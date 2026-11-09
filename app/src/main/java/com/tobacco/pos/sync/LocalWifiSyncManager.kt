package com.tobacco.pos.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * مدیریت اتصال محلی خودکار بین گوشی‌های داخل مغازه دخانیات (بدون نیاز به اینترنت)
 * از طریق Wi-Fi و پروتکل mDNS/NSD
 */
class LocalWifiSyncManager(
    private val context: Context,
    private val conflictResolver: InventoryConflictResolver
) {
    private val tag = "WifiSync"
    private val serviceType = "_tobacco_pos._tcp."
    private val serviceName = "TobaccoPOS_${android.os.Build.MODEL}"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val discoveredPeers = mutableSetOf<String>()

    /**
     * راه‌اندازی سرور داخلی روی گوشی صندوقدار و ثبت در شبکه محلی
     */
    fun startLocalServer(port: Int = 8990) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                registerNsdService(port)
                Log.d(tag, "Local sync server running on port $port")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleIncomingClient(clientSocket)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in local server", e)
            }
        }
    }

    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@LocalWifiSyncManager.serviceName
            serviceType = this@LocalWifiSyncManager.serviceType
            setPort(port)
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(tag, "NSD Service registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "NSD Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            })
        } catch (e: Exception) {
            Log.e(tag, "Cannot register NSD", e)
        }
    }

    /**
     * جستجوی خودکار سایر گوشی‌های موجود در مغازه
     */
    fun startDiscovery() {
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName.contains("TobaccoPOS") && service.serviceName != serviceName) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host.hostAddress ?: return
                            val port = serviceInfo.port
                            val peerKey = "$host:$port"
                            if (discoveredPeers.add(peerKey)) {
                                Log.d(tag, "Connected to peer shop phone: $peerKey")
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        })
    }

    /**
     * دریافت پیام‌های فروش یا تغییر قیمت از گوشی‌های دیگر و اعمال سریع
     */
    private fun handleIncomingClient(socket: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine()
                if (line != null) {
                    // نمونه پیام فروش سریع: SOLD:productId:quantity
                    if (line.startsWith("SOLD:")) {
                        val parts = line.split(":")
                        if (parts.size >= 3) {
                            val productId = parts[1]
                            val qty = parts[2].toIntOrNull() ?: 1
                            conflictResolver.applySoldDelta(productId, qty)
                            Log.d(tag, "Applied sync delta sold: $productId, -$qty")
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(tag, "Client handle error", e)
            }
        }
    }

    /**
     * انتشار رویداد فروش به تمامی گوشی‌های همگام شده در مغازه
     */
    fun broadcastSaleEvent(productId: String, quantity: Int) {
        scope.launch {
            for (peer in discoveredPeers) {
                try {
                    val parts = peer.split(":")
                    val host = parts[0]
                    val port = parts[1].toInt()
                    val socket = Socket(host, port)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println("SOLD:$productId:$quantity")
                    socket.close()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to broadcast to $peer", e)
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            scope.cancel()
        } catch (_: Exception) {}
    }
}
