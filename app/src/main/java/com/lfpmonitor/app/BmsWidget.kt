package com.lfpmonitor.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/** Виджет рабочего стола: шкала заряда, напряжение, ток, крайние ячейки и температура. */
class BmsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
    }

    companion object {
        private const val STALE_MS = 10 * 60 * 1000L

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, BmsWidget::class.java))
                if (ids.isEmpty()) return
                mgr.updateAppWidget(ids, buildViews(ctx))
            } catch (e: Exception) {
            }
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val d = BmsState.current(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setImageViewBitmap(R.id.w_gauge, GaugeRenderer.bitmap(360, d))

            if (d != null) {
                v.setTextViewText(R.id.w_high, "▲ ${Fmt.f3(d.cellMax)} V")
                v.setTextViewText(R.id.w_low, "▼ ${Fmt.f3(d.cellMin)} V")
                v.setTextViewText(R.id.w_temp, "🌡 ${Fmt.f1(d.tempMax)} °C")
                val old = System.currentTimeMillis() - d.time > STALE_MS
                v.setTextViewText(R.id.w_time, (if (old) "⚠ " else "") + "обн. " + Fmt.time(d.time))
                v.setTextColor(R.id.w_time, if (old) Color.parseColor("#FFB020") else Color.parseColor("#8A8A8A"))
            } else {
                v.setTextViewText(R.id.w_high, "▲ —")
                v.setTextViewText(R.id.w_low, "▼ —")
                v.setTextViewText(R.id.w_temp, "🌡 —")
                v.setTextViewText(R.id.w_time, "нет данных")
            }

            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}

/** Запуск связи с BMS после перезагрузки телефона, если она была включена. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autoRun(context) || Prefs.mac(context) == null) return
        try {
            BmsService.send(context, BmsService.ACTION_START)
        } catch (e: Exception) {
        }
    }
}
