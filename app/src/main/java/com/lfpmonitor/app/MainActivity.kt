package com.lfpmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDevice: TextView
    private lateinit var gauge: GaugeView
    private lateinit var tvHigh: TextView
    private lateinit var tvLow: TextView
    private lateinit var tvDiff: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvCap: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvMos: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvCells: TextView
    private lateinit var btnToggle: Button

    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }

    private val blue = Color.parseColor("#2AA8F2")
    private val red = Color.parseColor("#FF3B30")
    private val green = GaugeRenderer.GREEN
    private val gray = Color.parseColor("#BDBDBD")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), 1)
        } else if (Prefs.mac(this) != null && Prefs.autoRun(this) && !BmsService.running) {
            startService()
        }
    }

    override fun onResume() {
        super.onResume()
        BmsState.addListener(listener)
        render()
    }

    override fun onPause() {
        BmsState.removeListener(listener)
        super.onPause()
    }

    // ---------- Разрешения ----------

    private fun neededPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean = neededPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasPermissions()) {
            toast("Без разрешения на Bluetooth приложение не сможет читать BMS")
            return
        }
        if (Prefs.mac(this) == null) scan()
        else if (Prefs.autoRun(this) && !BmsService.running) startService()
    }

    // ---------- Действия ----------

    private fun startService() {
        Prefs.setAutoRun(this, true)
        try {
            BmsService.send(this, BmsService.ACTION_START)
        } catch (e: Exception) {
            toast("Не удалось запустить: ${e.message}")
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun stopService() {
        Prefs.setAutoRun(this, false)
        stopService(Intent(this, BmsService::class.java))
        handler.postDelayed({ render() }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), 1)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("На телефоне нет Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) {}
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            toast("Bluetooth ещё включается, попробуйте через пару секунд")
            return
        }
        // Пока мы подключены, плата не видна в поиске, поэтому связь на время отключаем.
        val wasRunning = BmsService.running
        if (wasRunning) stopService(Intent(this, BmsService::class.java))

        val macs = ArrayList<String>()
        val names = ArrayList<String>()
        val items = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        var dialog: AlertDialog? = null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address
                if (macs.contains(mac)) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "(без имени)"
                macs.add(mac)
                names.add(name)
                runOnUiThread { items.add("$name\n$mac   сигнал ${result.rssi}") }
            }
        }

        val stopScan = Runnable {
            try { scanner.stopScan(cb) } catch (e: Exception) {}
            dialog?.setTitle(if (macs.isEmpty()) "Ничего не найдено" else "Выберите вашу BMS")
        }

        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Поиск BMS… (закройте фирменное приложение)")
            .setAdapter(items) { _, which ->
                handler.removeCallbacks(stopScan)
                stopScan.run()
                Prefs.setDevice(this, macs[which], names[which])
                BmsState.data = null
                startService()
            }
            .setNegativeButton("Отмена") { _, _ ->
                handler.removeCallbacks(stopScan)
                stopScan.run()
                if (wasRunning) startService()
            }
            .setCancelable(false)
            .show()

        try {
            scanner.startScan(cb)
            handler.postDelayed(stopScan, 15_000)
        } catch (e: Exception) {
            toast("Не удалось начать поиск: ${e.message}")
        }
    }

    private fun askBatteryWhitelist() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Уже работает без ограничений")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun copyDebug() {
        val d = BmsState.current(this)
        val sb = StringBuilder()
        sb.append("Приложение: ").append(BuildConfigInfo.version(this)).append('\n')
        sb.append("Устройство: ").append(BmsState.deviceInfo.ifEmpty { "—" }).append('\n')
        sb.append("Статус: ").append(BmsState.status).append('\n')
        if (d != null) {
            sb.append("SOC ${d.soc}%, U ${Fmt.f3(d.voltage)} В (сумма ячеек ${Fmt.f3(d.cellSum)}")
            if (d.voltageFromCells) sb.append(", взято из ячеек")
            sb.append("), I ${Fmt.f3(d.current)} А\n")
            sb.append("T1 ${Fmt.f1(d.temp1)}, T2 ${Fmt.f1(d.temp2)}, MOS ${Fmt.f1(d.mosTemp)} °C\n")
            sb.append("Остаток ${Fmt.f1(d.remainingAh)} из ${Fmt.f1(d.capacityAh)} Ah\n")
            sb.append("Ячейки: ").append(d.cells.joinToString(" ") { Fmt.f3(it) }).append('\n')
        }
        sb.append("Кадр:\n").append(BmsState.rawHex.ifEmpty { "—" })
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("BMS debug", sb.toString()))
        toast("Скопировано. Вставьте в чат с Claude")
    }

    // ---------- Отрисовка ----------

    private fun render() {
        val d = BmsState.current(this)
        gauge.data = d
        val name = Prefs.name(this)
        val mac = Prefs.mac(this)
        tvDevice.text = if (mac == null) "BMS не выбрана" else "${name.ifEmpty { "BMS" }}  ($mac)"
        tvStatus.text = if (BmsService.running) BmsState.status else "Остановлено"
        btnToggle.text = if (BmsService.running) "Остановить связь" else "Запустить связь"

        if (d == null) {
            for (t in listOf(tvHigh, tvLow, tvDiff, tvTemp, tvRemain, tvCap, tvPower, tvMos)) t.text = "—"
            tvUpdated.text = "Данных пока нет"
            tvCells.text = ""
            return
        }
        tvHigh.text = Fmt.f3(d.cellMax)
        tvLow.text = Fmt.f3(d.cellMin)
        tvDiff.text = Fmt.f3(d.delta)
        tvTemp.text = Fmt.f1(d.tempMax)
        tvRemain.text = Fmt.f1(d.remainingAh)
        tvCap.text = Fmt.f1(d.capacityAh)
        tvPower.text = Fmt.f1(d.power)
        tvMos.text = Fmt.f1(d.mosTemp)
        tvUpdated.text = "Обновлено: ${Fmt.dateTime(d.time)}"
        val sb = StringBuilder()
        d.cells.forEachIndexed { i, v ->
            if (i > 0) sb.append(if (i % 4 == 0) "\n" else "    ")
            sb.append(String.format("%02d: %s", i + 1, Fmt.f3(v)))
        }
        tvCells.text = sb.toString()
    }

    // ---------- Разметка ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A2A2A"))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(4), dp(14), dp(4), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
    }

    private fun stat(parent: LinearLayout, label: String, color: Int): TextView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = "—"
            setTextColor(color)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val lab = TextView(this).apply {
            text = label
            setTextColor(gray)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        col.addView(value)
        col.addView(lab)
        parent.addView(col)
        return value
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), dp(6), dp(12), 0) }
        parent.addView(b)
        return b
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(24))
        }

        tvDevice = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        tvStatus = TextView(this).apply {
            setTextColor(gray)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(tvDevice)
        root.addView(tvStatus)

        gauge = GaugeView(this)
        gauge.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(24), dp(8), dp(24), 0) }
        root.addView(gauge)

        val c1 = card()
        tvHigh = stat(c1, "Макс. ячейка (В)", blue)
        tvLow = stat(c1, "Мин. ячейка (В)", red)
        tvDiff = stat(c1, "Разница (В)", green)
        tvTemp = stat(c1, "Темп. (°C)", green)
        root.addView(c1)

        val c2 = card()
        tvRemain = stat(c2, "Остаток (Ah)", green)
        tvCap = stat(c2, "Ёмкость (Ah)", green)
        tvPower = stat(c2, "Мощность (Вт)", green)
        tvMos = stat(c2, "MOS (°C)", green)
        root.addView(c2)

        tvCells = TextView(this).apply {
            setTextColor(gray)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        root.addView(tvCells)

        tvUpdated = TextView(this).apply {
            setTextColor(gray)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        root.addView(tvUpdated)

        btnToggle = button(root, "Запустить связь") {
            if (BmsService.running) stopService()
            else if (Prefs.mac(this) == null) scan()
            else startService()
        }
        button(root, "Отпустить BMS на 10 минут (для фирменного приложения)") {
            if (BmsService.running) BmsService.send(this, BmsService.ACTION_PAUSE)
            else toast("Связь и так выключена")
        }
        button(root, "Выбрать BMS") { scan() }
        button(root, "Разрешить работу в фоне без ограничений") { askBatteryWhitelist() }
        button(root, "Скопировать данные для отладки") { copyDebug() }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

object BuildConfigInfo {
    fun version(a: Activity): String = try {
        a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
