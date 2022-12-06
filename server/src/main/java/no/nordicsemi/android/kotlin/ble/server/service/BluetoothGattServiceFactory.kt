package no.nordicsemi.android.kotlin.ble.server.service

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConsts
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPermission
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty

internal object BluetoothGattServiceFactory {

    fun copy(service: BluetoothGattService): BluetoothGattService {
        return BluetoothGattService(service.uuid, service.type).apply {
            characteristics.forEach {
                val characteristic = BluetoothGattCharacteristic(it.uuid, it.properties, it.permissions)

                it.descriptors.forEach {
                    val descriptor = BluetoothGattDescriptor(it.uuid, it.permissions)

                    characteristic.addDescriptor(descriptor)
                }
                addCharacteristic(characteristic)
            }
        }
    }

    fun create(config: BleServerGattServiceConfig): BluetoothGattService {
        val service = BluetoothGattService(config.uuid, config.type.toNative())

        config.characteristicConfigs.forEach {
            val characteristic = BluetoothGattCharacteristic(
                it.uuid,
                BleGattProperty.toInt(it.properties),
                BleGattPermission.toInt(it.permissions)
            )

            it.descriptorConfigs.forEach {
                val descriptor = BluetoothGattDescriptor(
                    it.uuid,
                    BleGattPermission.toInt(it.permissions)
                )
                characteristic.addDescriptor(descriptor)
            }

            if (it.hasNotifications) {
                val cccd = BluetoothGattDescriptor(
                    BleGattConsts.NOTIFICATION_DESCRIPTOR,
                    BleGattPermission.toInt(listOf(BleGattPermission.PERMISSION_READ_ENCRYPTED_MITM, BleGattPermission.PERMISSION_WRITE_ENCRYPTED_MITM))
                )

                cccd.value = byteArrayOf(0, 0)

                characteristic.addDescriptor(cccd)
            }

            service.addCharacteristic(characteristic)
        }

        return service
    }
}
