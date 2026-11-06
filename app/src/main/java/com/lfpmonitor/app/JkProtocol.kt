package com.lfpmonitor.app

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Все показатели, которые показывают главный экран и виджет. */
data class BmsData(
    val soc: Int,
    val voltage: Double,
    val current: Double,
    val cellMax: Double,
    val cellMin: Double,
    val cellMaxIndex: Int,
    val cellMinIndex: Int,
    val temp1: Double,
    val temp2: Double,
    val mosTemp: Double,
    val remainingAh: Double,
    val capacityAh: Double,
    val cells: List<Double>,
    val voltageFromCells: Boolean,
    val time: Long
) {
    val tempMax: Double get() = maxOf(temp1, temp2)
    val delta: Double get() = cellMax - cellMin
    val power: Double get() = voltage * current
    val cellSum: Double get() = cells.sum()
}

/**
 * Протокол JK BMS по Bluetooth, вариант JK02_32S
 * (платы с прошивкой V11 и новее, в том числе JK-B1A8S20P V19H / V19.25).
 */
object JkProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_CELL_INFO = 0x96
    const val CMD_DEVICE_INFO = 0x97
    const val FRAME_SIZE = 300

    const val TYPE_SETTINGS = 0x01
    const val TYPE_CELL_INFO = 0x02
    const val TYPE_DEVICE_INFO = 0x03

    /** Команда из 20 байт: AA 55 90 EB, код, длина, 4 байта значения, нули, контрольная сумма. */
    fun command(cmd: Int): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte()
        f[1] = 0x55.toByte()
        f[2] = 0x90.toByte()
        f[3] = 0xEB.toByte()
        f[4] = cmd.toByte()
        f[5] = 0.toByte()
        var sum = 0
        for (i in 0 until 19) sum += f[i].toInt() and 0xFF
        f[19] = (sum and 0xFF).toByte()
        return f
    }

    fun isHeader(d: ByteArray): Boolean =
        d.size >= 4 &&
            (d[0].toInt() and 0xFF) == 0x55 &&
            (d[1].toInt() and 0xFF) == 0xAA &&
            (d[2].toInt() and 0xFF) == 0xEB &&
            (d[3].toInt() and 0xFF) == 0x90

    fun crcOk(f: ByteArray): Boolean {
        if (f.size < FRAME_SIZE) return false
        var sum = 0
        for (i in 0 until FRAME_SIZE - 1) sum += f[i].toInt() and 0xFF
        return (sum and 0xFF) == (f[FRAME_SIZE - 1].toInt() and 0xFF)
    }

    fun frameType(f: ByteArray): Int = f[4].toInt() and 0xFF

    private fun u8(f: ByteArray, i: Int): Int = f[i].toInt() and 0xFF
    private fun u16(f: ByteArray, i: Int): Int = u8(f, i) or (u8(f, i + 1) shl 8)
    private fun s16(f: ByteArray, i: Int): Int = u16(f, i).toShort().toInt()
    private fun u32(f: ByteArray, i: Int): Long = u16(f, i).toLong() or (u16(f, i + 2).toLong() shl 16)
    private fun s32(f: ByteArray, i: Int): Int = u32(f, i).toInt()

    /**
     * Разбор кадра с данными ячеек (тип 0x02).
     * Ячейки и их сводка сдвинуты на 16 байт, остальные поля на 32 байта
     * относительно старого протокола JK02_24S.
     */
    fun parseCellInfo(f: ByteArray, now: Long = System.currentTimeMillis()): BmsData? {
        if (f.size < FRAME_SIZE || frameType(f) != TYPE_CELL_INFO) return null

        val cells = ArrayList<Double>()
        var maxV = -1.0
        var minV = 100.0
        var maxI = 0
        var minI = 0
        for (i in 0 until 32) {
            val v = u16(f, 6 + i * 2) * 0.001
            if (v < 0.5 || v > 5.0) continue
            cells.add(v)
            if (v > maxV) { maxV = v; maxI = i + 1 }
            if (v < minV) { minV = v; minI = i + 1 }
        }
        if (cells.isEmpty()) return null

        var voltage = u32(f, 150) * 0.001
        val current = s32(f, 158) * 0.001
        val t1 = s16(f, 162) * 0.1
        val t2 = s16(f, 164) * 0.1
        val mos = s16(f, 144) * 0.1
        val soc = u8(f, 173).coerceIn(0, 100)
        val remaining = s32(f, 174) * 0.001
        val capacity = s32(f, 178) * 0.001

        // Проверка: напряжение батареи должно быть близко к сумме ячеек.
        val sum = cells.sum()
        var fromCells = false
        if (voltage < sum * 0.85 || voltage > sum * 1.15) {
            voltage = sum
            fromCells = true
        }

        return BmsData(
            soc = soc,
            voltage = voltage,
            current = current,
            cellMax = maxV,
            cellMin = minV,
            cellMaxIndex = maxI,
            cellMinIndex = minI,
            temp1 = t1,
            temp2 = t2,
            mosTemp = mos,
            remainingAh = remaining,
            capacityAh = capacity,
            cells = cells,
            voltageFromCells = fromCells,
            time = now
        )
    }

    /** Модель, версии и имя из кадра с информацией об устройстве (тип 0x03). */
    fun parseDeviceInfo(f: ByteArray): String {
        if (f.size < FRAME_SIZE || frameType(f) != TYPE_DEVICE_INFO) return ""
        val model = ascii(f, 6, 16)
        val hw = ascii(f, 22, 8)
        val sw = ascii(f, 30, 8)
        val name = ascii(f, 46, 16)
        return "$model, железо $hw, прошивка $sw, имя $name"
    }

    private fun ascii(f: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in start until start + len) {
            val c = u8(f, i)
            if (c == 0) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    fun hex(f: ByteArray): String {
        val sb = StringBuilder(f.size * 3)
        for ((n, b) in f.withIndex()) {
            if (n > 0) sb.append(if (n % 20 == 0) '\n' else ' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

/** Собирает кадр из кусочков по 20 байт, которые приходят по Bluetooth. */
class FrameAssembler {
    private val buf = ByteArrayOutputStream()
    var crcErrors = 0
        private set

    fun reset() = buf.reset()

    fun feed(data: ByteArray): ByteArray? {
        if (JkProtocol.isHeader(data)) buf.reset()
        else if (buf.size() == 0) return null
        buf.write(data, 0, data.size)
        if (buf.size() > 1024) { buf.reset(); return null }
        if (buf.size() < JkProtocol.FRAME_SIZE) return null
        val frame = buf.toByteArray()
        buf.reset()
        if (!JkProtocol.crcOk(frame)) { crcErrors++; return null }
        return frame
    }
}
