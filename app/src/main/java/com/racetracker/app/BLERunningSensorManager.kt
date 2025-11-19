package com.racetracker.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID
import kotlin.math.max

class BLERunningSensorManager(private val onRsc: (speedMps: Double, cadenceSpm: Double, strideLengthMeters: Double?, contactTimeMs: Double?) -> Unit) {
    private val RSC_SERVICE = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb") // Running Speed and Cadence
    private val RSC_CHARACTERISTIC = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null

    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(null, false, callback)
    }

    fun disconnect() {
        gatt?.close(); gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val svc = gatt?.getService(RSC_SERVICE)
            val char = svc?.getCharacteristic(RSC_CHARACTERISTIC)
            char?.let { gatt.setCharacteristicNotification(it, true) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic ?: return
            if (characteristic.uuid == RSC_CHARACTERISTIC) {
                val data = characteristic.value
                // Parse according to spec (flags -> instantaneous speed, cadence, stride length optional)
                val flags = data[0].toInt()
                var index = 1
                var instSpeed = 0.0
                var instCadence = 0.0
                var strideLength: Double? = null
                // Speed (uint16) in units of m/s with resolution 1/256
                val speedRaw = (data[index].toInt() and 0xff) or ((data[index+1].toInt() and 0xff) shl 8)
                index += 2
                instSpeed = speedRaw / 256.0
                // Cadence uint8 in steps per minute
                instCadence = (data[index].toInt() and 0xff).toDouble(); index += 1
                // if stride length present (flag bit 0x01?), spec says
                if ((flags and 0x01) != 0 && data.size >= index + 2) {
                    val sl = (data[index].toInt() and 0xff) or ((data[index+1].toInt() and 0xff) shl 8)
                    strideLength = sl / 100.0 // decimeters? verify vendor — many use centimeters or 1/100 meters
                    index += 2
                }
                // ground contact time not standard — vendor-specific; left as null if not present
                onRsc(instSpeed, instCadence, strideLength, null)
            }
        }
    }
}
