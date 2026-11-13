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
    /** True for the Esc button: [keys] is a key name sent with send-keys, not typed text. */
    val isKey: Boolean = false,
    /** True for a typed reply from the notification: [keys] is the message. */
    val isReply: Boolean = false,
)

/** Connects the notification buttons (a broadcast receiver) to the running app. */
object AppBus {
    private val _actions = MutableSharedFlow<NotifyAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<NotifyAction> = _actions
    fun emit(a: NotifyAction) { _actions.tryEmit(a) }
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
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId).putExtra("reply", true)
            .putExtra("question", question)
            .putExtra("cLabels", choices.map { it.label.take(40) }.toTypedArray())
            .putExtra("cKeys", choices.map { it.keys }.toTypedArray())
            .putExtra("cEnter", choices.map { it.pressEnter }.toBooleanArray())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id(serverId, paneId) * 4 + 2, i, flags)
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

    private fun openIntent(ctx: Context, serverId: String, paneId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, id(serverId, paneId), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** An agent is waiting on a question; [choices] become up to two buttons, plus Esc to decline. */
    fun needsYou(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, question: String, choices: List<Choice>) {
        if (!allowed(ctx)) return
        val b = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent needs you · $serverLabel")
            .setContentText(question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setContentIntent(openIntent(ctx, serverId, paneId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        choices.take(2).forEachIndexed { n, c ->
            val i = Intent(ctx, ActionReceiver::class.java)
                .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
                .putExtra("keys", c.keys).putExtra("enter", c.pressEnter).putExtra("question", question)
            val pi = PendingIntent.getBroadcast(ctx, id(serverId, paneId) * 4 + n, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, c.label.take(24), pi)
        }
        // Esc declines or dismisses the question, the same as the Esc key in the app.
        val esc = Intent(ctx, ActionReceiver::class.java)
            .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_PANE, paneId)
            .putExtra("keys", "esc").putExtra("isKey", true).putExtra("question", question)
        b.addAction(0, "Esc", PendingIntent.getBroadcast(ctx, id(serverId, paneId) * 4 + 3, esc, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // Reply offers every choice as a quick reply (Android shows the first three buttons only).
        b.addAction(replyAction(ctx, serverId, paneId, agent, choices.map { it.label }, choices, question))
        runCatching { NotificationManagerCompat.from(ctx).notify(id(serverId, paneId), b.build()) }
    }

    /** An agent finished its work. */
    fun done(ctx: Context, serverId: String, serverLabel: String, paneId: String, agent: String, preview: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, ATTENTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$agent finished · $serverLabel")
            .setContentText(preview.ifBlank { "Ready for your next message." })
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
        AppBus.emit(
            NotifyAction(
                server, pane, intent.getStringExtra("keys").orEmpty(), intent.getBooleanExtra("enter", false),
                intent.getStringExtra("question").orEmpty(), intent.getBooleanExtra("isKey", false),
            ),
        )
        Notify.clear(context, server, pane)
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
