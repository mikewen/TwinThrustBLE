package com.twinthrustble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twinthrustble.databinding.ActivityMainBinding
import com.twinthrustble.databinding.ItemDeviceBinding

/**
 * TwinThrustBLE — MainActivity
 *
 * Scan filter:
 *   • Default: only show devices whose name contains "ESC_PWM", "BLDC_PWM", or "AC6328"
 *   • "Show all" chip disables the name filter
 *   • Already-assigned MAC addresses are always hidden — no point re-connecting them here
 *
 * Flow: tap device → ControlActivity (SINGLE) → assign Port or Stbd → back → repeat
 * When both saved: "CONNECT BOTH & LAUNCH" → ControlActivity (DUAL)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // All raw scan results (unfiltered)
    private val allDevices = mutableListOf<BleDeviceItem>()
    private val seenAddresses = mutableSetOf<String>()

    // Filtered list shown in RecyclerView
    private val visibleDevices = mutableListOf<BleDeviceItem>()
    private lateinit var adapter: DeviceAdapter

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD_MS = 15_000L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startScan()
        else Toast.makeText(this, "Bluetooth & Location permissions required", Toast.LENGTH_LONG).show()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) checkPermissionsAndScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        adapter = DeviceAdapter(visibleDevices) { item -> connectSingle(item) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnScan.setOnClickListener {
            if (scanning) stopScan() else checkPermissionsAndScan()
        }
        binding.chipShowAll.setOnCheckedChangeListener { _, _ -> rebuildVisible() }

        binding.btnConnectBoth.setOnClickListener { connectBoth() }
        binding.btnClearPort.setOnClickListener {
            prefs.edit().remove(KEY_PORT_ADDR).remove(KEY_PORT_NAME).apply()
            updateSavedUi()
        }
        binding.btnClearStbd.setOnClickListener {
            prefs.edit().remove(KEY_STBD_ADDR).remove(KEY_STBD_NAME).apply()
            updateSavedUi()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSavedUi()   // refresh after returning from ControlActivity (new assignment may have been saved)
        rebuildVisible()  // hide newly assigned device from list
    }

    // ── Filter logic ──────────────────────────────────────────────────────────

    private fun isEscDevice(name: String) =
        name.contains("ESC_PWM",  ignoreCase = true) ||
                name.contains("BLDC_PWM", ignoreCase = true) ||
                name.contains("AC6328",   ignoreCase = true)

    private fun assignedAddresses(): Set<String> = buildSet {
        prefs.getString(KEY_PORT_ADDR, "")?.takeIf { it.isNotEmpty() }?.let { add(it) }
        prefs.getString(KEY_STBD_ADDR, "")?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }

    private fun rebuildVisible() {
        val assigned  = assignedAddresses()
        val showAll   = binding.chipShowAll.isChecked
        visibleDevices.clear()
        visibleDevices.addAll(
            allDevices.filter { d ->
                d.address !in assigned &&          // always hide already-assigned
                        (showAll || isEscDevice(d.name))   // name filter unless show-all
            }
        )
        adapter.notifyDataSetChanged()
    }

    // ── Saved assignments UI ──────────────────────────────────────────────────

    private fun updateSavedUi() {
        val portAddr = prefs.getString(KEY_PORT_ADDR, "") ?: ""
        val portName = prefs.getString(KEY_PORT_NAME, "") ?: ""
        val stbdAddr = prefs.getString(KEY_STBD_ADDR, "") ?: ""
        val stbdName = prefs.getString(KEY_STBD_NAME, "") ?: ""

        val portReady = portAddr.isNotEmpty()
        val stbdReady = stbdAddr.isNotEmpty()

        binding.tvPortSaved.text = if (portReady) "\u2b05 PORT\n$portName\n$portAddr"
        else "\u2b05 PORT\n(not assigned)"
        binding.tvStbdSaved.text = if (stbdReady) "STBD \u27a1\n$stbdName\n$stbdAddr"
        else "STBD \u27a1\n(not assigned)"
        binding.btnClearPort.visibility = if (portReady) View.VISIBLE else View.GONE
        binding.btnClearStbd.visibility = if (stbdReady) View.VISIBLE else View.GONE

        val bothReady = portReady && stbdReady
        binding.btnConnectBoth.isEnabled = bothReady
        binding.btnConnectBoth.alpha = if (bothReady) 1f else 0.4f
        binding.tvBothHint.text = when {
            bothReady  -> "Both modules assigned — tap Launch to run all 4 motors."
            portReady  -> "Starboard not assigned. Connect it below and assign."
            stbdReady  -> "Port not assigned. Connect it below and assign."
            else       -> "Scan, tap a device, then assign it as Port or Starboard."
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun connectSingle(item: BleDeviceItem) {
        stopScan()
        startActivity(Intent(this, ControlActivity::class.java).apply {
            putExtra(ControlActivity.EXTRA_MODE, ControlActivity.MODE_SINGLE)
            putExtra(ControlActivity.EXTRA_DEVICE, item.device)
            putExtra(ControlActivity.EXTRA_DEVICE_NAME, item.name)
        })
    }

    private fun connectBoth() {
        val portAddr = prefs.getString(KEY_PORT_ADDR, "") ?: ""
        val portName = prefs.getString(KEY_PORT_NAME, "") ?: ""
        val stbdAddr = prefs.getString(KEY_STBD_ADDR, "") ?: ""
        val stbdName = prefs.getString(KEY_STBD_NAME, "") ?: ""
        if (portAddr.isEmpty() || stbdAddr.isEmpty()) {
            Toast.makeText(this, "Assign both modules first", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent(this, ControlActivity::class.java).apply {
            putExtra(ControlActivity.EXTRA_MODE, ControlActivity.MODE_DUAL)
            putExtra(ControlActivity.EXTRA_PORT_ADDR, portAddr)
            putExtra(ControlActivity.EXTRA_PORT_NAME, portName)
            putExtra(ControlActivity.EXTRA_STBD_ADDR, stbdAddr)
            putExtra(ControlActivity.EXTRA_STBD_NAME, stbdName)
        })
    }

    // ── BLE Scan ──────────────────────────────────────────────────────────────

    private fun checkPermissionsAndScan() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            val bt = bluetoothAdapter
            if (bt == null || !bt.isEnabled) {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
            }
            startScan()
        } else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        allDevices.clear(); seenAddresses.clear(); visibleDevices.clear()
        adapter.notifyDataSetChanged()
        scanning = true
        binding.btnScan.text = "Stop Scan"
        binding.progressBar.visibility = View.VISIBLE
        bleScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        handler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)
        Log.d(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        bleScanner?.stopScan(scanCallback)
        binding.btnScan.text = "Scan for BLE Modules"
        binding.progressBar.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name    = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown"

            if (address in seenAddresses) {
                // Update RSSI in allDevices
                val idx = allDevices.indexOfFirst { it.address == address }
                if (idx >= 0) allDevices[idx] = allDevices[idx].copy(rssi = result.rssi)
                // Update in visibleDevices too if present
                val vi = visibleDevices.indexOfFirst { it.address == address }
                if (vi >= 0) { visibleDevices[vi] = visibleDevices[vi].copy(rssi = result.rssi); adapter.notifyItemChanged(vi) }
                return
            }
            seenAddresses.add(address)
            allDevices.add(BleDeviceItem(name, address, result.rssi, result.device))
            allDevices.sortByDescending { it.rssi }
            rebuildVisible()
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@MainActivity, "Scan failed ($errorCode)", Toast.LENGTH_SHORT).show()
            stopScan()
        }
    }

    override fun onPause() { super.onPause(); stopScan() }

    companion object {
        private const val TAG   = "TwinThrustBLE"
        const val PREFS_NAME    = "twinthrustble_config"
        const val KEY_PORT_ADDR = "port_addr"
        const val KEY_PORT_NAME = "port_name"
        const val KEY_STBD_ADDR = "stbd_addr"
        const val KEY_STBD_NAME = "stbd_name"
    }
}

// ── Data ──────────────────────────────────────────────────────────────────────

data class BleDeviceItem(
    val name: String, val address: String, val rssi: Int, val device: BluetoothDevice
)

// ── Adapter ───────────────────────────────────────────────────────────────────

class DeviceAdapter(
    private val items: List<BleDeviceItem>,
    private val onClick: (BleDeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvName.text    = item.name
        holder.binding.tvAddress.text = item.address
        holder.binding.tvRssi.text    = "${item.rssi} dBm"
        holder.binding.ivSignal.setImageLevel(when {
            item.rssi >= -60 -> 3; item.rssi >= -75 -> 2; item.rssi >= -85 -> 1; else -> 0
        })
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}