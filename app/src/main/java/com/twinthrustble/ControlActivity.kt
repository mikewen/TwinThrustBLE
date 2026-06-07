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
import android.speech.tts.TextToSpeech
import android.util.Log
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
import java.util.Locale

/**
 * TwinThrustBLE — ControlActivity
 *
 * Motor layout:
 *   Port  BLE → M1 (front-left) + M2 (rear-left)
 *   Stbd  BLE → M3 (front-right) + M4 (rear-right)
 *   Front BLE → M5 + M6 (aux front)
 *
 * Sync levels:
 *   SYNC_ALL  — master ▼▲ + slider drives all motors. Side trim (L/R). F/R trim per side.
 *   SYNC_SIDE — port ▼▲ + slider → M1+M2; stbd ▼▲ + slider → M3+M4. F/R trim per side.
 *   SYNC_NONE — independent ▼▲ + sliders.
 */
class ControlActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

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

    // Multi-mode
    private lateinit var portBle: AC6328BleManager
    private lateinit var stbdBle: AC6328BleManager
    private lateinit var frontBle: AC6328BleManager
    private var portConnected = false
    private var stbdConnected = false
    private var frontConnected = false

    private var escMode = true

    // Sync state
    private var syncLevel = SYNC_ALL

    // SYNC_ALL throttle state (native units)
    private var masterVal    = 0
    private var portSideTrim = 0
    private var stbdSideTrim = 0
    private var frontTrim    = 0
    private var portFRTrim   = 0
    private var stbdFRTrim   = 0

    // SYNC_SIDE
    private var portSideVal  = 0
    private var stbdSideVal  = 0

    // SYNC_NONE
    private var m1Val = 0; private var m2Val = 0
    private var m3Val = 0; private var m4Val = 0
    private var m5Val = 0; private var m6Val = 0

    private val TRIM_RANGE_ESC  = 100
    private val TRIM_RANGE_BLDC = 500
    private val FEEDBACK_POLL_MS = 2_000L
    private val COMMAND_LOOP_MS  = 300L   // for firmware watchdog
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

    // TTS
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        mode  = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        tts = TextToSpeech(this, this)

        when (mode) {
            MODE_SINGLE -> initSingleMode()
            MODE_DUAL   -> initDualMode()
        }

        setupModeToggle()
        setupGps()
        setupBackPress()
        scheduleFeedbackPoll()
        scheduleCommandLoop()
        updateConnUi()

        setupLookbonRemote()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        gpsManager.stopLogging(); gpsManager.stopPhoneGps()
        safeStopAll()
        when (mode) {
            MODE_SINGLE -> { if (::singleBle.isInitialized) { singleBle.disconnect().enqueue(); singleBle.close() } }
            MODE_DUAL   -> {
                if (::portBle.isInitialized) { portBle.disconnect().enqueue(); portBle.close() }
                if (::stbdBle.isInitialized) { stbdBle.disconnect().enqueue(); stbdBle.close() }
                if (::frontBle.isInitialized) { frontBle.disconnect().enqueue(); frontBle.close() }
            }
        }
        remote?.disconnect()?.enqueue()
        tts?.stop()
        tts?.shutdown()
    }

    // ── TTS ───────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            speak("System ready")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ── Lookbon Remote ────────────────────────────────────────────────────────

    private fun setupLookbonRemote() {
        remote = LookbonRemote(this)
        remote?.onConnected = {
            speak("Remote connected")
            vibrate(100)
        }
        remote?.onDisconnected = {
            speak("Remote lost")
        }
        remote?.onCommand = { cmd ->
            handleRemoteCommand(cmd)
        }

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        val savedAddr = prefs.getString(MainActivity.KEY_LOOKBON_ADDR, "") ?: ""

        if (savedAddr.isNotEmpty()) {
            try {
                val device = adapter.getRemoteDevice(savedAddr)
                remote?.connectToDevice(device, true)
                return
            } catch (e: Exception) {
                Log.e("ControlActivity", "Error connecting to saved remote: $savedAddr", e)
            }
        }

        @SuppressLint("MissingPermission")
        val bonded = adapter.bondedDevices
        val lookbon = bonded?.find { dev ->
            val name = dev.name ?: ""
            LookbonRemote.REMOTE_NAME_FILTERS.any { name.contains(it, ignoreCase = true) }
        }

        lookbon?.let {
            remote?.connectToDevice(it, true)
        } ?: run {
            Log.w("ControlActivity", "No Lookbon remote found")
        }
    }

    private fun handleRemoteCommand(cmd: LookbonRemote.Command) {
        when (cmd) {
            LookbonRemote.Command.SPEED_UP -> {
                adjustMaster(1)
                speak("${nativeToPercent(masterVal)}")
            }
            LookbonRemote.Command.SPEED_DOWN -> {
                adjustMaster(-1)
                speak("${nativeToPercent(masterVal)}")
            }
            LookbonRemote.Command.SPEED_UP_FAST -> {
                adjustMaster(5)
                speak("${nativeToPercent(masterVal)}")
            }
            LookbonRemote.Command.SPEED_DOWN_FAST -> {
                adjustMaster(-5)
                speak("${nativeToPercent(masterVal)}")
            }
            LookbonRemote.Command.STOP -> {
                speak("Stopped")
                safeStopAll()
                resetAllThrottle()
                updateThrustUi()
                vibrate(200)
            }
            LookbonRemote.Command.START_REPEAT_UP -> {
                startRamp(1, false)
            }
            LookbonRemote.Command.START_REPEAT_DOWN -> {
                startRamp(-1, false)
            }
            LookbonRemote.Command.START_REPEAT_UP_FAST -> {
                startRamp(1, true)
            }
            LookbonRemote.Command.START_REPEAT_DOWN_FAST -> {
                startRamp(-1, true)
            }
            LookbonRemote.Command.STOP_REPEAT -> {
                stopRamp()
                speak("${nativeToPercent(masterVal)}")
            }
            LookbonRemote.Command.STEER_LEFT -> {
                adjustSteer(-1)
            }
            LookbonRemote.Command.STEER_RIGHT -> {
                adjustSteer(1)
            }
            LookbonRemote.Command.START_STEER_LEFT -> {
                speak("Left")
                startSteer(-1)
            }
            LookbonRemote.Command.START_STEER_RIGHT -> {
                speak("Right")
                startSteer(1)
            }
            LookbonRemote.Command.STOP_STEER -> {
                stopSteer()
            }
            else -> {}
        }
    }

    private var remote: LookbonRemote? = null

    private var rampRunnable: Runnable? = null
    private fun startRamp(dir: Int, fast: Boolean) {
        stopRamp()
        rampRunnable = object : Runnable {
            override fun run() {
                adjustMaster(dir * (if (fast) 2 else 1))
                handler.postDelayed(this, 100)
            }
        }
        handler.post(rampRunnable!!)
    }
    private fun stopRamp() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
    }

    private fun adjustMaster(deltaPct: Int) {
        val currentPct = nativeToPercent(masterVal)
        val newPct = (currentPct + deltaPct).coerceIn(0, 100)
        masterVal = percentToNative(newPct)
        if (mode == MODE_SINGLE) {
            sendSingle(masterVal)
            binding.seekSingle.progress = newPct
            binding.tvSinglePct.text = "${newPct}%  ${masterVal}u"
        } else {
            binding.seekMaster.progress = newPct
            sendThrust()
            updateThrustUi()
        }
    }

    private fun adjustSteer(dir: Int) {
        if (syncLevel == SYNC_ALL) {
            portSideTrim += dir * 2
            stbdSideTrim -= dir * 2
            clampSideTrims()
            sendThrust()
            updateThrustUi()
        }
    }

    private var steerRunnable: Runnable? = null
    private fun startSteer(dir: Int) {
        stopSteer()
        steerRunnable = object : Runnable {
            override fun run() {
                if (syncLevel == SYNC_ALL) {
                    portSideTrim -= dir * 2
                    stbdSideTrim += dir * 2
                    clampSideTrims()
                    sendThrust()
                    updateThrustUi()
                }
                handler.postDelayed(this, 100)
            }
        }
        handler.post(steerRunnable!!)
    }
    private fun stopSteer() {
        steerRunnable?.let { handler.removeCallbacks(it) }
        steerRunnable = null
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
        val frontAddr = prefs.getString(MainActivity.KEY_FRONT_ADDR, "") ?: ""
        val remoteAddr = prefs.getString(MainActivity.KEY_LOOKBON_ADDR, "") ?: ""
        
        singleRole = when (singleDevice?.address) {
            portAddr -> ROLE_PORT
            stbdAddr -> ROLE_STBD
            frontAddr -> ROLE_FRONT
            remoteAddr -> ROLE_REMOTE
            else -> ROLE_NONE
        }

        singleDevice?.let {
            connectBleDevice(singleBle, it, singleName,
                onConnected    = { 
                    singleConnected = true
                    runOnUiThread { 
                        updateConnUi()
                        vibrate(50)
                        speak("$singleName connected")
                    } 
                },
                onDisconnected = { 
                    singleConnected = false
                    runOnUiThread { 
                        updateConnUi()
                        speak("$singleName lost")
                    } 
                },
                onFeedback = { data ->
                    if (singleRole == ROLE_FRONT && data.currentAmps > 0.05f) {
                        val watts = data.currentAmps * 48f
                        Log.d("AC6328", "Front Single Current: ${data.currentAmps}A ($watts W)")
                    }
                })
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
        binding.btnAssignFront.setOnClickListener { assignSingle(ROLE_FRONT) }
        binding.btnAssignRemote.setOnClickListener { assignSingle(ROLE_REMOTE) }
        
        binding.btnSingleStop.setOnClickListener {
            singleBle.stopMotors()
            masterVal = 0
            binding.seekSingle.progress = 0
            binding.tvSinglePct.text = "0%  ${stopValue()}u"
            vibrate(150)
            speak("Stop")
        }
    }

    private fun refreshAssignBanner() {
        when (singleRole) {
            ROLE_PORT -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as PORT (Left) — M1+M2\nSpin to verify, then go back and connect Starboard."
                binding.tvAssignBanner.setBackgroundColor(0xFF1A3A1A.toInt())
            }
            ROLE_STBD -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as STARBOARD (Right) — M3+M4\nSpin to verify, then go back and connect Port."
                binding.tvAssignBanner.setBackgroundColor(0xFF1A1A3A.toInt())
            }
            ROLE_FRONT -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as FRONT — M5+M6\nSpin to verify, then go back and connect others."
                binding.tvAssignBanner.setBackgroundColor(0xFF1A3A3A.toInt())
            }
            ROLE_REMOTE -> {
                binding.tvAssignBanner.text =
                    "✅ Assigned as LOOKBON REMOTE\nThis device will now control both sides in Launch mode."
                binding.tvAssignBanner.setBackgroundColor(0xFF4A4A1A.toInt())
            }
            else -> {
                binding.tvAssignBanner.text =
                    "⚠ Not assigned — spin motors to identify this side, then assign below."
                binding.tvAssignBanner.setBackgroundColor(0xFF2A2000.toInt())
            }
        }
        binding.btnAssignPort.alpha = if (singleRole == ROLE_PORT) 0.4f else 1f
        binding.btnAssignStbd.alpha = if (singleRole == ROLE_STBD) 0.4f else 1f
        binding.btnAssignFront.alpha = if (singleRole == ROLE_FRONT) 0.4f else 1f
        binding.btnAssignRemote.alpha = if (singleRole == ROLE_REMOTE) 0.4f else 1f
    }

    private fun assignSingle(role: String) {
        val addr = singleDevice?.address ?: return
        val editor = prefs.edit()
        
        // Remove from other roles if it was assigned there
        if (prefs.getString(MainActivity.KEY_PORT_ADDR, "") == addr) editor.remove(MainActivity.KEY_PORT_ADDR).remove(MainActivity.KEY_PORT_NAME)
        if (prefs.getString(MainActivity.KEY_STBD_ADDR, "") == addr) editor.remove(MainActivity.KEY_STBD_ADDR).remove(MainActivity.KEY_STBD_NAME)
        if (prefs.getString(MainActivity.KEY_FRONT_ADDR, "") == addr) editor.remove(MainActivity.KEY_FRONT_ADDR).remove(MainActivity.KEY_FRONT_NAME)
        if (prefs.getString(MainActivity.KEY_LOOKBON_ADDR, "") == addr) editor.remove(MainActivity.KEY_LOOKBON_ADDR).remove(MainActivity.KEY_LOOKBON_NAME)

        when (role) {
            ROLE_PORT -> editor.putString(MainActivity.KEY_PORT_ADDR, addr).putString(MainActivity.KEY_PORT_NAME, singleName)
            ROLE_STBD -> editor.putString(MainActivity.KEY_STBD_ADDR, addr).putString(MainActivity.KEY_STBD_NAME, singleName)
            ROLE_FRONT -> editor.putString(MainActivity.KEY_FRONT_ADDR, addr).putString(MainActivity.KEY_FRONT_NAME, singleName)
            ROLE_REMOTE -> editor.putString(MainActivity.KEY_LOOKBON_ADDR, addr).putString(MainActivity.KEY_LOOKBON_NAME, singleName)
        }
        editor.apply()
        
        singleRole = role
        refreshAssignBanner()
        val roleLabel = when(role) {
            ROLE_PORT -> "PORT ⬅ (M1+M2)"
            ROLE_STBD -> "STBD ➡ (M3+M4)"
            ROLE_FRONT -> "FRONT (M5+M6)"
            ROLE_REMOTE -> "REMOTE"
            else -> ""
        }
        showToast("$singleName → $roleLabel")
        vibrate(80)
        speak("Assigned $roleLabel")
    }

    // ── Dual/Multi mode ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun initDualMode() {
        val portAddr = intent.getStringExtra(EXTRA_PORT_ADDR) ?: prefs.getString(MainActivity.KEY_PORT_ADDR, "") ?: ""
        val portName = intent.getStringExtra(EXTRA_PORT_NAME) ?: prefs.getString(MainActivity.KEY_PORT_NAME, "") ?: "Port"
        val stbdAddr = intent.getStringExtra(EXTRA_STBD_ADDR) ?: prefs.getString(MainActivity.KEY_STBD_ADDR, "") ?: ""
        val stbdName = intent.getStringExtra(EXTRA_STBD_NAME) ?: prefs.getString(MainActivity.KEY_STBD_NAME, "") ?: "Stbd"
        val frontAddr = intent.getStringExtra(EXTRA_FRONT_ADDR) ?: prefs.getString(MainActivity.KEY_FRONT_ADDR, "") ?: ""
        val frontName = intent.getStringExtra(EXTRA_FRONT_NAME) ?: prefs.getString(MainActivity.KEY_FRONT_NAME, "") ?: "Front"

        binding.tvPortLabel.text = "\u2b05 $portName"
        binding.tvStbdLabel.text = "$stbdName \u27a1"

        portBle = AC6328BleManager(this)
        stbdBle = AC6328BleManager(this)
        frontBle = AC6328BleManager(this)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter

        if (portAddr.isNotEmpty()) {
            connectBleDevice(portBle, adapter.getRemoteDevice(portAddr), portName,
                onConnected    = { portConnected = true; runOnUiThread { updateConnUi(); vibrate(50); speak("Port connected") } },
                onDisconnected = { portConnected = false; runOnUiThread { updateConnUi(); speak("Port lost") } },
                onFeedback     = { data ->
                    if (data.batteryMv > 0) {
                        val p = portBle.battMvToPercent(data.batteryMv)
                        runOnUiThread { binding.tvPortBatt.text = "$p%" }
                    }
                }
            )
        }
        if (stbdAddr.isNotEmpty()) {
            connectBleDevice(stbdBle, adapter.getRemoteDevice(stbdAddr), stbdName,
                onConnected    = { stbdConnected = true; runOnUiThread { updateConnUi(); vibrate(50); speak("Starboard connected") } },
                onDisconnected = { stbdConnected = false; runOnUiThread { updateConnUi(); speak("Starboard lost") } },
                onFeedback     = { data ->
                    if (data.batteryMv > 0) {
                        val p = stbdBle.battMvToPercent(data.batteryMv)
                        runOnUiThread { binding.tvStbdBatt.text = "$p%" }
                    }
                }
            )
        }
        if (frontAddr.isNotEmpty()) {
            connectBleDevice(frontBle, adapter.getRemoteDevice(frontAddr), frontName,
                onConnected    = { frontConnected = true; runOnUiThread { updateConnUi(); vibrate(50); speak("Front connected") } },
                onDisconnected = { frontConnected = false; runOnUiThread { updateConnUi(); speak("Front lost") } },
                onFeedback     = { data ->
                    if (data.currentAmps > 0.05f) {
                        val watts = data.currentAmps * 48f
                        runOnUiThread {
                            binding.tvFrontCurrent.text = "%.1fA (%.0fW)".format(data.currentAmps, watts)
                        }
                    } else if (data.rawAe10.startsWith("V")) {
                        runOnUiThread { binding.tvFrontCurrent.text = "" }
                    }
                }
            )
        }

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

    private fun connectBleDevice(
        mgr: AC6328BleManager,
        device: BluetoothDevice,
        name: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onFeedback: ((AC6328BleManager.FeedbackData) -> Unit)? = null
    ) {
        mgr.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(d: BluetoothDevice) {}
            override fun onDeviceDisconnecting(d: BluetoothDevice) {}
            override fun onDeviceConnected(d: BluetoothDevice) { onConnected() }
            override fun onDeviceDisconnected(d: BluetoothDevice, reason: Int) { onDisconnected() }
            override fun onDeviceFailedToConnect(d: BluetoothDevice, reason: Int) { onDisconnected() }
            override fun onDeviceReady(d: BluetoothDevice) { mgr.applyMode() }
        })
        mgr.onFeedback = onFeedback
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
                MODE_DUAL   -> { portBle.applyMode(); stbdBle.applyMode(); frontBle.applyMode() }
            }
            safeStopAll()
            updateSliderRanges()
            updateThrustUi()
            speak(if (escMode) "ESC mode" else "BLDC mode")
        }
    }

    private fun AC6328BleManager.applyMode() {
        if (escMode) {
            setEscMode()
            armEsc() // Send initial arming signals
            handler.postDelayed({ armEsc() }, 500)   // Repeat arm signal for robustness
            handler.postDelayed({ armEsc() }, 1000)
        } else {
            setBldcMode()
        }
    }

    // ── Sync controls ─────────────────────────────────────────────────────────

    private fun setupSyncControls() {
        binding.btnSyncAll.setOnClickListener  { setSyncLevel(SYNC_ALL);  sendThrust(); updateThrustUi(); speak("Sync All") }
        binding.btnSyncSide.setOnClickListener { setSyncLevel(SYNC_SIDE); sendThrust(); updateThrustUi(); speak("Sync Side") }
        binding.btnSyncNone.setOnClickListener { setSyncLevel(SYNC_NONE); sendThrust(); updateThrustUi(); speak("Independent") }

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

        setupHoldButton(binding.btnFrontTrimUp)      { frontTrim += trimStep(); clampFrontTrim(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnFrontTrimDown)    { frontTrim -= trimStep(); clampFrontTrim(); sendThrust(); updateThrustUi() }

        binding.btnResetTrims.setOnClickListener {
            portSideTrim = 0; stbdSideTrim = 0; frontTrim = 0; portFRTrim = 0; stbdFRTrim = 0
            sendThrust(); updateThrustUi()
            speak("Trims reset")
        }

        // ── F/R trims (hold) ──
        setupHoldButton(binding.btnPortFRTrimUp)   { portFRTrim += trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnPortFRTrimDown) { portFRTrim -= trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdFRTrimUp)   { stbdFRTrim += trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }
        setupHoldButton(binding.btnStbdFRTrimDown) { stbdFRTrim -= trimStep(); clampFRTrims(); sendThrust(); updateThrustUi() }

        // ── SYNC_SIDE: port/stbd ▼▲ + slider ──
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

        // ── SYNC_NONE: M1–M6 ▼▲ + sliders ──
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
            MConfig(binding.btnM5Up, binding.btnM5Down, binding.seekM5,
                { m5Val }, { v -> m5Val = v.coerceIn(stopValue(), maxValue()); binding.seekM5.progress = (m5Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
            MConfig(binding.btnM6Up, binding.btnM6Down, binding.seekM6,
                { m6Val }, { v -> m6Val = v.coerceIn(stopValue(), maxValue()); binding.seekM6.progress = (m6Val - stopValue()).coerceAtLeast(0); sendThrust(); updateThrustUi() }),
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
        val af = computeAuxFront()
        when (level) {
            SYNC_SIDE -> { portSideVal = pf; stbdSideVal = sr }
            SYNC_NONE -> { 
                m1Val = computePortFront(); m2Val = computePortRear()
                m3Val = computeStbdFront(); m4Val = computeStbdRear()
                m5Val = computeAuxFront(); m6Val = computeAuxFront()
            }
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
            binding.seekM1, binding.seekM2, binding.seekM3, binding.seekM4, binding.seekM5, binding.seekM6)
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
    private fun computeAuxFront() = when (syncLevel) {
        SYNC_ALL  -> (masterVal + frontTrim).coerceIn(stopValue(), maxValue())
        SYNC_SIDE -> (((computePortFront() + computeStbdFront()) / 2) + frontTrim).coerceIn(stopValue(), maxValue())
        else      -> m5Val.coerceIn(stopValue(), maxValue())
    }

    // ── Send thrust ───────────────────────────────────────────────────────────

    private fun sendSingle(duty: Int) {
        val d = duty.coerceIn(stopValue(), maxValue())
        if (escMode) singleBle.sendEscPwm(d, d) else singleBle.sendBldc(d, d)
    }

    private fun sendThrust() {
        val m1 = computePortFront(); val m2 = computePortRear()
        val m3 = computeStbdFront(); val m4 = computeStbdRear()
        val m5 = computeAuxFront(); val m6 = computeAuxFront() // m6 follows m5 for now
        
        if (escMode) {
            if (portConnected) portBle.sendEscPwm(m1, m2)
            if (stbdConnected) stbdBle.sendEscPwm(m3, m4)
            if (frontConnected) frontBle.sendEscPwm(m5, m6)
        } else {
            if (portConnected) portBle.sendBldc(m1, m2)
            if (stbdConnected) stbdBle.sendBldc(m3, m4)
            if (frontConnected) frontBle.sendBldc(m5, m6)
        }
    }

    private fun safeStopAll() {
        repeat(3) {
            if (mode == MODE_SINGLE && ::singleBle.isInitialized) singleBle.stopMotors()
            if (mode == MODE_DUAL) {
                if (::portBle.isInitialized) portBle.stopMotors()
                if (::stbdBle.isInitialized) stbdBle.stopMotors()
                if (::frontBle.isInitialized) frontBle.stopMotors()
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
                binding.btnSingleStop.isEnabled = true
            }
            MODE_DUAL -> {
                binding.tvPortStatus.text = if (portConnected) "✅" else "⚠"
                binding.tvStbdStatus.text = if (stbdConnected) "✅" else "⚠"
                binding.tvFrontStatus.text = if (frontConnected) "FRONT ✅" else ""
                binding.tvPortStatus.setTextColor(if (portConnected) 0xFF66FF66.toInt() else 0xFFFF6666.toInt())
                binding.tvStbdStatus.setTextColor(if (stbdConnected) 0xFF66FF66.toInt() else 0xFFFF6666.toInt())
                binding.btnStop.isEnabled = true
            }
        }
    }

    /** Called after EVERY throttle state change — updates all status indicators. */
    private fun updateThrustUi() {
        if (mode == MODE_SINGLE) return

        val m1 = computePortFront(); val m2 = computePortRear()
        val m3 = computeStbdFront(); val m4 = computeStbdRear()
        val m5 = computeAuxFront(); val m6 = computeAuxFront()

        // Status bar (always visible) — % AND raw duty
        fun dutyLabel(v: Int) = if (escMode) "${v}u" else "${v}"
        binding.pbM1.progress   = nativeToPercent(m1); binding.tvM1Pct.text  = "${nativeToPercent(m1)}%"; binding.tvM1Duty.text = dutyLabel(m1)
        binding.pbM2.progress   = nativeToPercent(m2); binding.tvM2Pct.text  = "${nativeToPercent(m2)}%"; binding.tvM2Duty.text = dutyLabel(m2)
        binding.pbM3.progress   = nativeToPercent(m3); binding.tvM3Pct.text  = "${nativeToPercent(m3)}%"; binding.tvM3Duty.text = dutyLabel(m3)
        binding.pbM4.progress   = nativeToPercent(m4); binding.tvM4Pct.text  = "${nativeToPercent(m4)}%"; binding.tvM4Duty.text = dutyLabel(m4)
        
        // Optional M5/M6 display if present in layout
        try {
            binding.pbM5.progress = nativeToPercent(m5); binding.tvM5Pct.text = "${nativeToPercent(m5)}%"; binding.tvM5Duty.text = dutyLabel(m5)
            binding.pbM6.progress = nativeToPercent(m6); binding.tvM6Pct.text = "${nativeToPercent(m6)}%"; binding.tvM6Duty.text = dutyLabel(m6)
        } catch (e: Exception) {}

        // Master pct (SYNC_ALL)
        binding.tvMasterPct.text = "${nativeToPercent(masterVal)}%"

        // Side pct labels
        val lPct = nativeToPercent(computePortFront()); val rPct = nativeToPercent(computeStbdFront())
        binding.tvPortPct.text = "L $lPct%"
        binding.tvStbdPct.text = "R $rPct%"

        // Trim labels
        binding.tvPortSideTrimVal.text = fmtTrim(portSideTrim)
        binding.tvStbdSideTrimVal.text = fmtTrim(stbdSideTrim)
        binding.tvFrontTrimVal.text    = fmtTrim(frontTrim)
        binding.tvPortFRTrimVal.text   = fmtTrim(portFRTrim)
        binding.tvStbdFRTrimVal.text   = fmtTrim(stbdFRTrim)

        // Side pct in SYNC_SIDE panel
        binding.tvPortSidePct.text = "${nativeToPercent(m1)}%"
        binding.tvStbdSidePct.text = "${nativeToPercent(m3)}%"

        // Indep pcts
        binding.tvM1OnlyPct.text = "${nativeToPercent(m1)}%"
        binding.tvM2OnlyPct.text = "${nativeToPercent(m2)}%"
        binding.tvM3OnlyPct.text = "${nativeToPercent(m3)}%"
        binding.tvM4OnlyPct.text = "${nativeToPercent(m4)}%"
        try {
            binding.tvM5OnlyPct.text = "${nativeToPercent(m5)}%"
            binding.tvM6OnlyPct.text = "${nativeToPercent(m6)}%"
        } catch (e: Exception) {}
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
            speak("Emergency Stop")
            safeStopAll(); resetAllThrottle()
            binding.seekMaster.progress = 0
            binding.seekPort.progress = 0; binding.seekStbd.progress = 0
            binding.seekM1.progress = 0; binding.seekM2.progress = 0
            binding.seekM3.progress = 0; binding.seekM4.progress = 0
            try { binding.seekM5.progress = 0; binding.seekM6.progress = 0 } catch (e: Exception) {}
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
                    if (frontConnected) frontBle.readStatus()
                }
                handler.postDelayed(this, FEEDBACK_POLL_MS)
            }
        }
        handler.postDelayed(r, FEEDBACK_POLL_MS)
    }

    private fun scheduleCommandLoop() {
        val r = object : Runnable {
            override fun run() {
                if (mode == MODE_SINGLE && singleConnected) {
                    sendSingle(masterVal)
                } else if (mode == MODE_DUAL) {
                    if (portConnected || stbdConnected || frontConnected) {
                        sendThrust()
                    }
                }
                handler.postDelayed(this, COMMAND_LOOP_MS)
            }
        }
        handler.postDelayed(r, COMMAND_LOOP_MS)
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
    private fun clampFrontTrim() {
        frontTrim = frontTrim.coerceIn(-trimRange(), trimRange())
    }
    private fun clampFRTrims() {
        portFRTrim = portFRTrim.coerceIn(-trimRange(), trimRange())
        stbdFRTrim = stbdFRTrim.coerceIn(-trimRange(), trimRange())
    }

    private fun resetAllThrottle() {
        masterVal = 0; portSideTrim = 0; stbdSideTrim = 0; frontTrim = 0; portFRTrim = 0; stbdFRTrim = 0
        portSideVal = stopValue(); stbdSideVal = stopValue()
        m1Val = stopValue(); m2Val = stopValue(); m3Val = stopValue(); m4Val = stopValue()
        m5Val = stopValue(); m6Val = stopValue()
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
        const val EXTRA_FRONT_ADDR  = "front_addr"
        const val EXTRA_FRONT_NAME  = "front_name"

        const val MODE_SINGLE = "single"
        const val MODE_DUAL   = "dual"
        const val ROLE_NONE   = ""
        const val ROLE_PORT   = "port"
        const val ROLE_STBD   = "stbd"
        const val ROLE_FRONT  = "front"
        const val ROLE_REMOTE = "remote"

        const val SYNC_ALL  = "all"
        const val SYNC_SIDE = "side"
        const val SYNC_NONE = "none"
    }
}
