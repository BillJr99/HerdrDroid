package com.billmongan.herdrdroid.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import com.billmongan.herdrdroid.AppViewModel
import kotlinx.coroutines.delay
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Choice
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.ui.Speaker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val VOICE_SPEECH_LIMIT = 3800
private const val ANSWER_WINDOW_MS = 45_000L
private const val QUESTION_SCAN_LIMIT = 8

internal fun speakCompleteReadback(text: String, speak: (String) -> Unit): Boolean {
    if (text.length > VOICE_SPEECH_LIMIT) return false
    speak(text)
    return true
}

internal class VoiceControls(
    private val vm: AppViewModel,
    private val speak: (String, String, Boolean, (() -> Unit)?) -> Unit,
    private val speaking: () -> Boolean,
) {
    constructor(context: Context, vm: AppViewModel) : this(vm,
        { key, text, verbatim, done -> Speaker.speak(context, key, text, done, verbatim) },
        { Speaker.speakingKey != null })
    private val mutex = Mutex()
    private data class Answer(val target: AppViewModel.CardTarget, val prompt: Prompt, val choice: Choice, val readback: String)
    private var pending: Answer? = null
    private var answerExpires = 0L
    private var readbackRevision = 0L
    private sealed interface MessageTarget {
        data class Local(val target: AppViewModel.VoiceTarget) : MessageTarget
        data class Remote(val target: AppViewModel.RemotePromptTarget) : MessageTarget
    }
    private fun messageTargetCurrent(target: MessageTarget) = when (target) {
        is MessageTarget.Local -> vm.voiceTarget() == target.target
        is MessageTarget.Remote -> vm.remotePromptCurrent(target.target)
    }
    private val messages = VoiceMessageDraft<MessageTarget>(SystemClock::elapsedRealtime)
    private data class Confirmation(val grammar: ConfirmationGrammar, val expires: Long, val valid: () -> Boolean)
    @Volatile private var confirmation: Confirmation? = null
    fun confirmationGrammar(): ConfirmationGrammar? = confirmation?.takeIf {
        SystemClock.elapsedRealtime() < it.expires && it.valid()
    }?.grammar
    private var sessionRevision = 0L

    private data class Spoken(val text: String, val target: AppViewModel.VoiceTarget?, val session: String?, val verbatim: Boolean)
    private var lastSpoken: Spoken? = null

    private fun say(text: String, session: String? = null, verbatim: Boolean = false, onRead: (() -> Unit)? = null) {
        lastSpoken = Spoken(text, vm.voiceTarget(), session, verbatim)
        speak("voice-${SystemClock.elapsedRealtimeNanos()}", text.take(VOICE_SPEECH_LIMIT), verbatim, onRead)
    }

    fun clear() {
        pending = null
        answerExpires = 0L
        messages.clear()
        confirmation = null
        sessionRevision++
        lastSpoken = null
        if (Speaker.speakingKey?.startsWith("voice-") == true) Speaker.stop()
    }

    private fun sayAnswer(answer: Answer, stillAllowed: () -> Boolean) {
        answerExpires = 0L
        confirmation = null
        val reading = ++readbackRevision
        say(answer.readback, verbatim = true) {
            if (reading == readbackRevision && stillAllowed() && pending === answer && vm.voiceCardCurrent(answer.target)) {
                answerExpires = SystemClock.elapsedRealtime() + ANSWER_WINDOW_MS
                confirmation = Confirmation(ConfirmationGrammar.ANSWER, answerExpires) { vm.voiceCardCurrent(answer.target) }
            }
        }
    }

    private fun sayVerbatim(text: String) = say(text, verbatim = true)

    private fun matchingPanes(target: VoiceCommand.Switch, serverId: String? = null) = vm.sessions().filter { session ->
        (serverId == null || session.server.id == serverId) &&
            listOf(session.pane.tabLabel, session.pane.agentName, session.pane.title)
                .any { voiceName(it) == voiceName(target.name) }
    }

    suspend fun handle(text: String) {
        val receivedIn = sessionRevision
        mutex.withLock {
            if (vm.locked || sessionRevision != receivedIn) return@withLock
            handleCommand(text)
        }
    }

    private suspend fun handleCommand(text: String) {
        val revision = sessionRevision
        val command = parseVoiceCommand(text)
        if (command != VoiceCommand.Confirm && command != VoiceCommand.Repeat) {
            pending = null
            answerExpires = 0L
        }
        if (command !is VoiceCommand.Dictate && command != VoiceCommand.ConfirmMessage && command != VoiceCommand.Repeat) messages.clear()
        if (command != VoiceCommand.Repeat) confirmation = null
        fun stillAllowed() = !vm.locked && revision == sessionRevision
        val paneId = (vm.screen as? Screen.Agent)?.paneId
        val server = vm.current
        val pane = paneId?.let(vm::pane)

        when (command) {
            is VoiceCommand.Switch -> {
                val matchingServers = voiceServersNamed(vm.servers, command.server ?: command.name)
                val onServer = matchingServers.singleOrNull()
                if (command.server == null && onServer != null) {
                    if (server?.id != onServer.id) vm.switchTo(onServer.id, null, voice = true)
                    say("Opening ${onServer.label}.")
                } else {
                    if (command.server != null && onServer == null) {
                        say("I could not match that server.")
                        return
                    }
                    val candidates = matchingPanes(command, onServer?.id)
                    val target = candidates.singleOrNull()
                    if (target == null) say(if (candidates.isEmpty()) "I could not match that pane." else "More than one pane matches. Say its name and server.")
                    else {
                        vm.switchTo(target.server.id, target.pane.id, voice = true)
                        say("Opening ${target.pane.agentName} on ${target.server.label}.")
                    }
                }
            }
            VoiceCommand.Repeat -> {
                val answer = pending
                if (answer != null) {
                    if (answerExpires > SystemClock.elapsedRealtime() && vm.voiceCardCurrent(answer.target) && !speaking()) {
                        sayAnswer(answer, ::stillAllowed)
                    } else {
                        pending = null
                        answerExpires = 0L
                        confirmation = null
                        say("No verified answer to repeat. Choose the option again.")
                    }
                    return
                }
                val target = vm.voiceTarget()
                val spoken = lastSpoken?.takeIf { it.target == target }
                val session = spoken?.session
                val verified = session == null || target?.let { vm.voiceReplySnapshot(it)?.session } == session
                if (stillAllowed() && vm.voiceTarget() == target) {
                    if (verified && spoken != null) say(spoken.text, session, spoken.verbatim)
                    else say("There is nothing to repeat.")
                }
            }
            is VoiceCommand.Message -> {
                val named = command.pane.server
                val servers = if (named == null) emptyList() else voiceServersNamed(vm.servers, named)
                val matches = if (named != null && servers.size != 1) emptyList() else matchingPanes(command.pane, servers.singleOrNull()?.id)
                val match = matches.singleOrNull()
                val current = vm.voiceTarget()
                if (match == null) {
                    say(if (matches.size > 1) "More than one pane matches. Use its unique tab name and server." else "I could not match that pane.")
                } else {
                    val label = "${match.pane.tabLabel.ifBlank { match.pane.agentName }} on ${match.server.label}"
                    val target = if (match.server.id != vm.current?.id) {
                        vm.bindRemotePrompt(match)?.let { MessageTarget.Remote(it) }
                    } else {
                        current?.takeIf { it.pane == match.pane.id }?.let { MessageTarget.Local(it) }
                    }
                    if (!stillAllowed()) return
                    if (target == null) {
                        if (match.server.id == vm.current?.id) say("Open $label first, then request the message again.")
                        else say("Not sent. I could not verify $label on its Fleet connection.")
                    } else {
                        messages.begin(target, label)
                        say("Say dictate, then your message to $label. Nothing will be sent without confirmation.")
                    }
                }
            }
            is VoiceCommand.Dictate -> {
                val draft = messages.dictate(messages.activeTarget(::messageTargetCurrent), command.text)
                if (draft == null) say("Start again with send a message to, followed by the pane name.")
                else {
                    val readback = "Message to ${draft.label}. ${draft.text}. Say confirm message or cancel message."
                    val started = speakCompleteReadback(readback) { fullText ->
                        confirmation = Confirmation(ConfirmationGrammar.MESSAGE, draft.expires) { messageTargetCurrent(draft.target) }
                        say(fullText) { if (stillAllowed()) messages.onReadBack(draft) }
                    }
                    if (!started) {
                        messages.clear()
                        say("That message is too long for me to read back in full. Start again and dictate a shorter message.")
                    }
                }
            }
            VoiceCommand.ConfirmMessage -> {
                if (speaking()) messages.clear()
                val draft = messages.consume(messages.activeTarget(::messageTargetCurrent))
                if (draft == null) say("There is no message to confirm.")
                else {
                    val allowed = { stillAllowed() && messages.isFresh(draft) }
                    val result = when (val target = draft.target) {
                        is MessageTarget.Local -> vm.sendVoiceMessage(target.target, draft.text, allowed)
                        is MessageTarget.Remote -> vm.sendRemotePrompt(target.target, draft.text, allowed).feedback
                    }
                    if (stillAllowed()) say(result)
                }
            }
            VoiceCommand.CancelMessage -> say("Message cancelled.")
            VoiceCommand.ReadReply -> {
                val target = vm.voiceTarget()
                val read = target?.let { vm.voiceReplySnapshot(it) }
                if (!stillAllowed()) return
                val item = read?.items?.filterIsInstance<TextItem>()?.lastOrNull { it.text.isNotBlank() }
                if (target == vm.voiceTarget() && item != null && pane != null && server != null)
                    say("${pane.agentName} on ${server.label}. ${item.text}", read?.session)
                else say("There is no reply to read.")
            }
            VoiceCommand.ListQuestions -> {
                val all = vm.sessions()
                val scanned = all.take(QUESTION_SCAN_LIMIT)
                val reads = coroutineScope { scanned.map { async { vm.readListedVoiceCard(it) } }.awaitAll() }
                if (!stillAllowed()) return
                val found = scanned.zip(reads).mapNotNull { (session, read) ->
                    read?.prompt?.let { prompt ->
                        val title = if (prompt.terminalReview) "Open the terminal to review." else prompt.question.take(200)
                        "${session.pane.tabLabel.ifBlank { session.pane.agentName }} on ${session.server.label}: $title"
                    }
                }
                val incomplete = all.size > scanned.size || reads.any { it == null }
                val summary = found.joinToString(". ").ifEmpty { "No pending questions verified." }
                val coverage = if (incomplete) " I could not verify every pane." else ""
                if (!speakCompleteReadback("$summary$coverage", ::sayVerbatim)) {
                    say("The question list is too long to read in full. Check the drawer.")
                }
            }
            VoiceCommand.ReadQuestion -> {
                val target = vm.voiceCardTarget()
                val read = target?.let { vm.readVoiceCard(it) }
                if (!stillAllowed()) return
                val prompt = read?.prompt
                if (target == null || read == null || !vm.voiceCardCurrent(target)) say("I could not verify a pending question in the open pane.")
                else if (prompt == null) say("I could not find a pending question in the open pane.")
                else if (prompt.terminalReview) say("Open the terminal to review this question.")
                else {
                    val name = target.pane.tabLabel.ifBlank { target.pane.agentName }
                    val text = "$name on ${server?.label}. ${prompt.voiceContext()}\n" +
                        prompt.choices.mapIndexed { index, choice -> "Option ${index + 1}. ${choice.label}." }.joinToString(" ")
                    if (!speakCompleteReadback(text, ::sayVerbatim)) say("This question is too long to read in full. Use the question card on the phone.")
                }
            }
            is VoiceCommand.Choose -> {
                val target = vm.voiceCardTarget()
                val prompt = target?.let { vm.readVoiceCard(it)?.prompt }
                if (!stillAllowed()) return
                val choice = prompt?.choices?.getOrNull(command.index)
                // 2.2.2: never while the open card still says it is reading the question.
                if (target != null && vm.voiceCardStillReading(target)) {
                    say("The question is still being read on the phone. No answer was sent.")
                } else if (target == null || prompt == null || server == null || !vm.voiceCardFitsChat(target, prompt)) {
                    say("I could not verify a pending question in the open pane. No answer was sent.")
                } else if (choice == null || voiceCardKeys(prompt, choice) == null) {
                    say("Use the question card on the phone for this answer. No answer was sent.")
                } else {
                    val name = target.pane.tabLabel.ifBlank { target.pane.agentName }
                    val readback = "$name on ${server.label}. ${prompt.voiceContext()}\n" +
                        "Option ${command.index + 1}. ${choice.label}. Say confirm answer or cancel answer."
                    if (!speakCompleteReadback(readback) {
                        val answer = Answer(target, prompt, choice, it)
                        pending = answer
                        sayAnswer(answer, ::stillAllowed)
                    }) {
                        say("This answer is too long to read back in full. Use the question card on the phone.")
                    }
                }
            }
            VoiceCommand.Confirm -> {
                val answer = pending
                val expires = answerExpires
                pending = null
                answerExpires = 0L
                if (answer == null || !vm.voiceCardCurrent(answer.target) || speaking() || SystemClock.elapsedRealtime() >= expires) {
                    say("There is no answer to confirm.")
                    return
                }
                if (vm.voiceCardStillReading(answer.target)) {
                    say("The question is still being read on the phone. No answer was sent.")
                    return
                }
                vm.answerChoice(answer.target.pane.id, answer.prompt, answer.choice, target = answer.target,
                    voiceAllowed = { stillAllowed() && vm.voiceCardCurrent(answer.target) && SystemClock.elapsedRealtime() < expires }) { result ->
                    if (stillAllowed()) say(result)
                }
            }
            VoiceCommand.Cancel -> say("Cancelled.")
            null -> say("I did not recognize that voice command.")
        }
    }

    suspend fun announceReplies() {
        val revision = sessionRevision
        var target: AppViewModel.VoiceTarget? = null
        val replies = NewVoiceReplies()
        fun active() = HandsFreeService.active && sessionRevision == revision && !vm.locked
        fun busy() = mutex.isLocked || speaking() || HandsFreeService.capturingCommand ||
            pending?.let { vm.voiceCardCurrent(it.target) && SystemClock.elapsedRealtime() < answerExpires } == true ||
            messages.activeTarget(::messageTargetCurrent) != null
        while (active()) {
            val current = vm.voiceTarget()
            if (current != target) replies.clear()
            target = current
            if (current != null && !busy()) {
                val snapshot = vm.voiceReplySnapshot(current)
                if (snapshot == null) replies.interrupt()
                if (snapshot != null && active() && vm.voiceTarget() == current && !busy()) {
                    replies.observe(snapshot)?.let { text ->
                        vm.pane(current.pane)?.let { pane -> say("${pane.agentName} on ${vm.current?.label}. $text", snapshot.session) }
                    }
                }
            }
            delay(3_000)
        }
    }
}

internal data class VoiceUi(
    val enabled: Boolean,
    val setEnabled: (Boolean) -> Unit,
    val startHandsFree: () -> Unit,
    val stopHandsFree: () -> Unit,
    val command: (String) -> Unit,
    val route: VoiceRoute,
    val chooseRoute: (VoiceRoute) -> Unit,
)
internal val LocalVoiceUi = staticCompositionLocalOf<VoiceUi?> { null }
