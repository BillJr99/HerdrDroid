package com.billmongan.herdrdroid.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.billmongan.herdrdroid.MainActivity
import com.billmongan.herdrdroid.R
import com.billmongan.herdrdroid.herdr.Choice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A tap on a notification button, handed from the receiver to the view model. */
data class NotifyAction(
    val serverId: String, val paneId: String, val keys: String, val pressEnter: Boolean, val question: String,
    /** The label of the choice the button answers (1.4.2): checked too, so a reworded question still matches its options. */
    val label: String = "",
    /** The pane's agent, so the tap reads the screen the way the alert did (1.4.2). */
    val agent: String? = null,
    /** True for the Esc button: [keys] is a key name sent with send-keys, not typed text. */
    val isKey: Boolean = false,
    /** True for a typed reply from the notification: [keys] is the message. */
    val isReply: Boolean = false,
    /**
     * Every option the alert was made with ([alertOptionsSig], 2.1.2): checked too, so a button
     * answers only the same set of options. Empty for an alert posted before 2.1.2.
     */
    val options: String = "",
    /**
     * The question's full identity ([alertIdentity], 2.1.2, Grok's RC review HIGH): the command an
     * approval asks about, or OpenCode's whole question frame. A tap answers only a screen whose
     * strict read has the identical identity, so a new permission with the same heading gets nothing.
     */
    val identity: String = "",
    /**
     * 2.1.4: a typed answer from the merged "Type your answer" button ([typedChatPair]): the keys of
     * the typed row it answers through ([label] is that row's label). Empty for any other reply.
     */
    val typedRow: String = "",
    /** In-process proof of the pane and connection that presented the notification. */
    val origin: String = "",
)

/**
 * A question's full identity for its alert (2.1.2, Grok's RC review HIGH): a digest of what makes
 * it this question and not another with the same heading. That is an approval's exact material
 * ([com.billmongan.herdrdroid.herdr.Prompt.approvalIdentity]: OpenCode's permission rows with its
 * command, Codex's and Claude's approval), OpenCode's whole question frame, else the question and
 * its option labels. Spaces, styles and the phone's glyph mapping are set aside, since the alert's
 * read and the tap's strict read draw the same screen slightly differently.
 */
internal fun alertIdentity(p: com.billmongan.herdrdroid.herdr.Prompt): String {
    val material = p.approvalIdentity ?: p.openCodeQuestionFrame
        ?: (listOf(p.question) + p.choices.map { it.keys + ":" + it.label }).joinToString("\n")
    val norm = com.billmongan.herdrdroid.herdr.forPhone(material).filterNot(Char::isWhitespace)
    val activeTab = p.parts?.tabs?.withIndex()?.singleOrNull { it.value.current }
    val tab = activeTab?.let { "\u0000${it.index}:${it.value.title}" }.orEmpty()
    val command = p.parts?.command?.let { "\u0000" + com.billmongan.herdrdroid.herdr.stripAnsi(it) }.orEmpty()
    return java.security.MessageDigest.getInstance("SHA-256").digest((norm + command + tab).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * What a "needs you" alert says (2.1.2): the question, and for an approval the command it asks
 * about (Grok's RC review HIGH: "Permission required" alone doesn't say what is being allowed).
 */
internal fun alertText(p: com.billmongan.herdrdroid.herdr.Prompt): String {
    val command = p.parts?.command ?: p.parts?.details?.firstOrNull { it.trimStart().startsWith("$ ") }
    if (command.isNullOrBlank()) return p.question
    val cmd = command.trim()
    // 2.1.4 (Bill: "parse them nicely maybe as newlines?"): a question that is its heading and then
    // its command ("Confirm the line: git -C … | awk …") shows the heading, then the command on
    // lines of its own, one per command line, since the alert's big text keeps real newlines.
    fun squash(s: String) = s.filterNot(Char::isWhitespace)
    val heading = p.parts?.heading
    if (heading != null && squash(p.question) == squash(heading) + ":" + squash(cmd) ||
        heading == null && squash(p.question) == squash(cmd)) {
        return listOfNotNull(heading, cmd.take(ALERT_COMMAND_CHARS)).joinToString("\n")
    }
    return if (p.question.contains(cmd)) p.question else "${p.question}\n${cmd.take(ALERT_COMMAND_CHARS)}"
}

/**
 * 2.2.2: the alert's text as the phone shows it, from [alertText]: an approval's heading, then its
 * command on lines of its own, then its context; Claude's review step as each question with the
 * answer given under it. Every line goes through [com.billmongan.herdrdroid.ui.QuestionText.alert],
 * so no focus marker, gutter, "●" or "→" row mark or key hint shows, and the command is kept exactly.
 */
internal fun alertBody(p: com.billmongan.herdrdroid.herdr.Prompt): String {
    val parts = p.parts
    val command = parts?.command
    val heading = parts?.heading
    val commandPreview = command?.takeIf { '\n' !in it && it.length > ALERT_COMMAND_CHARS }
        ?.take(ALERT_COMMAND_CHARS)?.trimEnd()
    val lines: List<String> = when {
        parts != null && heading != null && parts.tabs.any { it.submit && it.current } ->
            listOf(heading) + parts.details.flatMap { d ->
                com.billmongan.herdrdroid.ui.QuestionText.reviewAnswer(d)?.let { (q, a) -> listOf(q, "Your answer: $a") } ?: listOf(d)
            }
        command != null && heading != null && !command.contains('\n') && command.length <= ALERT_COMMAND_CHARS -> {
            val context = parts.details.filter { d -> d.filterNot(Char::isWhitespace) != heading.filterNot(Char::isWhitespace) }
            // 2.2.2 review: every line of context, up to a total length, the cut one ending in "…".
            var budget = ALERT_CONTEXT_CHARS
            listOf(heading, command) + context.mapNotNull { d ->
                if (budget <= 0) null else (if (d.length <= budget) d else d.take(budget).trimEnd() + "…").also { budget -= d.length }
            }
        }
        commandPreview != null && heading != null ->
            listOf(heading, commandPreview, "Full command in the app.")
        // 2.2.4: a tall command (Claude's heredoc): the heading, the command's first lines, how many more, then the context.
        command != null && heading != null && command.contains('\n') && command.length > ALERT_COMMAND_CHARS -> {
            val all = command.lines()
            val shown = all.take(ALERT_COMMAND_LINES)
            val context = parts.details.filter { d -> d.filterNot(Char::isWhitespace) != heading.filterNot(Char::isWhitespace) }
            var budget = ALERT_CONTEXT_CHARS
            listOf(heading) + shown + listOfNotNull("(${all.size - shown.size} more lines in the app)".takeIf { all.size > shown.size }) +
                context.mapNotNull { d ->
                    if (budget <= 0) null else (if (d.length <= budget) d else d.take(budget).trimEnd() + "…").also { budget -= d.length }
                }
        }
        else -> listOf(alertText(p))
    }
    // Each piece cleaned on its own, so a heading and the line under it never run together.
    val code = command?.lines().orEmpty() + listOfNotNull(commandPreview)
    return lines.map { com.billmongan.herdrdroid.ui.QuestionText.alert(it, code) }.filter { it.isNotEmpty() }.joinToString("\n")
}

/** How much context an approval's alert carries under its command (2.2.2). */
internal const val ALERT_CONTEXT_CHARS = 600

/** How much of a long single-line command fits in the notification preview. */
internal const val ALERT_COMMAND_CHARS = 400

/** How many lines of a tall command an approval's alert shows (2.2.4). */
internal const val ALERT_COMMAND_LINES = 8

/** How many action buttons Android shows on a notification. */
internal const val MAX_ALERT_BUTTONS = 3

/** One button of a "needs you" alert (2.1.2): an option, Esc, or Reply. */
internal data class AlertButton(val title: String, val choice: Choice? = null, val esc: Boolean = false, val reply: Boolean = false,
    /** 2.1.4: the merged "Type your answer" button, a reply typed through [choice]'s row. */
    val typed: Boolean = false)

/**
 * What a "needs you" alert offers (2.1.2, Bill: "The alert should offer whatever options the
 * question does"): [buttons] in the order they show, and [inApp], the options left for the app's
 * question card, which tapping the alert opens.
 */
internal data class AlertPlan(val buttons: List<AlertButton>, val inApp: List<String>)

/** The option set an alert was made with, for [NotifyAction.options] (2.1.2). */
internal fun alertOptionsSig(choices: List<Choice>): String =
    choices.joinToString("\n") { it.keys + "\t" + it.label.take(200).filterNot(Char::isWhitespace) }

/**
 * The buttons of a "needs you" alert for [prompt] (2.1.2): the question's own options, in the
 * order it shows them, the same ones its chat card offers. Esc stays wherever the alert had it
 * (every question but Hermes's clarify, whose card leaves cancelling to Terminal); an option that
 * itself answers with Esc (OpenCode's Reject, an "(esc)" option) is that Esc, so it takes Esc's
 * place instead of a second button. Reply comes only where the question takes free text: one with
 * no options, or OpenCode's question with its "Type your own answer" row. Android shows at most
 * [MAX_ALERT_BUTTONS]: when more are needed, Esc keeps its slot and the options fill the rest in
 * order, and the ones left over (Reply included, which comes after the options as on screen) are
 * named in the alert's text for the question card that tapping the alert opens.
 */
internal fun alertPlan(prompt: com.billmongan.herdrdroid.herdr.Prompt, terminalReview: Boolean = prompt.terminalReview, reply: Boolean = true): AlertPlan {
    if (terminalReview || prompt.terminalReview || prompt.historyReviewOnly || prompt.clipped || prompt.guardedShortcutApproval) return AlertPlan(emptyList(), emptyList())
    val escChoice = prompt.choices.firstOrNull { com.billmongan.herdrdroid.herdr.menuKeys(prompt, it) == listOf("esc") }
    // 2.1.4: "Type something." and "Chat about this" are one "Type your answer" reply button.
    val merged = com.billmongan.herdrdroid.herdr.typedChatPair(prompt.choices)?.first
    // 2.2.8: an option the card shows disabled (never sent) is never an alert button.
    val answers = com.billmongan.herdrdroid.herdr.shownChoices(prompt.choices).filter {
        com.billmongan.herdrdroid.herdr.blockedChoiceReason(it) == null &&
            it.keys != com.billmongan.herdrdroid.herdr.OMP_STEER &&
            it.keys != com.billmongan.herdrdroid.herdr.OmpAsk.SUBMIT &&
            !it.keys.startsWith("grok-privacy:")
    }.map { c ->
        if (c == merged && reply) return@map AlertButton(com.billmongan.herdrdroid.herdr.TYPED_ANSWER_LABEL, choice = c, typed = true)
        // 2.2.2: the button reads the label as the card shows it (no marker, gutter or hint); its answer keeps c.label.
        val display = com.billmongan.herdrdroid.ui.QuestionText.label(if (c == merged) c.label else com.billmongan.herdrdroid.herdr.shownChoiceLabel(prompt.choices, c), c.keys)
        val title = if (c == escChoice && !c.label.contains("esc", ignoreCase = true)) "${com.billmongan.herdrdroid.ui.QuestionText.label(c.label, c.keys).take(18).trimEnd()} (Esc)" else
            display.take(24).trimEnd()
        AlertButton(title, choice = c)
    } + listOfNotNull(
        AlertButton(if (prompt.openCodeCustom != null) "Type answer" else "Reply", reply = true).takeIf {
            reply && (prompt.choices.isEmpty() || prompt.openCodeCustom != null)
        },
    )
    val esc = AlertButton("Esc", esc = true).takeIf { !prompt.hermesClarify && escChoice == null }
    // Today's order for a question with no options: Esc, then Reply.
    if (prompt.choices.isEmpty()) return AlertPlan(listOfNotNull(esc) + answers, emptyList())
    if (answers.size + (if (esc != null) 1 else 0) <= MAX_ALERT_BUTTONS) return AlertPlan(answers + listOfNotNull(esc), emptyList())
    val escSlot = esc ?: answers.firstOrNull { escChoice != null && it.choice == escChoice }
    val rest = answers.filter { it !== escSlot }
    val shown = rest.take(MAX_ALERT_BUTTONS - (if (escSlot != null) 1 else 0))
    return AlertPlan(shown + listOfNotNull(escSlot), rest.drop(shown.size).map { if (it.reply || it.typed) "Type your own answer" else com.billmongan.herdrdroid.ui.QuestionText.label(com.billmongan.herdrdroid.herdr.shownChoiceLabel(prompt.choices, it.choice!!), it.choice.keys) })
}

/**
 * Whether a "needs you" alert for [prompt] may carry a Reply box (2.1.1, Grok's RC review H1).
 * Claude's sessions list has no options, but typed text there starts a new session, so its alert
 * gets Esc only.
 */
internal fun notificationOffersReply(prompt: com.billmongan.herdrdroid.herdr.Prompt?): Boolean =
    prompt?.guardedShortcutApproval != true && prompt?.claudeBackground != true && prompt?.openCodePermission != true && prompt?.museQuota != true && prompt?.hermesClarify != true

/**
 * The keys an alert button sends to an OpenCode permission (2.1.2), from [now], the strict styled
 * read taken at the tap; null when nothing may be sent. Esc and Reject are Esc, which rejects
 * wherever the highlight is. Allow once and Always allow (offered since 2.1.2, Bill: "The alert
 * should offer whatever options the question does") move from the highlight the read shows, so a
 * read with no highlight (plain text, or an ambiguous frame) sends nothing. No digit is ever typed
 * into the dialog.
 */
internal fun openCodePermissionAlertKeys(now: com.billmongan.herdrdroid.herdr.Prompt, a: NotifyAction): List<String>? {
    if (!now.openCodePermission || a.isReply || !notificationAnswerStillFits(now, a)) return null
    if (a.isKey) return if (a.keys == "esc") listOf("esc") else null
    val choice = now.choices.firstOrNull { it.keys == a.keys } ?: return null
    return com.billmongan.herdrdroid.herdr.menuKeys(now, choice)?.takeIf { keys -> keys.isNotEmpty() && keys.all { it in setOf("left", "right", "enter", "esc") } }
}

/**
 * Whether a notification button still answers the question on screen [now] (1.4.2): the same
 * question (spaces aside), and for a choice, an option with the same keys and the same label. Esc
 * needs only the same question. A typed reply is not checked here.
 */
fun notificationAnswerStillFits(now: com.billmongan.herdrdroid.herdr.Prompt?, a: NotifyAction): Boolean {
    // 2.1.2: Hermes's clarify and OpenCode's question dialog are answered from alerts too, through
    // the guarded checks in the app; a screen only Terminal can answer never is.
    if (now == null || now.terminalReview || now.guardedShortcutApproval) return false
    val sameQuestion = now.question.filterNot { it.isWhitespace() } == a.question.filterNot { it.isWhitespace() }
    if (!sameQuestion) return false
    // The same set of options as the alert's (2.1.2), for its Esc as much as its options, and the
    // same question identity: an alert made since 2.1.2 always carries both (Grok's RC review HIGH).
    if (a.options.isNotEmpty() || a.identity.isNotEmpty()) {
        if (a.options != alertOptionsSig(now.choices) || a.identity != alertIdentity(now)) return false
    }
    if (a.isKey) return true
    fun norm(s: String) = s.take(200).filterNot { it.isWhitespace() }
    // An empty label is a button from an alert posted before 1.4.2: the question alone decides, as then.
    return now.choices.any { it.keys == a.keys && (a.label.isEmpty() || norm(it.label) == norm(a.label)) }
}

/** Connects the notification buttons (a broadcast receiver) to the running app. */
object AppBus {
    private val _actions = MutableSharedFlow<NotifyAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<NotifyAction> = _actions
    /**
     * Hands the tap to the running app. False when nothing is listening (the app isn't running):
     * the value would be dropped, so the caller says to open the app instead (1.4.2, from review).
     */
    fun emit(a: NotifyAction): Boolean = _actions.subscriptionCount.value > 0 && _actions.tryEmit(a)
}

/**
 * The phone's own alerts, made from what the app sees over SSH: no push service and nothing on
 * the computer. "Needs you" alerts carry the question's options as buttons ([alertPlan]); tapping one
 * answers in the pane after the app checks the same question is still showing.
 */
object Notify {
    const val ATTENTION = "attention"
    const val WATCHING = "watching"
    const val USAGE = "usage"
    const val EXTRA_SERVER = "herdrdroid.server"
    const val EXTRA_PANE = "herdrdroid.pane"
    const val KEY_REPLY = "herdrdroid.reply"

    /** A Reply action with a text box: what you type goes to the agent (Android's inline reply). */
    private fun replyAction(
        ctx: Context, serverId: String, paneId: String, agent: String,
        /** Shown as one-tap quick replies above the keyboard; a choice's label answers with its keys. */
        quick: List<String> = emptyList(), choices: List<Choice> = emptyList(), question: String = "",
        agentKind: String? = null, options: String = "", identity: String = "", title: String = "Reply", origin: String = "",
    ): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $agent")
            .apply { if (quick.isNotEmpty()) setChoices(quick.map { it.take(40) }.toTypedArray()) }.build()
        val i = Intent(ctx, ActionReceiver::class.java)
            .forButton(serverId, paneId, if (choices.isEmpty() && question.isEmpty()) 5 else 4, identity, origin)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId).putExtra("reply", true)
            .putExtra("question", question)
            .putExtra("cLabels", choices.map { it.label.take(40) }.toTypedArray())
            .putExtra("cKeys", choices.map { it.keys }.toTypedArray())
            .putExtra("cEnter", choices.map { it.pressEnter }.toBooleanArray())
            .putExtra("options", options).putExtra("identity", identity).putExtra("agent", agentKind).putExtra("origin", origin)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, code(serverId, paneId, if (choices.isEmpty() && question.isEmpty()) 5 else 4), i, flags)
        return NotificationCompat.Action.Builder(0, title, pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    /**
     * The merged "Type your answer" button (2.1.4): a reply whose text the app types through the
     * typed row [row] ("Type something."), after the same question, options and identity checks
     * as the other buttons. Its own slot (6), so it never shares extras with Reply or an option.
     */
    private fun typedReplyAction(ctx: Context, serverId: String, paneId: String, agent: String, agentKind: String?, question: String,
        row: Choice, options: String, identity: String, origin: String): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel("Answer $agent").build()
        val i = Intent(ctx, ActionReceiver::class.java)
            .forButton(serverId, paneId, 6, identity, origin)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId).putExtra("reply", true)
            .putExtra("question", question).putExtra("typedRow", row.keys).putExtra("label", row.label).putExtra("agent", agentKind)
            .putExtra("options", options).putExtra("identity", identity).putExtra("origin", origin)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, code(serverId, paneId, 6), i, flags)
        return NotificationCompat.Action.Builder(0, com.billmongan.herdrdroid.herdr.TYPED_ANSWER_LABEL, pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ATTENTION, "Agents that need you or finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent is waiting on a question, or finished its work."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(USAGE, "Usage limits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An agent's usage limit passed 80% or 95%, or reset."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(WATCHING, "Watching agents", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while HerdrDroid watches your agents in the background."
            },
        )
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun id(serverId: String, paneId: String) = (serverId + "|" + paneId).hashCode()

    /**
     * The request code of one button of one pane's alert (1.4.2): a separate slot for each kind of
     * button, so a "finished" alert's Reply never rewrites the extras of a question's buttons
     * (PendingIntents with the same code share them under FLAG_UPDATE_CURRENT).
     */
    private fun code(serverId: String, paneId: String, slot: Int) = id(serverId, paneId) * 8 + slot

    /**
     * A data address naming the server, pane and button (1.4.2, from review): part of the
     * PendingIntent's identity, so two panes whose request codes happen to collide never share extras.
     */
    private fun Intent.forButton(serverId: String, paneId: String, slot: Int, identity: String, origin: String): Intent =
        setData(android.net.Uri.Builder().scheme("herdrdroid").authority("answer")
            .appendPath(serverId).appendPath(paneId).appendPath(slot.toString()).appendPath(identity).appendPath(origin).build())

    private fun openIntent(ctx: Context, serverId: String, paneId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, id(serverId, paneId), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * An agent is waiting on a question: its options become buttons as [alertPlan] lays them out
     * (2.1.2), from [prompt] when given, else from [choices].
     */
    fun needsYou(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, question: String, choices: List<Choice>, agentKind: String? = null, terminalReview: Boolean = false, reply: Boolean = true,
        prompt: com.billmongan.herdrdroid.herdr.Prompt? = null,
        /** 2.2.2: this replaces the neutral alert for the same question (same id), so it doesn't sound again. */
        quietUpdate: Boolean = false, origin: String = "") {
        if (!allowed(ctx)) return
        // Past the fingerprint window (1.3.1): the alert still comes, without the question or the
        // answer buttons; opening it asks for the fingerprint first.
        if (com.billmongan.herdrdroid.store.Vaults.locked()) {
            val n = NotificationCompat.Builder(ctx, ATTENTION)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("$agent needs you · $serverLabel")
                .setContentText(LOCKED_TEXT)
                .setContentIntent(openIntent(ctx, serverId, paneId))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), n) }
            return
        }
        val card = prompt?.takeIf { it.question == question } ?: com.billmongan.herdrdroid.herdr.Prompt(question, choices)
        val plan = alertPlan(card, terminalReview, reply)
        // The options with no button of their own (2.1.2): named here, chosen on the question card the alert opens.
        // 2.2.2: the text as the card reads it (no markers, gutters or hints; wrapped lines joined).
        val asked = (if (card === prompt) alertBody(card) else com.billmongan.herdrdroid.ui.QuestionText.alert(question)).ifEmpty { question }
        val text = if (plan.inApp.isEmpty()) asked
            else "$asked\n\nMore in HerdrDroid (tap to open the question): " + plan.inApp.joinToString(" · ") { it.take(40) }
        val b = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent needs you · $serverLabel")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quietUpdate)
            .setSilent(quietUpdate)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (plan.buttons.isEmpty()) {
            runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), b.build()) }
            return
        }
        // One set of answer buttons (1.4.2, Bill: the pill-shaped quick replies above the buttons
        // didn't answer, and the buttons then said the question had changed), all through the same
        // checked path, each carrying the alert's whole option set (2.1.2).
        val options = alertOptionsSig(card.choices)
        val identity = alertIdentity(card)
        var slot = 0
        for (button in plan.buttons) {
            val c = button.choice
            when {
                button.typed && c != null -> b.addAction(typedReplyAction(ctx, serverId, paneId, agent, agentKind, question, c, options, identity, origin))
                // Bill (2026-11-25): an approve-from-now-on option is confirmed in the app first: its
                // button opens the question card, where tapping it asks "Approve everything from now on?".
                c != null && com.billmongan.herdrdroid.herdr.approvesFromNowOn(c) -> b.addAction(0, button.title, openIntent(ctx, serverId, paneId))
                c != null -> {
                    val n = slot++
                    val i = Intent(ctx, ActionReceiver::class.java)
                        .forButton(serverId, paneId, n, identity, origin)
                        .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
                        .putExtra("keys", c.keys).putExtra("enter", c.pressEnter).putExtra("question", question).putExtra("label", c.label).putExtra("agent", agentKind)
                        .putExtra("options", options).putExtra("identity", identity).putExtra("origin", origin)
                    val pi = PendingIntent.getBroadcast(ctx, code(serverId, paneId, n), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    b.addAction(0, button.title, pi)
                }
                button.esc -> {
                    // Esc declines or dismisses the question, the same as the Esc key in the app.
                    val esc = Intent(ctx, ActionReceiver::class.java)
                        .forButton(serverId, paneId, 3, identity, origin)
                        .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
                        .putExtra("keys", "esc").putExtra("isKey", true).putExtra("question", question).putExtra("agent", agentKind)
                        .putExtra("options", options).putExtra("identity", identity).putExtra("origin", origin)
                    b.addAction(0, "Esc", PendingIntent.getBroadcast(ctx, code(serverId, paneId, 3), esc, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                button.reply -> b.addAction(replyAction(
                    ctx, serverId, paneId, agent, question = question, agentKind = agentKind,
                    options = options, identity = identity, title = button.title, origin = origin,
                ))
            }
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), b.build()) }
    }

    /** What an alert says instead of its text once the fingerprint window has passed (1.3.1). */
    const val LOCKED_TEXT = "Unlock HerdrDroid to read"

    /** A watched server can't be reached because its saved password or key is locked (1.3.1). Once per server per unlock. */
    fun locked(ctx: Context, serverId: String, serverLabel: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$serverLabel isn't being watched")
            .setContentText("$LOCKED_TEXT: its saved password or key is locked until you unlock the app.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$LOCKED_TEXT: its saved password or key is locked until you unlock the app."))
            .setContentIntent(openIntent(ctx, serverId, ""))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, "__locked__"), n) }
    }

    /**
     * An agent stopped on an error (1.4.2: a failed compaction, a rate limit), not a question: no
     * answer buttons, opening the pane shows the terminal-ready banner.
     */
    fun agentError(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, error: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent stopped on an error · $serverLabel")
            .setContentText(if (com.billmongan.herdrdroid.store.Vaults.locked()) LOCKED_TEXT else "$agent: $error")
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), n) }
    }

    /** An agent waits, but its screen couldn't be read (1.4.2): a plain notice, no question and no buttons. */
    fun unread(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent may need you · $serverLabel")
            .setContentText("The app couldn't read its screen. Open HerdrDroid to see it.")
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), n) }
    }

    /** An agent finished its work. */
    fun done(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, preview: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent finished · $serverLabel")
            .setContentText(if (com.billmongan.herdrdroid.store.Vaults.locked()) LOCKED_TEXT else preview.ifBlank { "Ready for your next message." })
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .addAction(replyAction(ctx, serverId, paneId, agent, listOf("Continue", "Yes", "No", "Thanks, that's all")))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), n) }
    }

    /** A usage limit passed 80% or 95%, or reset. One notification per agent and limit. */
    fun usage(ctx: Context, serverId: String, agentKey: String, label: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val open = PendingIntent.getActivity(ctx, ("usage|$serverId").hashCode(), Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, serverId).putExtra("usage", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, USAGE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(("usage|$serverId|$agentKey|$label").hashCode(), n) }
    }

    /** Whether this pane's alert is still showing (2.2.2): not tapped (it cancels itself) or dismissed. */
    fun isShowing(ctx: Context, serverId: String, paneId: String): Boolean = runCatching {
        val m = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return false
        m.activeNotifications.any { it.id == id(serverId, paneId) }
    }.getOrDefault(false)

    fun clear(ctx: Context, serverId: String, paneId: String) {
        NotificationManagerCompat.from(ctx).cancel(id(serverId, paneId))
    }

    /**
     * The alert after a button was tapped (1.4.2): it says what is being sent, with no buttons, so a
     * second tap can't race the first; the app replaces it with the outcome or clears it.
     */
    fun sending(ctx: Context, serverId: String, paneId: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), n) }
    }
}

/** Receives a notification button and passes it to the app, which answers in the pane. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val server = intent.getStringExtra(Notify.EXTRA_SERVER) ?: return
        val pane = intent.getStringExtra(Notify.EXTRA_PANE) ?: return
        val origin = intent.getStringExtra("origin").orEmpty()
        if (intent.getBooleanExtra("reply", false)) {
            val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notify.KEY_REPLY)?.toString()?.trim()
            val labels = intent.getStringArrayExtra("cLabels").orEmpty()
            val at = labels.indexOf(text)
            val typedRow = intent.getStringExtra("typedRow").orEmpty()
            val taken = if (typedRow.isNotEmpty()) {
                // The merged "Type your answer" (2.1.4): typed through its row after the app's checks.
                !text.isNullOrEmpty() && AppBus.emit(NotifyAction(server, pane, text, true, intent.getStringExtra("question").orEmpty(),
                    label = intent.getStringExtra("label").orEmpty(), agent = intent.getStringExtra("agent"), isReply = true,
                    options = intent.getStringExtra("options").orEmpty(), identity = intent.getStringExtra("identity").orEmpty(), typedRow = typedRow, origin = origin))
            } else if (at >= 0) {
                // A quick reply that is one of the agent's choices: answer with that choice's keys,
                // checked against the same question, like the choice buttons.
                AppBus.emit(NotifyAction(server, pane, intent.getStringArrayExtra("cKeys")?.getOrNull(at).orEmpty(),
                    intent.getBooleanArrayExtra("cEnter")?.getOrNull(at) ?: false, intent.getStringExtra("question").orEmpty(),
                    label = text.orEmpty(), agent = intent.getStringExtra("agent"),
                    options = intent.getStringExtra("options").orEmpty(), identity = intent.getStringExtra("identity").orEmpty(),
                    origin = origin))
            } else if (!text.isNullOrEmpty()) {
                // OpenCode's question (2.1.2): the reply carries the question it answers, for the app to check.
                val question = intent.getStringExtra("question") ?: intent.getStringExtra("customQuestion")
                AppBus.emit(NotifyAction(server, pane, text, true, question.orEmpty(), agent = intent.getStringExtra("agent"),
                    isReply = true, options = intent.getStringExtra("options").orEmpty(), identity = intent.getStringExtra("identity").orEmpty(), origin = origin))
            } else false
            if (!taken && !text.isNullOrEmpty())
                Notify.sending(context, server, pane, "Open HerdrDroid to answer", "The app could not receive the reply. Tap to open it.")
            return
        }
        val label = intent.getStringExtra("label").orEmpty()
        val taken = AppBus.emit(
            NotifyAction(
                server, pane, intent.getStringExtra("keys").orEmpty(), intent.getBooleanExtra("enter", false),
                intent.getStringExtra("question").orEmpty(), label = label, agent = intent.getStringExtra("agent"), isKey = intent.getBooleanExtra("isKey", false),
                options = intent.getStringExtra("options").orEmpty(), identity = intent.getStringExtra("identity").orEmpty(), origin = origin,
            ),
        )
        if (!taken) Notify.sending(context, server, pane, "Open HerdrDroid to answer", "The app wasn't running, so the answer wasn't sent. Tap to open it.")
    }
}

/**
 * Keeps HerdrDroid running while it watches agents in the background (the view model does the
 * watching). Shown as a quiet "Watching" notification; stopped from Settings or when the app
 * disconnects.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.channels(this)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notify.WATCHING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Watching your agents")
            .setContentText(intent?.getStringExtra("text") ?: "HerdrDroid will tell you when one needs you or finishes.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 7, n, type)
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, WatchService::class.java).putExtra("text", text)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }
    }
}
