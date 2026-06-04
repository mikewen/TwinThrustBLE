package com.twinthrustble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

/**
 * AC6328 / AC6329C BLE motor controller.
 *
 * Each BLE module drives TWO motors using a 5-byte unified packet:
 *   [CMD, m1Lo, m1Hi, m2Lo, m2Hi]
 *
 * Duty units are timer counts (little-endian 16-bit), NOT microseconds:
 *
 *   ESC mode: duty 500–1000
 *     500  = 1000µs (stop / arm)
 *     750  = 1500µs (mid)
 *     1000 = 2000µs (full throttle)
 *
 *   BLDC mode: duty 0–10000
 *     0     = 0%   (stop)
 *     10000 = 100% (full)
 */
class AC6328BleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "AC6328"

        val SERVICE_UUID   = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        val CHAR_AE03_UUID = UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb")
        val CHAR_AE02_UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CHAR_AE10_UUID = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb")

        // Command bytes
        const val CMD_ESC_PWM:   Byte = 0x01
        const val CMD_BLDC_DUTY: Byte = 0x02
        const val CMD_STOP:      Byte = 0xFF.toByte()

        // ESC duty range
        const val ESC_MIN     = 500    // 1000µs — stop / arm
        const val ESC_DEFAULT = 500
        const val ESC_MAX     = 1000   // 2000µs — full throttle

        // BLDC duty range
        const val BLDC_MIN     = 0
        const val BLDC_DEFAULT = 0
        const val BLDC_MAX     = 10000
    }

    private var charAe03: BluetoothGattCharacteristic? = null
    private var charAe02: BluetoothGattCharacteristic? = null
    private var charAe10: BluetoothGattCharacteristic? = null

    var onFeedback: ((FeedbackData) -> Unit)? = null
    var onAe02Raw:  ((ByteArray)    -> Unit)? = null
    var onError:    ((String)       -> Unit)? = null

    override fun getGattCallback(): BleManagerGattCallback = AC6328GattCallback()

    private inner class AC6328GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(SERVICE_UUID) ?: return false
            charAe03 = svc.getCharacteristic(CHAR_AE03_UUID)
            charAe02 = svc.getCharacteristic(CHAR_AE02_UUID)
            charAe10 = svc.getCharacteristic(CHAR_AE10_UUID)
            return charAe03 != null
        }

        override fun initialize() {
            charAe02?.let { c ->
                setNotificationCallback(c).with { _, data ->
                    data.value?.let { bytes ->
                        onAe02Raw?.invoke(bytes)
                        parseAe02Echo(bytes)
                    }
                }
                enableNotifications(c).enqueue()
            }
        }

        override fun onServicesInvalidated() {
            charAe03 = null; charAe02 = null; charAe10 = null
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Send ESC PWM duty to both motors using 5-byte format:
     * [0x01, m1_lo, m1_hi, m2_lo, m2_hi]
     */
    fun sendEscPwm(m1Duty: Int, m2Duty: Int) {
        val m1 = m1Duty.coerceIn(ESC_MIN, ESC_MAX)
        val m2 = m2Duty.coerceIn(ESC_MIN, ESC_MAX)
        Log.d(TAG, "ESC 5-byte: m1=$m1, m2=$m2")
        writeCommand(build5BytePacket(CMD_ESC_PWM, m1, m2))
    }

    /**
     * Send BLDC duty to both motors using 5-byte format:
     * [0x02, m1_lo, m1_hi, m2_lo, m2_hi]
     */
    fun sendBldc(m1Duty: Int, m2Duty: Int) {
        val m1 = m1Duty.coerceIn(BLDC_MIN, BLDC_MAX)
        val m2 = m2Duty.coerceIn(BLDC_MIN, BLDC_MAX)
        Log.d(TAG, "BLDC 5-byte: m1=$m1, m2=$m2")
        writeCommand(build5BytePacket(CMD_BLDC_DUTY, m1, m2))
    }

    /**
     * Stop both motors — sends 5-byte packet with CMD_STOP [0xFF, 0, 0, 0, 0].
     */
    fun stopMotors() {
        Log.d(TAG, "STOP sent (5-byte)")
        writeCommand(build5BytePacket(CMD_STOP, 0, 0))
    }

    /** Switch firmware to ESC mode (write 0x01 to ae10) */
    fun setEscMode() {
        charAe10?.let {
            writeCharacteristic(it, byteArrayOf(0x01),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
        }
    }

    /** Switch firmware to BLDC mode (write 0x02 to ae10) */
    fun setBldcMode() {
        charAe10?.let {
            writeCharacteristic(it, byteArrayOf(0x02),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
        }
    }

    /** Read ae10 status string: "M<mode>A<vbat_mv>T<uptime_min>" */
    fun readStatus() {
        charAe10?.let {
            readCharacteristic(it).with { _, data ->
                val raw = data.value?.toString(Charsets.UTF_8) ?: return@with
                onFeedback?.invoke(parseAe10Status(raw))
            }.enqueue()
        }
    }

    /** Arm ESC: send stop duty on both motors to complete the ESC arm sequence */
    fun armEsc() = sendEscPwm(ESC_MIN, ESC_MIN)

    // ── Battery helper ────────────────────────────────────────────────────────

    fun battMvToPercent(mv: Int): Int = when {
        mv >= 4200 -> 100
        mv >= 3700 -> 60 + (mv - 3700) * 40 / 500
        mv >= 3500 -> 20 + (mv - 3500) * 40 / 200
        mv >= 3300 -> (mv - 3300) * 20 / 200
        else       -> 0
    }.coerceIn(0, 100)

    // ── Packet builder ────────────────────────────────────────────────────────

    /**
     * Build a 5-byte command packet: [cmd, m1Lo, m1Hi, m2Lo, m2Hi]
     */
    private fun build5BytePacket(cmd: Byte, m1: Int, m2: Int): ByteArray = byteArrayOf(
        cmd,
        (m1 and 0xFF).toByte(),
        ((m1 shr 8) and 0xFF).toByte(),
        (m2 and 0xFF).toByte(),
        ((m2 shr 8) and 0xFF).toByte()
    )

    private fun writeCommand(bytes: ByteArray) {
        charAe03?.let {
            writeCharacteristic(it, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
        }
    }

    // ── ae02 echo parser ──────────────────────────────────────────────────────

    private fun parseAe02Echo(bytes: ByteArray) {
        if (bytes.size < 5) return
        val cmd = bytes[0]
        val m1  = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        val m2  = (bytes[3].toInt() and 0xFF) or ((bytes[4].toInt() and 0xFF) shl 8)
        
        onFeedback?.invoke(FeedbackData(
            source   = "ae02-echo",
            echoCmd  = cmd.toInt() and 0xFF,
            echoPort = m1,
            echoStbd = m2,
            rawAe02  = bytes
        ))
    }

    // ── ae10 status parser ────────────────────────────────────────────────────

    private fun parseAe10Status(raw: String): FeedbackData {
        val mode      = Regex("M(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val battMv    = Regex("A(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val uptimeMin = Regex("T(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return FeedbackData(source = "ae10-read", batteryMv = battMv,
            uptimeMin = uptimeMin, rawAe10 = raw)
    }

    // ── Connect helper ────────────────────────────────────────────────────────

    fun connectToDevice(device: BluetoothDevice) {
        connect(device).useAutoConnect(false).retry(3, 200).enqueue()
    }

    // ── Feedback data ─────────────────────────────────────────────────────────

    data class FeedbackData(
        val source:    String    = "",
        val batteryMv: Int       = 0,
        val uptimeMin: Int       = -1,
        val rawAe10:   String    = "",
        val echoCmd:   Int       = -1,
        val echoPort:  Int       = -1,
        val echoStbd:  Int       = -1,
        val rawAe02:   ByteArray = ByteArray(0)
    )

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Convert ESC duty unit to microseconds for display (50Hz timer: 1 unit = 2µs) */
    fun dutyToUs(duty: Int): Int = duty * 2
}
