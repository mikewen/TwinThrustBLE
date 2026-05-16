package com.twinthrustble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * LOOKBON BLE Remote
 * Maps remote buttons and joystick to motor control actions with repeat support.
 */
class LookbonRemote(context: Context) : BleManager(context) {

    enum class Command {
        SPEED_UP,
        SPEED_DOWN,
        SPEED_UP_FAST,
        SPEED_DOWN_FAST,
        STOP,
        TOGGLE_DIRECTION,
        START_REPEAT_UP,
        START_REPEAT_DOWN,
        START_REPEAT_UP_FAST,
        START_REPEAT_DOWN_FAST,
        STOP_REPEAT,
        STEER_LEFT,
        STEER_RIGHT,
        START_STEER_LEFT,
        START_STEER_RIGHT,
        STOP_STEER
    }

    companion object {
        private const val TAG = "LookbonRemote"

        // ae30 service / ae02 characteristic
        val SERVICE_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID    = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

        val REMOTE_NAME_FILTERS = listOf("LOOKBON", "lookbon")
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onCommand:      ((Command) -> Unit)? = null
    var onConnected:    (() -> Unit)?        = null
    var onDisconnected: (() -> Unit)?        = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var rHeld = false
    private var lHeld = false
    private var lastJoystickDir = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var notifyChar: BluetoothGattCharacteristic? = null

    override fun getGattCallback(): BleManagerGattCallback = GattCb()

    private inner class GattCb : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(SERVICE_UUID) ?: return false
            notifyChar = svc.getCharacteristic(CHAR_UUID)
            return notifyChar != null
        }

        override fun initialize() {
            notifyChar?.let { c ->
                setNotificationCallback(c).with { _, data ->
                    val raw = data.value ?: return@with
                    val hex = raw.joinToString("") { "%02X".format(it) }
                    handleHex(hex)
                }
                enableNotifications(c).enqueue()
            }
        }

        override fun onServicesInvalidated() {
            notifyChar = null
        }
    }

    private fun handleHex(hex: String) {
        when {
            // 2-char = single byte key event or joystick
            hex.length == 2 -> {
                val eventByte = hex[0]   // A/B/C or D
                val btnChar   = hex[1]   // 1-7 or 0-8
                when (eventByte) {
                    'D' -> handleJoystick(btnChar.digitToIntOrNull() ?: return)
                    'A', 'B', 'C' -> handleButton(eventByte, btnChar.digitToIntOrNull() ?: return)
                }
            }
            // 4-char = 2-byte joystick
            hex.length == 4 -> {
                try {
                    val b1 = hex.substring(0, 2).toInt(16)
                    val b2 = hex.substring(2, 4).toInt(16)
                    if (b1 == 'D'.code) {
                        handleJoystick(b2 - '0'.code)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing 4-char hex: $hex", e)
                }
            }
        }
    }

    private fun handleButton(event: Char, btn: Int) {
        when {
            // R modifier (Trigger NEAR)
            event == 'B' && btn == 6 -> { rHeld = true; return }
            event == 'C' && btn == 6 -> { rHeld = false; return }

            // L modifier (Trigger FAR)
            event == 'B' && btn == 7 -> { lHeld = true; return }
            event == 'C' && btn == 7 -> { lHeld = false; emit(Command.STOP_REPEAT); return }

            // @ button (Center)
            event == 'A' && btn == 1 -> emit(Command.STOP)
            event == 'B' && btn == 1 -> emit(Command.STOP)

            // Button A (Right) - Steering
            event == 'B' && btn == 2 -> emit(Command.START_STEER_RIGHT)
            event == 'C' && btn == 2 -> emit(Command.STOP_STEER)
            
            // Button B (Left) - Steering
            event == 'B' && btn == 3 -> emit(Command.START_STEER_LEFT)
            event == 'C' && btn == 3 -> emit(Command.STOP_STEER)
            
            // Button C (Up) - Speed Up / Repeat
            event == 'B' && btn == 4 -> {
                if (rHeld) emit(Command.START_REPEAT_UP_FAST) else emit(Command.START_REPEAT_UP)
            }
            event == 'C' && btn == 4 -> {
                emit(Command.STOP_REPEAT)
            }

            // Button D (Down) - Speed Down / Repeat
            event == 'B' && btn == 5 -> {
                if (rHeld) emit(Command.START_REPEAT_DOWN_FAST) else emit(Command.START_REPEAT_DOWN)
            }
            event == 'C' && btn == 5 -> {
                emit(Command.STOP_REPEAT)
            }

            // Fallback for single clicks (A event)
            event == 'A' && btn == 2 -> emit(Command.STEER_RIGHT)
            event == 'A' && btn == 3 -> emit(Command.STEER_LEFT)
            event == 'A' && btn == 4 -> if (rHeld) emit(Command.SPEED_UP_FAST) else emit(Command.SPEED_UP)
            event == 'A' && btn == 5 -> if (rHeld) emit(Command.SPEED_DOWN_FAST) else emit(Command.SPEED_DOWN)
        }
    }

    private fun handleJoystick(dir: Int) {
        if (dir == lastJoystickDir) return
        
        // Stop any current repeat or steering when joystick moves or centers
        if (lastJoystickDir != 0) {
            emit(Command.STOP_REPEAT)
            emit(Command.STOP_STEER)
        }

        when (dir) {
            1 -> emit(Command.START_REPEAT_UP)
            2 -> emit(Command.START_REPEAT_DOWN)
            3 -> emit(Command.START_STEER_LEFT)
            4 -> emit(Command.START_STEER_RIGHT)
        }
        
        lastJoystickDir = dir
    }

    private fun emit(cmd: Command) {
        mainHandler.post { onCommand?.invoke(cmd) }
    }

    fun connectToDevice(device: BluetoothDevice, autoReconnect: Boolean = false) {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(d: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(d: BluetoothDevice) = Unit
            override fun onDeviceReady(d: BluetoothDevice) = Unit
            override fun onDeviceConnected(d: BluetoothDevice) {
                Log.i(TAG, "Lookbon remote connected")
                mainHandler.post { onConnected?.invoke() }
            }
            override fun onDeviceFailedToConnect(d: BluetoothDevice, reason: Int) {
                Log.w(TAG, "Lookbon remote failed: $reason")
                mainHandler.post { onDisconnected?.invoke() }
            }
            override fun onDeviceDisconnected(d: BluetoothDevice, reason: Int) {
                Log.i(TAG, "Lookbon remote disconnected")
                mainHandler.post { onDisconnected?.invoke() }
            }
        })
        connect(device)
            .retry(3, 300)
            .useAutoConnect(autoReconnect)
            .enqueue()
    }
}
