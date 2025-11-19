package com.racetracker.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Basic BLE Heart Rate client. Scans + connects to a device (you may pre-select address).
 * Listens to HEART_RATE_MEASUREMENT characteristic UUID 00002a37-0000-1000-8000-00805f9b34fb.
 */
class BLEHeartRateManager(private val context: Context, private val onHeartRate: (hr: Int) -> Unit) {
    private val BT_HRM_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val BT_HRM_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }

    fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            gatt ?: return
            val svc: BluetoothGattService? = gatt.getService(BT_HRM_SERVICE)
            val char: BluetoothGattCharacteristic? = svc?.getCharacteristic(BT_HRM_MEASUREMENT)
            char?.let {
                gatt.setCharacteristicNotification(it, true)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic ?: return
            if (characteristic.uuid == BT_HRM_MEASUREMENT) {
                val data = characteristic.value ?: return
                // Parse according to BLE spec
                val flags = data[0].toInt()
                val hrFormatUint16 = (flags and 0x01) != 0
                val hr = if (hrFormatUint16 && data.size >= 3) {
                    ((data[2].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                } else {
                    data[1].toInt() and 0xff
                }
                onHeartRate(hr)
            }
        }
    }
}
