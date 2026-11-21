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
)

/**
 * Whether a "needs you" alert for [prompt] may carry a Reply box (2.1.1, Grok's RC review H1).
 * Claude's sessions list has no options, but typed text there starts a new session, so its alert
 * gets Esc only.
 */
internal fun notificationOffersReply(prompt: com.billmongan.herdrdroid.herdr.Prompt?): Boolean =
    prompt?.claudeBackground != true && prompt?.openCodePermission != true

/**
 * The keys an alert button sends to an OpenCode permission (2.1.2), from [now], the strict styled
 * read taken at the tap; null when nothing may be sent. Esc and Reject are Esc, which rejects
 * wherever the highlight is. Allow once moves from the highlight the read shows, so a read with no
 * highlight (plain text, or an ambiguous frame) sends nothing. Always allow is never sent, and no
 * digit is ever typed into the dialog.
 */
internal fun openCodePermissionAlertKeys(now: com.billmongan.herdrdroid.herdr.Prompt, a: NotifyAction): List<String>? {
    if (!now.openCodePermission || a.isReply || !notificationAnswerStillFits(now, a)) return null
    if (a.isKey) return if (a.keys == "esc") listOf("esc") else null
    val choice = now.choices.firstOrNull { it.keys == a.keys } ?: return null
    if (choice.label.equals("Always allow", true)) return null
    return com.billmongan.herdrdroid.herdr.menuKeys(now, choice)?.takeIf { keys -> keys.isNotEmpty() && keys.all { it in setOf("left", "right", "enter", "esc") } }
}

/**
 * Whether a notification button still answers the question on screen [now] (1.4.2): the same
 * question (spaces aside), and for a choice, an option with the same keys and the same label. Esc
 * needs only the same question. A typed reply is not checked here.
 */
fun notificationAnswerStillFits(now: com.billmongan.herdrdroid.herdr.Prompt?, a: NotifyAction): Boolean {
    if (now == null || now.terminalReview || now.hermesClarify || now.openCodeQuestionFrame != null) return false
    val sameQuestion = now.question.filterNot { it.isWhitespace() } == a.question.filterNot { it.isWhitespace() }
    if (!sameQuestion) return false
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
 * the computer. "Needs you" alerts carry the agent's first two choices as buttons; tapping one
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
    ): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $agent")
            .apply { if (quick.isNotEmpty()) setChoices(quick.map { it.take(40) }.toTypedArray()) }.build()
        val i = Intent(ctx, ActionReceiver::class.java)
            .forButton(serverId, paneId, if (choices.isEmpty() && question.isEmpty()) 5 else 4)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId).putExtra("reply", true)
            .putExtra("question", question)
            .putExtra("cLabels", choices.map { it.label.take(40) }.toTypedArray())
            .putExtra("cKeys", choices.map { it.keys }.toTypedArray())
            .putExtra("cEnter", choices.map { it.pressEnter }.toBooleanArray())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, code(serverId, paneId, if (choices.isEmpty() && question.isEmpty()) 5 else 4), i, flags)
        return NotificationCompat.Action.Builder(0, "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
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
    private fun Intent.forButton(serverId: String, paneId: String, slot: Int): Intent =
        setData(android.net.Uri.Builder().scheme("herdrdroid").authority("answer")
            .appendPath(serverId).appendPath(paneId).appendPath(slot.toString()).build())

    private fun openIntent(ctx: Context, serverId: String, paneId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, id(serverId, paneId), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** An agent is waiting on a question; [choices] become up to two buttons, plus Esc to decline. */
    fun needsYou(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, question: String, choices: List<Choice>, agentKind: String? = null, terminalReview: Boolean = false, reply: Boolean = true) {
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
        val b = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent needs you · $serverLabel")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (terminalReview) {
            runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), b.build()) }
            return
        }
        // One set of answer buttons (1.4.2, Bill: the pill-shaped quick replies above the buttons
        // didn't answer, and the buttons then said the question had changed): the first two choices
        // and Esc, all through the same checked path. Android shows three buttons, so there is no
        // Reply box on a question with options; one without options gets Reply to type the answer.
        choices.take(2).forEachIndexed { n, c ->
            val i = Intent(ctx, ActionReceiver::class.java)
                .forButton(serverId, paneId, n)
                .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
                .putExtra("keys", c.keys).putExtra("enter", c.pressEnter).putExtra("question", question).putExtra("label", c.label).putExtra("agent", agentKind)
            val pi = PendingIntent.getBroadcast(ctx, code(serverId, paneId, n), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, c.label.take(24), pi)
        }
        // Esc declines or dismisses the question, the same as the Esc key in the app.
        val esc = Intent(ctx, ActionReceiver::class.java)
            .forButton(serverId, paneId, 3)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
            .putExtra("keys", "esc").putExtra("isKey", true).putExtra("question", question).putExtra("agent", agentKind)
        b.addAction(0, "Esc", PendingIntent.getBroadcast(ctx, code(serverId, paneId, 3), esc, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (choices.isEmpty() && reply) b.addAction(replyAction(ctx, serverId, paneId, agent, question = question))
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
        if (intent.getBooleanExtra("reply", false)) {
            val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notify.KEY_REPLY)?.toString()?.trim()
            val labels = intent.getStringArrayExtra("cLabels").orEmpty()
            val at = labels.indexOf(text)
            if (at >= 0) {
                // A quick reply that is one of the agent's choices: answer with that choice's keys,
                // checked against the same question, like the choice buttons.
                AppBus.emit(NotifyAction(server, pane, intent.getStringArrayExtra("cKeys")?.getOrNull(at).orEmpty(),
                    intent.getBooleanArrayExtra("cEnter")?.getOrNull(at) ?: false, intent.getStringExtra("question").orEmpty()))
            } else if (!text.isNullOrEmpty()) AppBus.emit(NotifyAction(server, pane, text, true, "", isReply = true))
            Notify.clear(context, server, pane)
            return
        }
        val label = intent.getStringExtra("label").orEmpty()
        val taken = AppBus.emit(
            NotifyAction(
                server, pane, intent.getStringExtra("keys").orEmpty(), intent.getBooleanExtra("enter", false),
                intent.getStringExtra("question").orEmpty(), label = label, agent = intent.getStringExtra("agent"), isKey = intent.getBooleanExtra("isKey", false),
            ),
        )
        if (taken) Notify.sending(context, server, pane, "Sending your answer", if (label.isNotEmpty()) label else "Esc")
        else Notify.sending(context, server, pane, "Open HerdrDroid to answer", "The app wasn't running, so the answer wasn't sent. Tap to open it.")
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
