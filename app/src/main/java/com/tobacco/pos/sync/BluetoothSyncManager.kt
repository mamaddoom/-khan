package com.tobacco.pos.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID

/**
 * همگام‌سازی اضطراری با بلوتوث وقتی مودم وای‌فای خاموش یا قطع است
 */
class BluetoothSyncManager(
    private val conflictResolver: InventoryConflictResolver
) {
    private val tag = "BluetoothSync"
    private val appUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        scope.launch {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("TobaccoPOS", appUuid)
                Log.d(tag, "Bluetooth sync server listening...")

                while (isActive) {
                    val socket: BluetoothSocket = serverSocket?.accept() ?: break
                    handleBluetoothConnection(socket)
                }
            } catch (e: Exception) {
                Log.e(tag, "Bluetooth server error", e)
            }
        }
    }

    private fun handleBluetoothConnection(socket: BluetoothSocket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val message = reader.readLine()
                if (message != null && message.startsWith("SOLD:")) {
                    val parts = message.split(":")
                    if (parts.size >= 3) {
                        conflictResolver.applySoldDelta(parts[1], parts[2].toIntOrNull() ?: 1)
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(tag, "Bluetooth connection failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSaleToDevice(deviceAddress: String, productId: String, quantity: Int) {
        if (bluetoothAdapter == null) return
        scope.launch {
            try {
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                val socket = device.createRfcommSocketToServiceRecord(appUuid)
                socket.connect()
                val writer = PrintWriter(socket.outputStream, true)
                writer.println("SOLD:$productId:$quantity")
                socket.close()
                Log.d(tag, "Sent BT sync sold: $productId")
            } catch (e: Exception) {
                Log.e(tag, "Failed to send BT sync", e)
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
