package com.screenmirror.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenmirror.app.databinding.ActivityMainBinding

/**
 * 主界面 — 设备选择 + 投屏控制
 *
 * 功能：
 * 1. 请求屏幕录制 + 录音权限
 * 2. 使用 NSD + SSDP 发现局域网设备
 * 3. 手动输入目标设备 IP
 * 4. 启动/停止投屏
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_CAPTURE = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceDiscovery: DeviceDiscovery
    private lateinit var deviceAdapter: DeviceAdapter

    private var selectedDevice: Device? = null
    private var isStreaming = false
    private var pendingResultCode: Int = Activity.RESULT_CANCELED
    private var pendingData: Intent? = null

    // 权限请求 Launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScreenCapture()
        } else {
            Toast.makeText(this, "需要录音权限才能采集音频", Toast.LENGTH_LONG).show()
            // 即使没有录音权限，也允许纯视频投屏
            startScreenCapture()
        }
    }

    // 状态广播接收器
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
            val error = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR)
            when (status) {
                ScreenCaptureService.Status.STREAMING.name -> {
                    isStreaming = true
                    updateUI()
                    Toast.makeText(this@MainActivity, "投屏已开始", Toast.LENGTH_SHORT).show()
                }
                ScreenCaptureService.Status.ERROR.name,
                ScreenCaptureService.Status.IDLE.name -> {
                    isStreaming = false
                    updateUI()
                    error?.let {
                        Toast.makeText(this@MainActivity, "错误: $it", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceDiscovery = DeviceDiscovery(this)

        setupDeviceList()
        setupClickListeners()
        observeDevices()
        registerStatusReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceDiscovery.stopScan()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    // ─── 初始化 ─────────────────────────────────────────────────

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            selectedDevice = device
            updateUI()
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnToggle.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
    }

    private fun observeDevices() {
        deviceDiscovery.devices.observe(this) { devices ->
            deviceAdapter.submitList(devices)
            binding.tvStatus.text = if (devices.isEmpty() && deviceDiscovery.isScanning) {
                getString(R.string.status_scanning)
            } else if (devices.isEmpty()) {
                getString(R.string.no_devices_found)
            } else {
                getString(R.string.label_device_list)
            }
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(ScreenCaptureService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    // ─── 投屏控制 ───────────────────────────────────────────────

    private fun startStreaming() {
        // 确定目标设备
        val targetIp = getTargetIp()
        if (targetIp.isNullOrBlank()) {
            Toast.makeText(this, "请选择设备或手动输入 IP 地址", Toast.LENGTH_LONG).show()
            return
        }

        // 检查权限
        if (!hasPermissions()) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS.toTypedArray())
            return
        }

        startScreenCapture()
    }

    private fun startScreenCapture() {
        val targetIp = getTargetIp() ?: return
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_CODE_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val targetIp = getTargetIp() ?: return
                launchStreamService(targetIp, resultCode, data)
            } else {
                Toast.makeText(this, "需要屏幕录制权限才能投屏", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchStreamService(host: String, resultCode: Int, data: Intent) {
        deviceDiscovery.stopScan()

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_TARGET_HOST, host)
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.tvStatus.text = getString(R.string.status_connecting)
    }

    private fun stopStreaming() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        isStreaming = false
        updateUI()
        binding.tvStatus.text = getString(R.string.status_idle)
        deviceDiscovery.startScan()
    }

    private fun getTargetIp(): String? {
        // 优先使用选中的设备
        selectedDevice?.let { return it.ipAddress }
        // 其次使用手动输入的 IP
        val manualIp = binding.etIpAddress.text.toString().trim()
        if (manualIp.isNotBlank()) {
            deviceDiscovery.addManualDevice(manualIp)
            return manualIp
        }
        return null
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateUI() {
        if (isStreaming) {
            binding.btnToggle.text = getString(R.string.btn_stop_stream)
            binding.btnToggle.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            binding.tvStatus.text = getString(R.string.status_streaming)
            binding.etIpAddress.isEnabled = false
            binding.rvDevices.alpha = 0.5f
        } else {
            binding.btnToggle.text = getString(R.string.btn_start_stream)
            binding.btnToggle.setBackgroundColor(
                ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
            binding.etIpAddress.isEnabled = true
            binding.rvDevices.alpha = 1.0f
        }
    }
}

// ─── 设备列表适配器 ────────────────────────────────────────────

class DeviceAdapter(
    private val onItemClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var devices: List<Device> = emptyList()

    fun submitList(list: List<Device>) {
        devices = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(android.R.id.text1)
        private val detailsView: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(device: Device) {
            nameView.text = device.name
            detailsView.text = "${device.ipAddress}  ·  ${getSourceLabel(device.source)}"
            itemView.setOnClickListener { onItemClick(device) }
        }

        private fun getSourceLabel(source: DiscoverySource): String = when (source) {
            DiscoverySource.NSD -> "NSD"
            DiscoverySource.SSDP -> "SSDP"
            DiscoverySource.MANUAL -> "手动"
        }
    }
}
