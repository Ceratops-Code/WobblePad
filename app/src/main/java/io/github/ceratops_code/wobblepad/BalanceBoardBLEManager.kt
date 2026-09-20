package io.github.ceratops_code.wobblepad

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

/** Nordic owns the GATT queue, discovery and CCCD write. Subscribe once per connection. */
class BalanceBoardBLEManager(context: Context, private val packet: (ByteArray) -> Unit,
    private val battery: (Int) -> Unit, private val failure: (String) -> Unit) : BleManager(context) {
    private var stream: BluetoothGattCharacteristic? = null
    private var level: BluetoothGattCharacteristic? = null
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        stream = gatt.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
        level = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
            ?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))
        return stream?.properties?.and(BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && stream != null
    }
    override fun initialize() {
        setNotificationCallback(stream).with { _, data -> data.value?.let { packet(it.copyOf()) } }
        enableNotifications(stream).timeout(8000).fail { _, status -> failure("Could not enable board notifications ($status).") }.enqueue()
        level?.let { value -> readCharacteristic(value).with { _, data ->
            data.value?.firstOrNull()?.let { battery(it.toInt() and 255) }
        }.enqueue() }
    }
    override fun onServicesInvalidated() { stream = null; level = null }
    companion object {
        val SERVICE: UUID = UUID.fromString("856b152a-734a-5546-bf2b-ed4898184e12")
        val CHARACTERISTIC: UUID = UUID.fromString("856b152b-734a-5546-bf2b-ed4898184e12")
    }
}
