/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.kotlin.ble.server.main.service

import android.annotation.SuppressLint
import kotlinx.coroutines.flow.asSharedFlow
import no.nordicsemi.android.common.core.DataByteArray
import no.nordicsemi.android.kotlin.ble.core.ClientDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattOperationStatus
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPermission
import no.nordicsemi.android.kotlin.ble.core.data.BleGattProperty
import no.nordicsemi.android.kotlin.ble.core.event.ValueFlow
import no.nordicsemi.android.kotlin.ble.core.provider.MtuProvider
import no.nordicsemi.android.kotlin.ble.core.wrapper.IBluetoothGattCharacteristic
import no.nordicsemi.android.kotlin.ble.server.api.CharacteristicEvent
import no.nordicsemi.android.kotlin.ble.server.api.DescriptorEvent
import no.nordicsemi.android.kotlin.ble.server.api.GattServerAPI
import no.nordicsemi.android.kotlin.ble.server.api.OnCharacteristicReadRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnCharacteristicWriteRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnExecuteWrite
import no.nordicsemi.android.kotlin.ble.server.api.OnNotificationSent
import no.nordicsemi.android.kotlin.ble.server.api.ServiceEvent
import java.util.*

@SuppressLint("MissingPermission")
class BleServerGattCharacteristic internal constructor(
    private val server: GattServerAPI,
    private val device: ClientDevice,
    private val characteristic: IBluetoothGattCharacteristic,
    private val mtuProvider: MtuProvider
) {

    val uuid = characteristic.uuid
    val instanceId = characteristic.instanceId

    private var transactionalValue = DataByteArray()

    private val _value = ValueFlow.create()
    val value = _value.asSharedFlow()

    val permissions: List<BleGattPermission>
        get() = BleGattPermission.createPermissions(characteristic.permissions)

    val properties: List<BleGattProperty>
        get() = BleGattProperty.createProperties(characteristic.properties)

    private val descriptors = characteristic.descriptors.map {
        BleServerGattDescriptor(server, instanceId, it, mtuProvider)
    }

    fun findDescriptor(uuid: UUID): BleServerGattDescriptor? {
        return descriptors.firstOrNull { it.uuid == uuid }
    }

    fun setValue(value: DataByteArray) {
        // only notify once when the value changes
        //todo think about improving this
//        if (value.contentEquals(_value.value)) return
        _value.tryEmit(value)
        characteristic.value = value.value

        val isNotification = properties.contains(BleGattProperty.PROPERTY_NOTIFY)
        val isIndication = properties.contains(BleGattProperty.PROPERTY_INDICATE)

        if (isNotification || isIndication) {
            server.notifyCharacteristicChanged(device, characteristic, isIndication, value)
        }
    }

    internal fun onEvent(event: ServiceEvent) {
        when (event) {
            is CharacteristicEvent -> onCharacteristicEvent(event)
            is DescriptorEvent -> onDescriptorEvent(event)
            is OnExecuteWrite -> onExecuteWrite(event)
        }
    }

    private fun onDescriptorEvent(event: DescriptorEvent) {
        if (event.descriptor.characteristic == characteristic) {
            descriptors.forEach { it.onEvent(event) }
        }
    }

    private fun onCharacteristicEvent(event: CharacteristicEvent) {
        when (event) {
            is OnCharacteristicReadRequest -> onLocalEvent(event.characteristic) { onCharacteristicReadRequest(event) }
            is OnCharacteristicWriteRequest -> onLocalEvent(event.characteristic) { onCharacteristicWriteRequest(event) }
            is OnNotificationSent -> onNotificationSent(event)
        }
    }

    private fun onLocalEvent(eventCharacteristic: IBluetoothGattCharacteristic, block: () -> Unit) {
        if (eventCharacteristic.uuid == characteristic.uuid && eventCharacteristic.instanceId == characteristic.instanceId) {
            block()
        }
    }

    private fun onExecuteWrite(event: OnExecuteWrite) {
        descriptors.onEach { it.onExecuteWrite(event) }
        if (!event.execute) {
            transactionalValue
            return
        }
        _value.tryEmit(transactionalValue)
        transactionalValue = DataByteArray()
        server.sendResponse(event.device, event.requestId, BleGattOperationStatus.GATT_SUCCESS.value, 0, null)
    }

    private fun onNotificationSent(event: OnNotificationSent) {
    }

    private fun onCharacteristicWriteRequest(event: OnCharacteristicWriteRequest) {
        val value = event.value.copyOf()
        val status = BleGattOperationStatus.GATT_SUCCESS
        if (event.preparedWrite) {
            transactionalValue = value //todo maybe +=
        } else {
            _value.tryEmit(value)
        }
        if (event.responseNeeded) {
            server.sendResponse(
                event.device,
                event.requestId,
                status.value,
                event.offset,
                event.value
            )
        }
    }

    private fun onCharacteristicReadRequest(event: OnCharacteristicReadRequest) {
        val status = BleGattOperationStatus.GATT_SUCCESS
        val offset = event.offset
        val value = _value.value
        val data = value.getChunk(offset, mtuProvider.mtu.value)
        server.sendResponse(event.device, event.requestId, status.value, event.offset, data)
    }
}
