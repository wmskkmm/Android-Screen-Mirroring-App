package com.screenmirror.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * 局域网设备发现 — 基于 Android NSD (Network Service Discovery) + SSDP
 */
data class Device(
    val name: String,
    val ipAddress: String,
    val port: Int = 5004,
    val source: DiscoverySource = DiscoverySource.NSD
)

enum class DiscoverySource { NSD, SSDP, MANUAL }

class DeviceDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val SSDP_MULTICAST_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val M_SEARCH_MSG = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
            append("\r\n")
        }
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _devices = MutableLiveData<List<Device>>(emptyList())
    val devices: LiveData<List<Device>> = _devices

    @Volatile
    var isScanning = false
        private set

    fun startScan() {
        if (isScanning) return
        isScanning = true
        _devices.postValue(emptyList())

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        startNsdDiscovery()
        startSsdpSearch()
        Log.i(TAG, "Device scanning started")
    }

    fun stopScan() {
        isScanning = false
        stopNsdDiscovery()
        Log.i(TAG, "Device scanning stopped")
    }

    fun addManualDevice(ip: String, name: String = "手动设备") {
        val device = Device(name = name, ipAddress = ip, source = DiscoverySource.MANUAL)
        val current = (_devices.value ?: emptyList()).toMutableList()
        if (current.none { it.ipAddress == ip }) {
            current.add(device)
            _devices.postValue(current)
        }
    }

    fun clear() {
        _devices.postValue(emptyList())
    }

    // ─── NSD 发现 ──────────────────────────────────────────────

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "NSD resolve failed: ${si.serviceName}, code=$errorCode")
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val host = resolvedInfo.host ?: return
                        val ip = host.hostAddress ?: return
                        val name = resolvedInfo.serviceName ?: "NSD设备"
                        addDevice(Device(name, ip, source = DiscoverySource.NSD))
                        Log.d(TAG, "NSD device found: $name @ $ip")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val addr = serviceInfo.host?.hostAddress ?: return
                val current = _devices.value ?: emptyList()
                _devices.postValue(current.filter { it.ipAddress != addr })
                Log.d(TAG, "NSD device lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery start failed: $serviceType, code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery stop failed: $serviceType, code=$errorCode")
            }
        }

        discoveryListener = listener
        nsdManager?.discoverServices("_services._dns-sd._udp",
            NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD discovery", e)
        }
        discoveryListener = null
    }

    // ─── SSDP 搜索 ─────────────────────────────────────────────

    private fun startSsdpSearch() {
        Thread {
            try {
                val socket = java.net.MulticastSocket()
                socket.reuseAddress = true
                val group = java.net.InetAddress.getByName(SSDP_MULTICAST_ADDR)
                socket.joinGroup(group)

                val msgBytes = M_SEARCH_MSG.toByteArray()
                val packet = java.net.DatagramPacket(msgBytes, msgBytes.size, group, SSDP_PORT)
                socket.send(packet)

                socket.soTimeout = 2000
                val buf = ByteArray(2048)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 2500) {
                    try {
                        val response = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        val responseText = String(response.data, 0, response.length)
                        val ip = response.address?.hostAddress ?: continue
                        val name = extractHeader(responseText, "SERVER")
                            ?: extractHeader(responseText, "USN")
                            ?: "SSDP设备"
                        addDevice(Device(name, ip, source = DiscoverySource.SSDP))
                        Log.d(TAG, "SSDP device found: $name @ $ip")
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                socket.leaveGroup(group)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "SSDP search error", e)
            }
        }.start()
    }

    private fun extractHeader(httpText: String, header: String): String? {
        val regex = Regex("^$header:\\s*(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        return regex.find(httpText)?.groupValues?.get(1)?.trim()
    }

    private fun addDevice(device: Device) {
        val current = (_devices.value ?: emptyList()).toMutableList()
        if (current.none { it.ipAddress == device.ipAddress }) {
            current.add(device)
            _devices.postValue(current)
        }
    }
}
