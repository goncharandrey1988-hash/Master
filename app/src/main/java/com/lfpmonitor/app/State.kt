package com.lfpmonitor.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Текущие данные внутри приложения, общие для службы, экрана и виджета. */
object BmsState {
    @Volatile var data: BmsData? = null
    @Volatile var status: String = "Остановлено"
    @Volatile var rawHex: String = ""
    @Volatile var deviceInfo: String = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { for (l in listeners) l() } }

    fun current(ctx: Context): BmsData? = data ?: Prefs.loadData(ctx)
}

/** Сохранённые настройки и последние данные (чтобы виджет показывал их и после перезагрузки). */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lfp", Context.MODE_PRIVATE)

    fun mac(c: Context): String? = sp(c).getString("mac", null)
    fun name(c: Context): String = sp(c).getString("name", "") ?: ""
    fun setDevice(c: Context, mac: String, name: String) =
        sp(c).edit().putString("mac", mac).putString("name", name).apply()

    fun autoRun(c: Context): Boolean = sp(c).getBoolean("autoRun", false)
    fun setAutoRun(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoRun", v).apply()

    fun saveData(c: Context, d: BmsData) {
        val s = listOf(
            d.soc, d.voltage, d.current, d.cellMax, d.cellMin, d.cellMaxIndex, d.cellMinIndex,
            d.temp1, d.temp2, d.mosTemp, d.remainingAh, d.capacityAh,
            if (d.voltageFromCells) 1 else 0, d.time,
            d.cells.joinToString(",")
        ).joinToString(";")
        sp(c).edit().putString("data", s).apply()
    }

    fun loadData(c: Context): BmsData? {
        val s = sp(c).getString("data", null) ?: return null
        return try {
            val p = s.split(";")
            BmsData(
                soc = p[0].toInt(),
                voltage = p[1].toDouble(),
                current = p[2].toDouble(),
                cellMax = p[3].toDouble(),
                cellMin = p[4].toDouble(),
                cellMaxIndex = p[5].toInt(),
                cellMinIndex = p[6].toInt(),
                temp1 = p[7].toDouble(),
                temp2 = p[8].toDouble(),
                mosTemp = p[9].toDouble(),
                remainingAh = p[10].toDouble(),
                capacityAh = p[11].toDouble(),
                voltageFromCells = p[12] == "1",
                time = p[13].toLong(),
                cells = if (p.size > 14 && p[14].isNotEmpty()) p[14].split(",").map { it.toDouble() } else emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Fmt {
    fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
    fun signedA(v: Double) = (if (v > 0.005) "+" else "") + f2(v)
    fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(t))
}
