package com.billmongan.herdrdroid.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.billmongan.herdrdroid.MainActivity
import com.billmongan.herdrdroid.R
import com.billmongan.herdrdroid.store.AppPrefs
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * The home-screen widget. It never connects by itself: the app writes what it last saw (the
 * server, how many agents need you or work, and the first few agents) and asks it to redraw.
 */
class AgentsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    companion object {
        /** Redraws every placed widget from the saved state. */
        fun update(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, AgentsWidget::class.java))
            if (ids.isNotEmpty()) draw(context, m, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val o = runCatching { JSONObject(AppPrefs(context).widgetState) }.getOrDefault(JSONObject())
            val v = RemoteViews(context.packageName, R.layout.widget_agents)
            if (o.has("server")) {
                v.setTextViewText(R.id.widget_title, o.optString("server"))
                val needs = o.optInt("needs")
                val working = o.optInt("working")
                v.setTextViewText(R.id.widget_summary, when {
                    needs > 0 -> "$needs need you · $working working"
                    working > 0 -> "$working working · none need you"
                    else -> "All agents idle"
                })
                v.setTextViewText(R.id.widget_list, o.optString("lines"))
                val at = o.optLong("at")
                v.setTextViewText(R.id.widget_when, if (at > 0) "Seen " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) else "")
            }
            val open = PendingIntent.getActivity(
                context, 11, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            ids.forEach { manager.updateAppWidget(it, v) }
        }
    }
}
