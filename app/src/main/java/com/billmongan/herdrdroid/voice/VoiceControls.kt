package com.billmongan.herdrdroid.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import com.billmongan.herdrdroid.AppViewModel
import kotlinx.coroutines.delay
import com.billmongan.herdrdroid.Screen
import com.billmongan.herdrdroid.herdr.Prompt
import com.billmongan.herdrdroid.herdr.Status
import com.billmongan.herdrdroid.herdr.detectPrompt
import com.billmongan.herdrdroid.transcript.QuestionLog
import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.ui.Speaker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VoiceControls(private val context: Context, private val vm: AppViewModel) {
    private val mutex = Mutex()
    private data class Answer(val target: AppViewModel.VoiceTarget, val prompt: Prompt, val index: Int, val expires: Long, val questionKey: String)
    private var pending: Answer? = null
    private var answerReadBack: Answer? = null
    private val messages = VoiceMessageDraft<AppViewModel.VoiceTarget>(SystemClock::elapsedRealtime)
    private data class Confirmation(val grammar: ConfirmationGrammar, val expires: Long, val target: AppViewModel.VoiceTarget)
    @Volatile private var confirmation: Confirmation? = null
    fun confirmationGrammar(): ConfirmationGrammar? = confirmation?.takeIf {
        SystemClock.elapsedRealtime() < it.expires && vm.voiceTarget() == it.target
    }?.grammar
    private var sessionRevision = 0L

    private data class Spoken(val text: String, val target: AppViewModel.VoiceTarget?, val session: String?)
    private var lastSpoken: Spoken? = null

    private fun say(text: String, session: String? = null, onRead: (() -> Unit)? = null) {
        lastSpoken = Spoken(text, vm.voiceTarget(), session)
        Speaker.speak(context, "voice-${SystemClock.elapsedRealtimeNanos()}", text.take(3800), onRead)
    }

    fun clear() {
        pending = null
        answerReadBack = null
        messages.clear()
        confirmation = null
        sessionRevision++
        lastSpoken = null
        if (Speaker.speakingKey?.startsWith("voice-") == true) Speaker.stop()
    }

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
            answerReadBack = null
        }
        if (command !is VoiceCommand.Dictate && command != VoiceCommand.ConfirmMessage && command != VoiceCommand.Repeat) messages.clear()
        if (command != VoiceCommand.Repeat) confirmation = null
        fun stillAllowed() = !vm.locked && revision == sessionRevision
        val paneId = (vm.screen as? Screen.Agent)?.paneId
        val server = vm.current
        val pane = paneId?.let(vm::pane)
        suspend fun freshQuestion(): Prompt? {
            val target = vm.voiceTarget() ?: return null
            if (pane == null || server == null || target.pane != pane.id || target.server != server.id) return null
            if (!vm.refreshVoiceScreen(server.id, pane.id) || !stillAllowed() || vm.voiceTarget() != target) return null
            return detectPrompt(vm.paneText, pane.status == Status.NEEDS_YOU, pane.agent, vm.versionOf(pane.id))
        }
        when (command) {
            is VoiceCommand.Switch -> {
                val name = voiceName(command.name)
                val explicitServer = command.server?.let(::voiceName)
                val matchingServers = vm.servers.filter { voiceName(it.label) == (explicitServer ?: name) }
                val onServer = matchingServers.singleOrNull()
                if (command.server == null && onServer != null) {
                    if (server?.id != onServer.id) vm.switchTo(onServer.id, null, voice = true)
                    say("Opening ${onServer.label}.")
                } else {
                    if (explicitServer != null && onServer == null) {
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
                val target = vm.voiceTarget()
                val spoken = lastSpoken?.takeIf { it.target == target }
                val session = spoken?.session
                val verified = session == null || target?.let { vm.voiceReplySnapshot(it)?.session } == session
                if (stillAllowed() && vm.voiceTarget() == target) {
                    if (verified && spoken != null) say(spoken.text, session)
                    else say("There is nothing to repeat.")
                }
            }
            is VoiceCommand.Message -> {
                val named = command.pane.server
                val servers = if (named == null) emptyList() else vm.servers.filter { voiceName(it.label) == voiceName(named) }
                val matches = if (named != null && servers.size != 1) emptyList() else matchingPanes(command.pane, servers.singleOrNull()?.id)
                val match = matches.singleOrNull()
                val current = vm.voiceTarget()
                if (match == null) {
                    say(if (matches.size > 1) "More than one pane matches. Use its unique tab name and server." else "I could not match that pane.")
                } else if (current == null || match.server.id != current.server || match.pane.id != current.pane) {
                    say("Switch to ${match.pane.tabLabel.ifBlank { match.pane.agentName }} on ${match.server.label} first, then request the message again.")
                } else {
                    messages.begin(current, "${match.pane.tabLabel.ifBlank { match.pane.agentName }} on ${match.server.label}")
                    say("Say dictate, then your message. Nothing will be sent without confirmation.")
                }
            }
            is VoiceCommand.Dictate -> {
                val draft = messages.dictate(vm.voiceTarget(), command.text)
                if (draft == null) say("Start again with send a message to, followed by the pane name.")
                else {
                    confirmation = Confirmation(ConfirmationGrammar.MESSAGE, draft.expires, draft.target)
                    say("Message to ${draft.label}. ${draft.text}. Say confirm message or cancel message.") {
                        if (stillAllowed()) messages.onReadBack(draft)
                    }
                }
            }
            VoiceCommand.ConfirmMessage -> {
                if (Speaker.speakingKey != null) messages.clear()
                val draft = messages.consume(vm.voiceTarget())
                if (draft == null) say("There is no message to confirm.")
                else {
                    val result = vm.sendVoiceMessage(draft.target, draft.text) { stillAllowed() && messages.isFresh(draft) }
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
            VoiceCommand.ReadQuestion -> {
                val prompt = freshQuestion()
                if (!stillAllowed()) return
                if (prompt == null || pane == null || server == null) say("I could not find a pending question.")
                else say("${pane.agentName} on ${server.label}. ${prompt.question} " +
                    prompt.choices.mapIndexed { index, choice -> "Option ${index + 1}. ${choice.label}." }.joinToString(" "))
            }
            is VoiceCommand.Choose -> {
                val target = vm.voiceTarget()
                val prompt = freshQuestion()
                if (!stillAllowed()) return
                val choice = prompt?.choices?.getOrNull(command.index)
                val questionKey = if (pane != null && prompt != null) vm.voiceQuestionKey(pane.id, prompt) else null
                if (prompt == null || pane == null || server == null || target == null || vm.voiceTarget() != target || choice == null || questionKey == null ||
                    com.billmongan.herdrdroid.herdr.isTypedAnswerChoice(choice.label) ||
                    com.billmongan.herdrdroid.herdr.grantsStandingPermission(choice.label) ||
                    choice.label.trim().lowercase() in setOf("submit", "cancel")) {
                    say("Use the question card on the phone for this answer.")
                } else {
                    val answer = Answer(target, prompt, command.index, SystemClock.elapsedRealtime() + 45_000L, questionKey)
                    pending = answer
                    confirmation = Confirmation(ConfirmationGrammar.ANSWER, answer.expires, answer.target)
                    say("Option ${command.index + 1}. ${choice.label}. Say confirm answer or cancel answer.") {
                        if (stillAllowed() && pending === answer) answerReadBack = answer
                    }
                }
            }
            VoiceCommand.Confirm -> {
                val answer = pending?.takeIf { it === answerReadBack }
                pending = null
                answerReadBack = null
                if (answer == null || vm.voiceTarget() != answer.target || Speaker.speakingKey != null ||
                    SystemClock.elapsedRealtime() >= answer.expires) {
                    say("There is no answer to confirm.")
                    return
                }
                val fresh = freshQuestion()
                if (!stillAllowed()) return
                if (fresh == null || vm.voiceQuestionKey(answer.target.pane, fresh) != answer.questionKey ||
                    !QuestionLog.sameCard(QuestionLog.signature(answer.prompt), QuestionLog.signature(fresh))) {
                    say("The question changed. No answer was sent.")
                } else {
                    vm.answerChoice(answer.target.pane, answer.prompt, answer.prompt.choices[answer.index], answer.questionKey,
                        voiceAllowed = { stillAllowed() && vm.voiceTarget() == answer.target && SystemClock.elapsedRealtime() < answer.expires },
                        voiceConnection = answer.target.connection) { result ->
                        if (stillAllowed()) say(result)
                    }
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
        fun busy() = mutex.isLocked || Speaker.speakingKey != null || HandsFreeService.capturingCommand ||
            pending?.let { it.target == vm.voiceTarget() && SystemClock.elapsedRealtime() < it.expires } == true ||
            messages.isPending(vm.voiceTarget())
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
