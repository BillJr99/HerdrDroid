package com.billmongan.herdrdroid.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import com.billmongan.herdrdroid.AppViewModel
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
    private data class Answer(val server: String, val pane: String, val prompt: Prompt, val index: Int, val expires: Long, val questionKey: String)
    @Volatile private var pending: Answer? = null
    private var sessionRevision = 0L
    fun awaitingConfirmation(): Boolean = pending?.let { SystemClock.elapsedRealtime() < it.expires } == true

    private fun say(text: String) = Speaker.speak(context, "voice-${SystemClock.elapsedRealtimeNanos()}", text.take(3800))
    fun clear() { pending = null; sessionRevision++ }

    suspend fun handle(text: String) = mutex.withLock {
        if (vm.locked) return@withLock
        val revision = sessionRevision
        val command = parseVoiceCommand(text)
        if (command != VoiceCommand.Confirm) {
            pending = null
        }
        val paneId = (vm.screen as? Screen.Agent)?.paneId
        val server = vm.current
        val pane = paneId?.let(vm::pane)
        suspend fun freshQuestion(): Prompt? {
            if (pane == null || server == null) return null
            if (!vm.refreshVoiceScreen(server.id, pane.id) || sessionRevision != revision) return null
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
                        return@withLock
                    }
                    val candidates = vm.sessions().filter { session ->
                        (onServer == null || session.server.id == onServer.id) &&
                            listOf(session.pane.tabLabel, session.pane.agentName, session.pane.title).any { voiceName(it) == name }
                    }
                    val target = candidates.singleOrNull()
                    if (target == null) say(if (candidates.isEmpty()) "I could not match that pane." else "More than one pane matches. Say its name and server.")
                    else {
                        vm.switchTo(target.server.id, target.pane.id, voice = true)
                        say("Opening ${target.pane.agentName} on ${target.server.label}.")
                    }
                }
            }
            VoiceCommand.ReadReply -> {
                if (pane != null) vm.readTranscript(pane.id, allowBackground = true)
                val item = vm.transcriptItems.filterIsInstance<TextItem>().lastOrNull()
                say(if (pane != null && server != null && (vm.screen as? Screen.Agent)?.paneId == pane.id && vm.current?.id == server.id && item != null)
                    "${pane.agentName} on ${server.label}. ${item.text}" else "There is no reply to read.")
            }
            VoiceCommand.ReadQuestion -> {
                val prompt = freshQuestion()
                if (prompt == null || pane == null || server == null) say("I could not find a pending question.")
                else say("${pane.agentName} on ${server.label}. ${prompt.question} " +
                    prompt.choices.mapIndexed { index, choice -> "Option ${index + 1}. ${choice.label}." }.joinToString(" "))
            }
            is VoiceCommand.Choose -> {
                val prompt = freshQuestion()
                val choice = prompt?.choices?.getOrNull(command.index)
                val questionKey = if (pane != null && prompt != null) vm.voiceQuestionKey(pane.id, prompt) else null
                if (prompt == null || pane == null || server == null || choice == null || questionKey == null ||
                    com.billmongan.herdrdroid.herdr.isTypedAnswerChoice(choice.label) ||
                    com.billmongan.herdrdroid.herdr.grantsStandingPermission(choice.label) ||
                    choice.label.trim().lowercase() in setOf("submit", "cancel")) {
                    say("Use the question card on the phone for this answer.")
                } else {
                    pending = Answer(server.id, pane.id, prompt, command.index, SystemClock.elapsedRealtime() + 45_000L, questionKey)
                    say("Option ${command.index + 1}. ${choice.label}. Use the voice command confirm answer or cancel answer.")
                }
            }
            VoiceCommand.Confirm -> {
                val answer = pending
                pending = null
                if (answer == null || server?.id != answer.server || pane?.id != answer.pane ||
                    SystemClock.elapsedRealtime() >= answer.expires) {
                    say("There is no answer to confirm.")
                    return@withLock
                }
                val fresh = freshQuestion()
                if (fresh == null || vm.voiceQuestionKey(answer.pane, fresh) != answer.questionKey ||
                    !QuestionLog.sameCard(QuestionLog.signature(answer.prompt), QuestionLog.signature(fresh))) {
                    say("The question changed. No answer was sent.")
                } else {
                    vm.answerChoice(answer.pane, answer.prompt, answer.prompt.choices[answer.index], answer.questionKey) { result -> if (sessionRevision == revision) say(result) }
                }
            }
            VoiceCommand.Cancel -> say("Cancelled.")
            null -> say("I did not recognize that voice command.")
        }
    }
}

internal data class VoiceUi(val enabled: Boolean, val toggle: () -> Unit, val handsFree: () -> Unit, val command: (String) -> Unit)
internal val LocalVoiceUi = staticCompositionLocalOf<VoiceUi?> { null }
