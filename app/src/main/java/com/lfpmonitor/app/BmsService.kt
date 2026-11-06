package com.lfpmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Служба держит Bluetooth-подключение к BMS, принимает данные
 * и обновляет виджет и уведомление.
 */
class BmsService : Service() {

    companion object {
        const val ACTION_START = "com.lfpmonitor.app.START"
        const val ACTION_PAUSE = "com.lfpmonitor.app.PAUSE"
        const val ACTION_STOP = "com.lfpmonitor.app.STOP"
        private const val CHANNEL = "bms"
        private const val NOTIF_ID = 1
        const val PAUSE_MS = 10 * 60 * 1000L
        private const val UI_THROTTLE_MS = 5_000L

        @Volatile var running = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, BmsService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val assembler = FrameAssembler()
    private var connected = false
    private var connectedAt = 0L
    private var lastFrameAt = 0L
    private var pausedUntil = 0L
    private var lastUiAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val ch = NotificationChannel(CHANNEL, "Связь с BMS", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setAutoRun(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> pause()
            else -> {
                pausedUntil = 0
                handler.removeCallbacks(reconnect)
                if (gatt == null) connect()
            }
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 10_000)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        BmsState.status = "Остановлено"
        BmsState.notifyChanged()
        BmsWidget.updateAll(this)
        super.onDestroy()
    }

    // ---------- Уведомление ----------

    private fun goForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        BmsState.status = "Не удалось запустить службу: ${e.message}"
        BmsState.notifyChanged()
        false
    }

    private fun buildNotification(): Notification {
        val d = BmsState.data
        val title = if (d != null) {
            "${d.soc}% · ${Fmt.f2(d.voltage)} В · ${Fmt.signedA(d.current)} А"
        } else {
            getString(R.string.app_name)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val pause = PendingIntent.getForegroundService(
            this, 1, Intent(this, BmsService::class.java).setAction(ACTION_PAUSE), flags
        )
        val resume = PendingIntent.getForegroundService(
            this, 3, Intent(this, BmsService::class.java).setAction(ACTION_START), flags
        )
        val stop = PendingIntent.getForegroundService(
            this, 2, Intent(this, BmsService::class.java).setAction(ACTION_STOP), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(BmsState.status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (System.currentTimeMillis() < pausedUntil) {
            b.addAction(Notification.Action.Builder(icon, "Подключиться сейчас", resume).build())
        } else {
            b.addAction(Notification.Action.Builder(icon, "Отпустить на 10 мин", pause).build())
        }
        b.addAction(Notification.Action.Builder(icon, "Стоп", stop).build())
        return b.build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
        }
    }

    private fun setStatus(s: String) {
        BmsState.status = s
        BmsState.notifyChanged()
        updateNotification()
    }

    // ---------- Подключение ----------

    private val reconnect = Runnable { connect() }

    private val connectTimeout = Runnable {
        if (!connected) {
            closeGatt()
            setStatus("BMS не отвечает (далеко или занята фирменным приложением). Повтор через 15 с")
            scheduleReconnect(15_000)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (connected) {
                if (now - lastFrameAt > 45_000 && now - connectedAt > 45_000) {
                    closeGatt()
                    setStatus("Данные не приходят, переподключаюсь…")
                    scheduleReconnect(2_000)
                } else if (now - lastFrameAt > 12_000) {
                    write(JkProtocol.command(JkProtocol.CMD_CELL_INFO))
                }
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private fun scheduleReconnect(ms: Long) {
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, ms)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun pause() {
        pausedUntil = System.currentTimeMillis() + PAUSE_MS
        closeGatt()
        scheduleReconnect(PAUSE_MS)
        setStatus("Отпущено до ${Fmt.time(pausedUntil)}: можно открыть фирменное приложение")
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (System.currentTimeMillis() < pausedUntil) return
        if (connected) return
        val mac = Prefs.mac(this)
        if (mac == null) {
            setStatus("BMS не выбрана")
            return
        }
        if (!hasConnectPermission()) {
            setStatus("Нет разрешения на Bluetooth. Откройте приложение")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth выключен")
            scheduleReconnect(15_000)
            return
        }
        closeGatt()
        setStatus("Подключение к ${Prefs.name(this).ifEmpty { mac }}…")
        try {
            val device = adapter.getRemoteDevice(mac)
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(connectTimeout, 35_000)
        } catch (e: Exception) {
            setStatus("Ошибка подключения: ${e.message}")
            scheduleReconnect(15_000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        connected = false
        writeChar = null
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect() } catch (e: Exception) {}
            try { g.close() } catch (e: Exception) {}
        }
        assembler.reset()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post { onConnState(g, status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { onServices(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { if (g === gatt) startRequests() }
        }

        // Android 13 и новее.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val copy = value.copyOf()
            handler.post { if (g === gatt) onBytes(copy) }
        }

        // Android 12 и старше.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: return
            handler.post { if (g === gatt) onBytes(v) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) {
            try { g.close() } catch (e: Exception) {}
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected = true
            connectedAt = System.currentTimeMillis()
            handler.removeCallbacks(connectTimeout)
            setStatus("Подключено, жду данные…")
            handler.postDelayed({ if (gatt === g) g.discoverServices() }, 600)
        } else {
            val wasConnected = connected
            closeGatt()
            if (wasConnected) {
                setStatus("Связь потеряна, переподключаюсь…")
                scheduleReconnect(3_000)
            } else {
                setStatus("BMS не отвечает (код $status): далеко или занята фирменным приложением. Повтор через 15 с")
                scheduleReconnect(15_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onServices(g: BluetoothGatt) {
        if (g !== gatt) return
        val ch = g.getService(JkProtocol.SERVICE_UUID)?.getCharacteristic(JkProtocol.CHAR_UUID)
        if (ch == null) {
            closeGatt()
            setStatus("У устройства нет сервиса FFE0: похоже, это не JK BMS")
            scheduleReconnect(30_000)
            return
        }
        writeChar = ch
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(JkProtocol.CCCD_UUID)
        if (d == null) {
            startRequests()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            legacyWriteDescriptor(g, d)
        }
        // Запасной вариант, если ответ на запись дескриптора потерялся.
        handler.postDelayed({ if (gatt === g && lastFrameAt < connectedAt) startRequests() }, 2_500)
    }

    private fun startRequests() {
        write(JkProtocol.command(JkProtocol.CMD_DEVICE_INFO))
        handler.postDelayed({ write(JkProtocol.command(JkProtocol.CMD_CELL_INFO)) }, 500)
    }

    @SuppressLint("MissingPermission")
    private fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        val type = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, type)
            } else {
                legacyWrite(g, ch, bytes, type)
            }
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun legacyWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int) {
        ch.writeType = type
        ch.value = bytes
        g.writeCharacteristic(ch)
    }

    // ---------- Данные ----------

    private fun onBytes(bytes: ByteArray) {
        val frame = assembler.feed(bytes) ?: return
        lastFrameAt = System.currentTimeMillis()
        when (JkProtocol.frameType(frame)) {
            JkProtocol.TYPE_DEVICE_INFO -> {
                BmsState.deviceInfo = JkProtocol.parseDeviceInfo(frame)
                write(JkProtocol.command(JkProtocol.CMD_CELL_INFO))
            }
            JkProtocol.TYPE_CELL_INFO -> {
                val d = JkProtocol.parseCellInfo(frame) ?: return
                BmsState.data = d
                BmsState.rawHex = JkProtocol.hex(frame)
                BmsState.status = "Подключено"
                BmsState.notifyChanged()
                val now = System.currentTimeMillis()
                if (now - lastUiAt >= UI_THROTTLE_MS) {
                    lastUiAt = now
                    Prefs.saveData(this, d)
                    BmsWidget.updateAll(this)
                    updateNotification()
                }
            }
        }
    }
}
