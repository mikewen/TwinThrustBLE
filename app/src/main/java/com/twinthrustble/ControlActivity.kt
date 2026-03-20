package com.twinthrustble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.twinthrustble.databinding.ActivityControlBinding
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * TwinThrustBLE — ControlActivity
 *
 * Motor layout:
 *   Port  BLE → M1 (front-left) + M2 (rear-left)
 *   Stbd  BLE → M3 (front-right) + M4 (rear-right)
 *
 * Sync levels:
 *   SYNC_ALL  — master ▼▲ + slider drives all 4. Side trim (L/R). F/R trim per side.
 *   SYNC_SIDE — port ▼▲ + slider → M1+M2; stbd ▼▲ + slider → M3+M4. F/R trim per side.
 *   SYNC_NONE — four independent ▼▲ + sliders: M1, M2, M3, M4.
 *
 * Status bar: always visible, shows M1–M4 progress bar + % + raw PWM duty.
 */
class ControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlBinding
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var mode = MODE_SINGLE

    // Single-mode
    private lateinit var singleBle: AC6328BleManager
    private var singleDevice: BluetoothDevice? = null
    private var singleName   = ""
    private var singleConnected = false
    private var singleRole   = ROLE_NONE

    // Dual-mode
    private lateinit var portBle: AC6328BleManager
    private lateinit var stbdBle: AC6328BleManager
    private var portConnected = false
    private var stbdConnected = false

    private var escMode = true

    // Sync state
    private var syncLevel = SYNC_ALL

    // SYNC_ALL throttle state (native units)
    private var masterVal    = 0
    private var portSideTrim = 0
    private var stbdSideTrim = 0
    private var portFRTrim   = 0
    private var stbdFRTrim   = 0

    // SYNC_SIDE
    private var portSideVal  = 0
    private var stbdSideVal  = 0

    // SYNC_NONE
    private var m1Val = 0; private var m2Val = 0
    private var m3Val = 0; private var m4Val = 0

    private val TRIM_RANGE_ESC  = 100
    private val TRIM_RANGE_BLDC = 500
    private val FEEDBACK_POLL_MS = 2_000L
    private val HOLD_INTERVAL_MS = 150L   // faster ramp for hold
    private val STEP_SMALL_ESC  = 5       // per button press ESC
    private val STEP_SMALL_BLDC = 100     // per button press BLDC
    private val STEP_LARGE_ESC  = 25      // held ramp ESC
    private val STEP_LARGE_BLDC = 250     // held ramp BLDC

    // GPS
    private lateinit var gpsManager: GpsManager
    private var speedUnitKnots = false

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) gpsManager.startPhoneGps() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        mode  = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        when (mode) {
            MODE_SINGLE -> initSingleMode()
            MODE_DUAL   -> initDualMode()
        }

        setupModeToggle()
        setupGps()
        setupBackPress()
        scheduleFeedbackPoll()
        updateConnUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        gpsManager.stopLogging(); gpsManager.stopPhoneGps()
        safeStopAll()
        when (mode) {
            MODE_SINGLE -> { singleBle.disconnect().enqueue(); singleBle.close() }
            MODE_DUAL   -> {
                portBle.disconnect().enqueue(); stbdBle.disconnect().enqueue()
                portBle.close(); stbdBle.close()
            }
        }
    }

    // ── Single mode ───────────────────────────────────────────────────────────

    private fun initSingleMode() {
        singleDevice = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
        singleName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "BLE Module"
        singleBle  = AC6328BleManager(this)

        val portAddr = prefs.getString(MainActivity.KEY_PORT_ADDR, "") ?: ""
        val stbdAddr = prefs.getString(MainActivity.KEY_STBD_ADDR, "") ?: ""
        singleRole = when (singleDevice?.address) {
            portAddr -> ROLE_PORT; stbdAddr -> ROLE_STBD; else -> ROLE_NONE
        }

        singleDevice?.let {
            connectBleDevice(singleBle, it, singleName,
                onConnected    = { singleConnected = true;  runOnUiThread { updateConnUi(); vibrate(50) } },
                onDisconnected = { singleConnected = false; runOnUiThread { updateConnUi() } })
        }
        showSingleUi()
    }

    private fun showSingleUi() {
        binding.layoutDual.visibility   = View.GONE
        binding.layoutSingle.visibility = View.VISIBLE
        binding.tvSingleName.text       = singleName
        refreshAssignBanner()

        // Slider
        binding.seekSingle.max = 100
        binding.seekSingle.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            if (!fromUser) return@simpleSeekListener
            masterVal = percentToNative(p)
            sendSingle(masterVal)
            binding.tvSinglePct.text = "${p}%  ${masterVal}u"
        })

        // ▼▲ buttons with hold
        setupThrottleButtons(
            btnUp   = binding.btnSingleUp,
            btnDown = binding.btnSingleDown,
            getValue   = { masterVal },
            setValue   = { v ->
                masterVal = v.coerceIn(stopValue(), maxValue())
                binding.seekSingle.progress = nativeToPercent(masterVal)
                sendSingle(masterVal)
                binding.tvSinglePct.text = "${nativeToPercent(masterVal)}%  ${masterVal}u"
            }
        )

        binding.btnAssignPort.setOnClickListener { assignSingle(ROLE_PORT) }
        binding.btnAssignStbd.setOnClickListener { assignSingle(ROLE_STBD) }
        binding.btnSingleStop.setOnClickListener {
            singleBle.stopMotors()
            masterVal = 0
            binding.seekSingle.progress = 0
            binding.tvSinglePct.text = "0%  ${stopValue()}u"
            vibrate(150)
        }
    }

    private fun refreshAssignBanner() {
        when (singleRole) {
            ROLE_PORT -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as PORT (Left) — M1+M2\nSpin to verify, then go back and connect Starboard."
                binding.tvAssignBanner.setBackgroundColor(0xFF1A3A1A.toInt())
                binding.btnAssignPort.alpha = 0.4f; binding.btnAssignStbd.alpha = 1f
            }
            ROLE_STBD -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as STARBOARD (Right) — M3+M4\nSpin to verify, then go back and connect Port."
                binding.tvAssignBanner.setBackgroundColor(0xFF1A1A3A.toInt())
                binding.btnAssignPort.alpha = 1f; binding.btnAssignStbd.alpha = 0.4f
            }
            else -> {
                binding.tvAssignBanner.text =
                    "⚠ Not assigned — spin motors to identify this side, then assign below."
                binding.tvAssignBanner.setBackgroundColor(0xFF2A2000.toInt())
                binding.btnAssignPort.alpha = 1f; binding.btnAssignStbd.alpha = 1f
            }
        }
    }

    private fun assignSingle(role: String) {
        val addr = singleDevice?.address ?: return
        if (role == ROLE_PORT) {
            if (prefs.getString(MainActivity.KEY_STBD_ADDR, "") == addr)
                prefs.edit().remove(MainActivity.KEY_STBD_ADDR).remove(MainActivity.KEY_STBD_NAME).apply()
            prefs.edit().putString(MainActivity.KEY_PORT_ADDR, addr)
                .putString(MainActivity.KEY_PORT_NAME, singleName).apply()
        } else {
            if (prefs.getString(MainActivity.KEY_PORT_ADDR, "") == addr)
                prefs.edit().remove(MainActivity.KEY_PORT_ADDR).remove(MainActivity.KEY_PORT_NAME).apply()
            prefs.edit().putString(MainActivity.KEY_STBD_ADDR, addr)
                .putString(MainActivity.KEY_STBD_NAME, singleName).apply()
        }
        singleRole = role
        refreshAssignBanner()
        showToast("$singleName → ${if (role == ROLE_PORT) "PORT ⬅ (M1+M2)" else "STBD ➡ (M3+M4)"}")
        vibrate(80)
    }

    // ── Dual mode ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun initDualMode() {
        val portAddr = intent.getStringExtra(EXTRA_PORT_ADDR) ?: prefs.getString(MainActivity.KEY_PORT_ADDR, "") ?: ""
        val portName = intent.getStringExtra(EXTRA_PORT_NAME) ?: prefs.getString(MainActivity.KEY_PORT_NAME, "") ?: "Port"
        val stbdAddr = intent.getStringExtra(EXTRA_STBD_ADDR) ?: prefs.getString(MainActivity.KEY_STBD_ADDR, "") ?: ""
        val stbdName = intent.getStringExtra(EXTRA_STBD_NAME) ?: prefs.getString(MainActivity.KEY_STBD_NAME, "") ?: "Stbd"

        binding.tvPortLabel.text = "\u2b05 $portName"
        binding.tvStbdLabel.text = "$stbdName \u27a1"

        portBle = AC6328BleManager(this); stbdBle = AC6328BleManager(this)

        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        fun resolve(a: String) = try { bt.getRemoteDevice(a) } catch (e: Exception) { null }

        resolve(portAddr)?.let { dev ->
            connectBleDevice(portBle, dev, portName,
                onConnected    = { portConnected = true;  runOnUiThread { updateConnUi(); vibrate(50) } },
                onDisconnected = { portConnected = false; runOnUiThread { updateConnUi() } },
                battCallback   = { pct -> runOnUiThread { binding.tvPortBatt.text = "\uD83D\uDD0B $pct%" } })
        } ?: showToast("Cannot resolve Port device")

        resolve(stbdAddr)?.let { dev ->
            connectBleDevice(stbdBle, dev, stbdName,
                onConnected    = { stbdConnected = true;  runOnUiThread { updateConnUi(); vibrate(50) } },
                onDisconnected = { stbdConnected = false; runOnUiThread { updateConnUi() } },
                battCallback   = { pct -> runOnUiThread { binding.tvStbdBatt.text = "\uD83D\uDD0B $pct%" } })
        } ?: showToast("Cannot resolve Starboard device")

        showDualUi()
    }

    private fun showDualUi() {
        binding.layoutSingle.visibility = View.GONE
        binding.layoutDual.visibility   = View.VISIBLE
        setupSyncControls()
        setupStopButton()
        setupSpeedUnitButton()
        applySyncLevel()
        updateThrustUi()  // initialise status bar immediately
    }

    // ── BLE connect helper ────────────────────────────────────────────────────

    private fun connectBleDevice(
        mgr: AC6328BleManager, device: BluetoothDevice, label: String,
        onConnected: () -> Unit, onDisconnected: () -> Unit,
        battCallback: ((Int) -> Unit)? = null
    ) {
        mgr.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(d: BluetoothDevice) {}
            override fun onDeviceConnected(d: BluetoothDevice)              = onConnected()
            override fun onDeviceFailedToConnect(d: BluetoothDevice, r: Int) {
                runOnUiThread { showToast("$label connect failed ($r)") }
            }
            override fun onDeviceDisconnecting(d: BluetoothDevice) {}
            override fun onDeviceDisconnected(d: BluetoothDevice, r: Int)   = onDisconnected()
            override fun onDeviceReady(d: BluetoothDevice) { mgr.applyMode() }
        })
        battCallback?.let { cb ->
            mgr.onFeedback = { data ->
                if (data.batteryMv > 0) cb(mgr.battMvToPercent(data.batteryMv))
            }
        }
        mgr.connectToDevice(device)
    }

    // ── ESC/BLDC mode toggle ──────────────────────────────────────────────────

    private fun setupModeToggle() {
        binding.switchMode.isChecked = escMode
        binding.tvModeLabel.text = if (escMode) "ESC" else "BLDC"
        binding.switchMode.setOnCheckedChangeListener { _, checked ->
            escMode = checked
            binding.tvModeLabel.text = if (escMode) "ESC" else "BLDC"
            resetAllThrottle()
            when (mode) {
                MODE_SINGLE -> singleBle.applyMode()
                MODE_DUAL   -> { portBle.applyMode(); stbdBle.applyMode() }
            }
            safeStopAll()
            updateSliderRanges()
            updateThrustUi()
        }
    }

    private fun AC6328BleManager.applyMode() = if (escMode) setEscMode() else setBldcMode()

    // ── Sync controls ─────────────────────────────────────────────────────────

    private fun setupSyncControls() {
        binding.btnSyncAll.setOnClickListener  { setSyncLevel(SYNC_ALL);  sendThrust(); updateThrustUi() }
        binding.btnSyncSide.setOnClickListener { setSyncLevel(SYNC_SIDE); sendThrust(); updateThrustUi() }
        binding.btnSyncNone.setOnClickListener { setSyncLevel(SYNC_NONE); sendThrust(); updateThrustUi() }

        // ── SYNC_ALL: master ▼▲ + slider ──
        setupThrottleButtons(
            btnUp   = binding.btnMasterUp,
            btnDown = binding.btnMasterDown,
            getValue = { masterVal },
            setValue = { v ->
                masterVal = v.coerceIn(stopValue(), maxValue())
                binding.seekMaster.progress = nativeToPercent(masterVal)
                sendThrust(); updateThrustUi()
            }
        )
        binding.seekMaster.max = 100
        binding.seekMaster.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            if (!fromUser || syncLevel != SYNC_ALL) return@simpleSeekListener
            masterVal = percentToNative(p); sendThrust(); updateThrustUi()
        })

        // ── Side trims (hold) ──
        setupHoldButton(binding.btnPortSideTrimUp)   { portSideTrim += trimStep(); clampSideTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnPortSideTrimDown) { portSideTrim -= trimStep(); clampSideTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdSideTrimUp)   { stbdSideTrim += trimStep(); clampSideTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdSideTrimDown) { stbdSideTrim -= trimStep(); clampSideTrims(); sendThrust(); updateThrustUi() }
        binding.btnResetTrims.setOnClickListener {
            portSideTrim = 0; stbdSideTrim = 0; portFRTrim = 0; stbdFRTrim = 0
            sendThrust(); updateThrustUi()
        }

        // ── F/R trims (hold) ──
        setupHoldButton(binding.btnPortFRTrimUp)   { portFRTrim += trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnPortFRTrimDown) { portFRTrim -= trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdFRTrimUp)   { stbdFRTrim += trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdFRTrimDown) { stbdFRTrim -= trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }

        // ── SYNC_SIDE: port ▼▲ + slider ──
        setupThrottleButtons(
            btnUp   = binding.btnPortSideUp,
            btnDown = binding.btnPortSideDown,
            getValue = { portSideVal },
            setValue = { v ->
                portSideVal = v.coerceIn(stopValue(), maxValue())
                binding.seekPort.progress = (portSideVal - stopValue()).coerceAtLeast(0)
                sendThrust(); updateThrustUi()
            }
        )
        binding.seekPort.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            if (!fromUser || syncLevel != SYNC_SIDE) return@simpleSeekListener
            portSideVal = stopValue() + p; sendThrust(); updateThrustUi()
        })

        // ── SYNC_SIDE: stbd ▼▲ + slider ──
        setupThrottleButtons(
            btnUp   = binding.btnStbdSideUp,
            btnDown = binding.btnStbdSideDown,
            getValue = { stbdSideVal },
            setValue = { v ->
                stbdSideVal = v.coerceIn(stopValue(), maxValue())
                binding.seekStbd.progress = (stbdSideVal - stopValue()).coerceAtLeast(0)
                sendThrust(); updateThrustUi()
            }
        )
        binding.seekStbd.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            if (!fromUser || syncLevel != SYNC_SIDE) return@simpleSeekListener
            stbdSideVal = stopValue() + p; sendThrust(); updateThrustUi()
        })

        // ── SYNC_NONE: M1–M4 ▼▲ + sliders ──
        data class MConfig(val up: android.widget.Button, val dn: android.widget.Button,
                           val seek: SeekBar, val get: () -> Int, val set: (Int) -> Unit)
        val motors = listOf(
            MConfig(binding.btnM1Up, binding.btnM1Down, binding.seekM1,
                { m1Val }, { v -> m1Val = v.coerceIn(stopValue(), maxValue()); binding.seekM1.progress = (m1Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
            MConfig(binding.btnM2Up, binding.btnM2Down, binding.seekM2,
                { m2Val }, { v -> m2Val = v.coerceIn(stopValue(), maxValue()); binding.seekM2.progress = (m2Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
            MConfig(binding.btnM3Up, binding.btnM3Down, binding.seekM3,
                { m3Val }, { v -> m3Val = v.coerceIn(stopValue(), maxValue()); binding.seekM3.progress = (m3Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
            MConfig(binding.btnM4Up, binding.btnM4Down, binding.seekM4,
                { m4Val }, { v -> m4Val = v.coerceIn(stopValue(), maxValue()); binding.seekM4.progress = (m4Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
        )
        for (m in motors) {
            setupThrottleButtons(m.up, m.dn, m.get, m.set)
            m.seek.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
                if (!fromUser || syncLevel != SYNC_NONE) return@simpleSeekListener
                m.set(stopValue() + p)
            })
        }

        updateSliderRanges()
    }

    // ── Throttle button helper (tap = small step, hold = ramp) ────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupThrottleButtons(
        btnUp: android.widget.Button,
        btnDown: android.widget.Button,
        getValue: () -> Int,
        setValue: (Int) -> Unit
    ) {
        fun step(dir: Int, large: Boolean) {
            val s = if (large) { if (escMode) STEP_LARGE_ESC else STEP_LARGE_BLDC }
            else       { if (escMode) STEP_SMALL_ESC else STEP_SMALL_BLDC }
            setValue(getValue() + dir * s)
        }

        listOf(btnUp to +1, btnDown to -1).forEach { (btn, dir) ->
            var ramp: Runnable? = null
            btn.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        step(dir, false)   // immediate single step on tap
                        ramp = object : Runnable {
                            override fun run() { step(dir, true); handler.postDelayed(this, HOLD_INTERVAL_MS) }
                        }
                        handler.postDelayed(ramp!!, 500L)  // delay before ramp starts
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ramp?.let { handler.removeCallbacks(it) }; ramp = null
                    }
                }
                false
            }
        }
    }

    private fun setSyncLevel(level: String) {
        val pf = computePortFront(); val sr = computeStbdFront()
        when (level) {
            SYNC_SIDE -> { portSideVal = pf; stbdSideVal = sr }
            SYNC_NONE -> { m1Val = computePortFront(); m2Val = computePortRear()
                m3Val = computeStbdFront(); m4Val = computeStbdRear() }
        }
        syncLevel = level
        applySyncLevel()
    }

    private fun applySyncLevel() {
        val active   = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
        val inactive = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
        binding.btnSyncAll.backgroundTintList  = if (syncLevel == SYNC_ALL)  active else inactive
        binding.btnSyncSide.backgroundTintList = if (syncLevel == SYNC_SIDE) active else inactive
        binding.btnSyncNone.backgroundTintList = if (syncLevel == SYNC_NONE) active else inactive

        binding.layoutSyncAll.visibility  = if (syncLevel == SYNC_ALL)  View.VISIBLE else View.GONE
        binding.layoutSyncSide.visibility = if (syncLevel == SYNC_SIDE) View.VISIBLE else View.GONE
        binding.layoutSyncNone.visibility = if (syncLevel == SYNC_NONE) View.VISIBLE else View.GONE
        binding.layoutFRTrim.visibility   = if (syncLevel == SYNC_NONE) View.GONE else View.VISIBLE

        updateSliderRanges()
    }

    private fun updateSliderRanges() {
        val range = maxValue() - stopValue()
        binding.seekMaster.max = 100
        listOf(binding.seekPort, binding.seekStbd,
            binding.seekM1, binding.seekM2, binding.seekM3, binding.seekM4)
            .forEach { it.max = range }
    }

    // ── Thrust computation ────────────────────────────────────────────────────

    private fun computePortFront() = when (syncLevel) {
        SYNC_ALL  -> (masterVal + portSideTrim + portFRTrim).coerceIn(stopValue(), maxValue())
        SYNC_SIDE -> (portSideVal + portFRTrim).coerceIn(stopValue(), maxValue())
        else      -> m1Val.coerceIn(stopValue(), maxValue())
    }
    private fun computePortRear() = when (syncLevel) {
        SYNC_ALL  -> (masterVal + portSideTrim - portFRTrim).coerceIn(stopValue(), maxValue())
        SYNC_SIDE -> (portSideVal - portFRTrim).coerceIn(stopValue(), maxValue())
        else      -> m2Val.coerceIn(stopValue(), maxValue())
    }
    private fun computeStbdFront() = when (syncLevel) {
        SYNC_ALL  -> (masterVal + stbdSideTrim + stbdFRTrim).coerceIn(stopValue(), maxValue())
        SYNC_SIDE -> (stbdSideVal + stbdFRTrim).coerceIn(stopValue(), maxValue())
        else      -> m3Val.coerceIn(stopValue(), maxValue())
    }
    private fun computeStbdRear() = when (syncLevel) {
        SYNC_ALL  -> (masterVal + stbdSideTrim - stbdFRTrim).coerceIn(stopValue(), maxValue())
        SYNC_SIDE -> (stbdSideVal - stbdFRTrim).coerceIn(stopValue(), maxValue())
        else      -> m4Val.coerceIn(stopValue(), maxValue())
    }

    // ── Send thrust ───────────────────────────────────────────────────────────

    private fun sendSingle(duty: Int) {
        val d = duty.coerceIn(stopValue(), maxValue())
        if (escMode) singleBle.sendEscPwm(d, d) else singleBle.sendBldc(d, d)
    }

    private fun sendThrust() {
        val m1 = computePortFront(); val m2 = computePortRear()
        val m3 = computeStbdFront(); val m4 = computeStbdRear()
        if (escMode) {
            if (portConnected) portBle.sendEscPwm(m1, m2)
            if (stbdConnected) stbdBle.sendEscPwm(m3, m4)
        } else {
            if (portConnected) portBle.sendBldc(m1, m2)
            if (stbdConnected) stbdBle.sendBldc(m3, m4)
        }
    }

    private fun safeStopAll() {
        repeat(3) {
            if (mode == MODE_SINGLE && ::singleBle.isInitialized) singleBle.stopMotors()
            if (mode == MODE_DUAL) {
                if (::portBle.isInitialized) portBle.stopMotors()
                if (::stbdBle.isInitialized) stbdBle.stopMotors()
            }
        }
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateConnUi() = runOnUiThread {
        when (mode) {
            MODE_SINGLE -> {
                binding.tvPortStatus.text = if (singleConnected) "✅ Connected" else "⚠ Connecting..."
                binding.tvPortStatus.setTextColor(if (singleConnected) 0xFF66FF66.toInt() else 0xFFFF6666.toInt())
                binding.tvStbdStatus.visibility = View.GONE
                binding.btnSingleStop.isEnabled = singleConnected
            }
            MODE_DUAL -> {
                binding.tvPortStatus.text = if (portConnected) "✅" else "⚠"
                binding.tvStbdStatus.text = if (stbdConnected) "✅" else "⚠"
                binding.tvPortStatus.setTextColor(if (portConnected) 0xFF66FF66.toInt() else 0xFFFF6666.toInt())
                binding.tvStbdStatus.setTextColor(if (stbdConnected) 0xFF66FF66.toInt() else 0xFFFF6666.toInt())
                binding.btnStop.isEnabled = portConnected || stbdConnected
            }
        }
    }

    /** Called after EVERY throttle state change — updates all status indicators. */
    private fun updateThrustUi() {
        if (mode == MODE_SINGLE) return

        val m1 = computePortFront(); val m2 = computePortRear()
        val m3 = computeStbdFront(); val m4 = computeStbdRear()

        // Status bar (always visible) — % AND raw duty
        fun dutyLabel(v: Int) = if (escMode) "${v}u" else "${v}"
        binding.pbM1.progress   = nativeToPercent(m1); binding.tvM1Pct.text  = "${nativeToPercent(m1)}%"; binding.tvM1Duty.text = dutyLabel(m1)
        binding.pbM2.progress   = nativeToPercent(m2); binding.tvM2Pct.text  = "${nativeToPercent(m2)}%"; binding.tvM2Duty.text = dutyLabel(m2)
        binding.pbM3.progress   = nativeToPercent(m3); binding.tvM3Pct.text  = "${nativeToPercent(m3)}%"; binding.tvM3Duty.text = dutyLabel(m3)
        binding.pbM4.progress   = nativeToPercent(m4); binding.tvM4Pct.text  = "${nativeToPercent(m4)}%"; binding.tvM4Duty.text = dutyLabel(m4)

        // Master pct (SYNC_ALL)
        binding.tvMasterPct.text = "${nativeToPercent(masterVal)}%"

        // Side pct labels
        val lPct = nativeToPercent(computePortFront()); val rPct = nativeToPercent(computeStbdFront())
        binding.tvPortPct.text = "L $lPct%"
        binding.tvStbdPct.text = "R $rPct%"

        // Trim labels
        binding.tvPortSideTrimVal.text = fmtTrim(portSideTrim)
        binding.tvStbdSideTrimVal.text = fmtTrim(stbdSideTrim)
        binding.tvPortFRTrimVal.text   = fmtTrim(portFRTrim)
        binding.tvStbdFRTrimVal.text   = fmtTrim(stbdFRTrim)

        // Side pct in SYNC_SIDE panel
        binding.tvPortSidePct.text = "$lPct%"
        binding.tvStbdSidePct.text = "$rPct%"

        // Indep pcts
        binding.tvM1OnlyPct.text = "${nativeToPercent(m1)}%"
        binding.tvM2OnlyPct.text = "${nativeToPercent(m2)}%"
        binding.tvM3OnlyPct.text = "${nativeToPercent(m3)}%"
        binding.tvM4OnlyPct.text = "${nativeToPercent(m4)}%"
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun setupGps() {
        gpsManager = GpsManager(this)
        gpsManager.onUpdate = { data -> runOnUiThread { updateGpsUi(data) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) gpsManager.startPhoneGps()
        else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        gpsManager.startLogging()
        binding.btnSpeedUnit.text = if (speedUnitKnots) "kn" else "km/h"
    }

    private fun updateGpsUi(data: GpsManager.GpsData) {
        val speed = if (speedUnitKnots) data.speedKnots else data.speedKmh
        binding.tvSpeed.text      = "%.1f".format(speed)
        binding.tvSpeedUnit.text  = if (speedUnitKnots) "kn" else "km/h"
        binding.tvGpsFix.text     = if (data.hasFix) "\u2713${data.satellites}sat" else "No Fix"
        binding.tvGpsHeading.text = if (data.hasHeading) "%.0f\u00b0%s".format(data.headingDeg, data.headingCardinal) else "\u2014"
        binding.tvGpsAlt.text     = if (data.hasFix) "%.0fm".format(data.altitudeM) else "\u2014"
        binding.tvGpsCoords.text  = if (data.hasFix) "%.4f\u00b0\n%.4f\u00b0".format(data.latDeg, data.lonDeg) else "\u2014"
    }

    // ── Stop + unit buttons ───────────────────────────────────────────────────

    private fun setupStopButton() {
        binding.btnStop.setOnClickListener {
            safeStopAll(); resetAllThrottle()
            binding.seekMaster.progress = 0
            binding.seekPort.progress = 0; binding.seekStbd.progress = 0
            binding.seekM1.progress = 0; binding.seekM2.progress = 0
            binding.seekM3.progress = 0; binding.seekM4.progress = 0
            updateThrustUi(); vibrate(200)
        }
    }

    private fun setupSpeedUnitButton() {
        binding.btnSpeedUnit.setOnClickListener {
            speedUnitKnots = !speedUnitKnots
            binding.btnSpeedUnit.text = if (speedUnitKnots) "kn" else "km/h"
            updateGpsUi(gpsManager.getCurrentData())
        }
    }

    // ── Feedback poll ─────────────────────────────────────────────────────────

    private fun scheduleFeedbackPoll() {
        val r = object : Runnable {
            override fun run() {
                if (mode == MODE_SINGLE && singleConnected) singleBle.readStatus()
                if (mode == MODE_DUAL) {
                    if (portConnected) portBle.readStatus()
                    if (stbdConnected) stbdBle.readStatus()
                }
                handler.postDelayed(this, FEEDBACK_POLL_MS)
            }
        }
        handler.postDelayed(r, FEEDBACK_POLL_MS)
    }

    // ── Back press ────────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this) {
            AlertDialog.Builder(this@ControlActivity)
                .setTitle("Stop motors & disconnect?")
                .setPositiveButton("Yes") { _, _ -> safeStopAll(); finish() }
                .setNegativeButton("Stay", null).show()
        }
    }

    // ── Hold-button (trim) ────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldButton(btn: android.widget.Button, action: () -> Unit) {
        var r: Runnable? = null
        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    r = object : Runnable { override fun run() { action(); handler.postDelayed(this, HOLD_INTERVAL_MS) } }
                    handler.postDelayed(r!!, 400L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    r?.let { handler.removeCallbacks(it) }; r = null
                }
            }
            false
        }
    }

    private fun simpleSeekListener(onChanged: (Int, Boolean) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChanged(p, fromUser)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stopValue()  = if (escMode) AC6328BleManager.ESC_MIN  else AC6328BleManager.BLDC_MIN
    private fun maxValue()   = if (escMode) AC6328BleManager.ESC_MAX  else AC6328BleManager.BLDC_MAX
    private fun trimRange()  = if (escMode) TRIM_RANGE_ESC             else TRIM_RANGE_BLDC
    private fun trimStep()   = if (escMode) 5                          else 50
    private fun fmtTrim(v: Int) = if (v >= 0) "+$v" else "$v"

    private fun percentToNative(pct: Int) =
        stopValue() + (pct * (maxValue() - stopValue()) / 100)

    private fun nativeToPercent(n: Int): Int {
        val range = maxValue() - stopValue()
        return if (range == 0) 0 else ((n - stopValue()) * 100 / range).coerceIn(0, 100)
    }

    private fun clampSideTrims() {
        portSideTrim = portSideTrim.coerceIn(-trimRange(), trimRange())
        stbdSideTrim = stbdSideTrim.coerceIn(-trimRange(), trimRange())
    }
    private fun clampFRTrims() {
        portFRTrim = portFRTrim.coerceIn(-trimRange(), trimRange())
        stbdFRTrim = stbdFRTrim.coerceIn(-trimRange(), trimRange())
    }

    private fun resetAllThrottle() {
        masterVal = 0; portSideTrim = 0; stbdSideTrim = 0; portFRTrim = 0; stbdFRTrim = 0
        portSideVal = stopValue(); stbdSideVal = stopValue()
        m1Val = stopValue(); m2Val = stopValue(); m3Val = stopValue(); m4Val = stopValue()
    }

    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE        = "mode"
        const val EXTRA_DEVICE      = "device"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_PORT_ADDR   = "port_addr"
        const val EXTRA_PORT_NAME   = "port_name"
        const val EXTRA_STBD_ADDR   = "stbd_addr"
        const val EXTRA_STBD_NAME   = "stbd_name"

        const val MODE_SINGLE = "single"
        const val MODE_DUAL   = "dual"
        const val ROLE_NONE   = ""
        const val ROLE_PORT   = "port"
        const val ROLE_STBD   = "stbd"

        const val SYNC_ALL  = "all"
        const val SYNC_SIDE = "side"
        const val SYNC_NONE = "none"
    }
}