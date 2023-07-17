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

package no.nordicsemi.android.kotlin.ble.client.main.callback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.common.core.ApplicationScope
import no.nordicsemi.android.kotlin.ble.client.api.GattClientAPI
import no.nordicsemi.android.kotlin.ble.client.api.GattClientEvent
import no.nordicsemi.android.kotlin.ble.client.api.OnBondStateChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnConnectionStateChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnMtuChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnPhyRead
import no.nordicsemi.android.kotlin.ble.client.api.OnPhyUpdate
import no.nordicsemi.android.kotlin.ble.client.api.OnReadRemoteRssi
import no.nordicsemi.android.kotlin.ble.client.api.OnServiceChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnServicesDiscovered
import no.nordicsemi.android.kotlin.ble.client.api.ServiceEvent
import no.nordicsemi.android.kotlin.ble.client.main.errors.GattOperationException
import no.nordicsemi.android.kotlin.ble.client.main.service.BleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.client.main.service.BleGattDescriptor
import no.nordicsemi.android.kotlin.ble.client.main.service.BleGattServices
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectionStatus
import no.nordicsemi.android.kotlin.ble.core.data.BleGattOperationStatus
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPhy
import no.nordicsemi.android.kotlin.ble.core.data.BondState
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionStateWithStatus
import no.nordicsemi.android.kotlin.ble.core.data.PhyInfo
import no.nordicsemi.android.kotlin.ble.core.data.PhyOption
import no.nordicsemi.android.kotlin.ble.core.mutex.MutexWrapper
import no.nordicsemi.android.kotlin.ble.core.provider.MtuProvider
import no.nordicsemi.android.kotlin.ble.core.wrapper.IBluetoothGattService
import no.nordicsemi.android.kotlin.ble.logger.BlekLogger
import no.nordicsemi.android.kotlin.ble.logger.DefaultBlekLogger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A class for managing BLE connection. It propagates events ([GattClientEvent]) to it's
 * corresponding characteristics ([BleGattCharacteristic]) and descriptors ([BleGattDescriptor]).
 * Thanks to that values are getting updated.
 *
 * Despite that it's responsible for exposing connection parameters like mtu, phy, connection state
 * and request their changes.
 */
class BleGattClient(
    private val gatt: GattClientAPI,
    private val logger: BlekLogger,
    private val mutex: MutexWrapper = MutexWrapper(),
) {

    private val _connectionStateWithStatus = MutableStateFlow<GattConnectionStateWithStatus?>(null)

    /**
     * Returns last observed [GattConnectionState] with it's corresponding status [BleGattConnectionStatus].
     */
    val connectionStateWithStatus = _connectionStateWithStatus.asStateFlow()

    /**
     * Returns whether a device is connected.
     */
    val isConnected
        get() = connectionStateWithStatus.value?.state == GattConnectionState.STATE_CONNECTED

    private val mtuProvider = MtuProvider()

    /**
     * Established MTU size. There are incoming changes on Android 14 where this value is gonna to
     * be always max value - 517 and wouldn't be able to change.
     */
    val mtu = mtuProvider.mtu

    /**
     * Returns last [GattConnectionState] without it's status.
     */
    val connectionState = _connectionStateWithStatus.mapNotNull { it?.state }

    private val _services = MutableStateFlow<BleGattServices?>(null)

    /**
     * Returns [Flow] which emits services. Services can be outdated which results in emitting
     * [OnServiceChanged]. That's why usage of [Flow] may be handy.
     */
    val services = _services.asStateFlow()

    private val _bondState = MutableStateFlow<BondState?>(null)

    /**
     * Returns bond state of the server device.
     */
    val bondState = _bondState.asStateFlow()

    private var onConnectionStateChangedCallback: ((GattConnectionState, BleGattConnectionStatus) -> Unit)? =
        null
    private var mtuCallback: ((OnMtuChanged) -> Unit)? = null
    private var rssiCallback: ((OnReadRemoteRssi) -> Unit)? = null
    private var phyCallback: ((PhyInfo, BleGattOperationStatus) -> Unit)? = null
    private var bondStateCallback: ((BondState) -> Unit)? = null
    private var onServicesDiscovered: ((BleGattServices) -> Unit)? = null

    init {
        gatt.event.onEach {
            logger.log(Log.VERBOSE, "On gatt event: $it")
            when (it) {
                is OnConnectionStateChanged -> onConnectionStateChange(it.status, it.newState)
                is OnPhyRead -> onEvent(it)
                is OnPhyUpdate -> onEvent(it)
                is OnReadRemoteRssi -> onEvent(it)
                is OnServiceChanged -> onEvent(it)
                is OnServicesDiscovered -> onServicesDiscovered(it.services, it.status)
                is ServiceEvent -> _services.value?.apply { onCharacteristicEvent(it) }
                is OnMtuChanged -> onEvent(it)
                is OnBondStateChanged -> onBondStateChanged(it.bondState)
            }
        }.launchIn(ApplicationScope)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal suspend fun waitForConnection(): GattConnectionState {
        if (_connectionStateWithStatus.value?.state == GattConnectionState.STATE_CONNECTED) {
            return GattConnectionState.STATE_CONNECTED
        }
        //emulate connecting state as it is not emitted by Android
        _connectionStateWithStatus.value = GattConnectionStateWithStatus(
            GattConnectionState.STATE_CONNECTING,
            BleGattConnectionStatus.SUCCESS
        )

        mutex.lock()
        return suspendCoroutine { continuation ->
            onConnectionStateChangedCallback = { connectionState, _ ->
                if (connectionState == GattConnectionState.STATE_CONNECTED) {
                    logger.log(Log.INFO, "Device connected")
                    continuation.resume(GattConnectionState.STATE_CONNECTED)
                } else if (connectionState == GattConnectionState.STATE_DISCONNECTED) {
                    logger.log(Log.INFO, "Device disconnected")
                    continuation.resume(GattConnectionState.STATE_DISCONNECTED)
                }
                mutex.unlock()
                onConnectionStateChangedCallback = null
            }
        }
    }

    /**
     * Suspend function requesting new mtu size.
     *
     * @param mtu New mtu size.
     * @return mtu size after the request. It can be different that requested mtu.
     */
    suspend fun requestMtu(mtu: Int): Int {
        mutex.lock()
        return suspendCoroutine { continuation ->
            logger.log(Log.VERBOSE, "Requesting new mtu - start, mtu: $mtu")
            mtuCallback = { (mtu, status) ->
                if (status.isSuccess) {
                    logger.log(Log.INFO, "MTU: $mtu")
                    continuation.resume(mtu)
                } else {
                    logger.log(Log.ERROR, "Requesting mtu - error: $status")
                    continuation.resumeWithException(GattOperationException(status))
                }

                mtuCallback = null
                mutex.unlock()
            }
            gatt.requestMtu(mtu)
        }
    }

    /**
     * Suspend function reading server device's rssi.
     *
     * @return Rssi of the device.
     */
    suspend fun readRssi(): Int {
        mutex.lock()
        return suspendCoroutine { continuation ->
            logger.log(Log.DEBUG, "Reading rssi - start")
            rssiCallback = { (rssi, status) ->
                if (status.isSuccess) {
                    logger.log(Log.INFO, "RSSI: $rssi")
                    continuation.resume(rssi)
                } else {
                    logger.log(Log.ERROR, "Reading rssi - error: $status")
                    continuation.resumeWithException(GattOperationException(status))
                }

                rssiCallback = null
                mutex.unlock()
            }
            gatt.readRemoteRssi()
        }
    }

    /**
     * Sets preferred phy for the connection.
     *
     * @param txPhy Phy ([BleGattPhy]) of a transmitter.
     * @param rxPhy Phy ([BleGattPhy]) of a receiver.
     * @param phyOption Phy option ([PhyOption]).
     * @return PHY values set after the request. They may differ from requested values.
     */
    suspend fun setPhy(txPhy: BleGattPhy, rxPhy: BleGattPhy, phyOption: PhyOption): PhyInfo {
        mutex.lock()
        return suspendCoroutine { continuation ->
            logger.log(
                Log.DEBUG,
                "Setting phy - start, txPhy: $txPhy, rxPhy: $rxPhy, phyOption: $phyOption"
            )
            phyCallback = { phy, status ->
                if (status.isSuccess) {
                    logger.log(Log.INFO, "Tx phy: ${phy.txPhy}, rx phy: ${phy.rxPhy}")
                    continuation.resume(phy)
                } else {
                    logger.log(Log.ERROR, "Setting phy - error: $status")
                    continuation.resumeWithException(GattOperationException(status))
                }

                phyCallback = null
                mutex.unlock()
            }
            gatt.setPreferredPhy(txPhy, rxPhy, phyOption)
        }
    }

    /**
     * Disconnects current device.
     */
    fun disconnect() {
        //emulate disconnecting state as it is not emitted by Android
        _connectionStateWithStatus.value = GattConnectionStateWithStatus(
            GattConnectionState.STATE_DISCONNECTING,
            BleGattConnectionStatus.SUCCESS
        )
        logger.log(Log.INFO, "Disconnecting...")
        gatt.disconnect()
    }

    /**
     * Clears service cache.
     */
    fun clearServicesCache() {
        logger.log(Log.INFO, "Clearing service cache...")
        gatt.clearServicesCache()
    }

    @SuppressLint("MissingPermission")
    private fun onConnectionStateChange(
        status: BleGattConnectionStatus,
        connectionState: GattConnectionState,
    ) {
        logger.log(Log.DEBUG, "On connection state changed: $connectionState, status: $status")

        _connectionStateWithStatus.value = GattConnectionStateWithStatus(connectionState, status)
        onConnectionStateChangedCallback?.invoke(connectionState, status)

        if (connectionState == GattConnectionState.STATE_DISCONNECTED) {
            if (!status.isLinkLoss || !gatt.autoConnect) {
                gatt.close()
            }
        }
    }

    /**
     * Auxiliary function which waits for bonding. The bonding may be initiated in different
     * scenarios e.g. after connected or when reading from characteristic which is protected.
     *
     * This function is suppose to help waiting for bonding to be initiated in scenarios when
     * this is expected.
     *
     * @param timeInMillis Initial delay before bond state changes are started to be observed.
     */
    suspend fun waitForBonding(timeInMillis: Long = 2000) {
        mutex.lock()
        delay(timeInMillis)
        suspendCoroutine { continuation ->
            if (bondState.value != BondState.BONDING) {
                mutex.unlock()
                continuation.resume(Unit)
                return@suspendCoroutine
            } else {
                bondStateCallback = {
                    mutex.unlock()
                    bondStateCallback = null
                    continuation.resume(Unit)
                }
            }
        }
    }

    /**
     * Begins reliable write. All writes to a characteristics which supports this feature will be
     * transactional which means that they can be reverted in case of data inconsistency.
     */
    fun beginReliableWrite() {
        gatt.beginReliableWrite()
    }

    /**
     * Aborts reliable write. All writes to a characteristics which supports reliable writes will be
     * reverted to a state preceding call to [beginReliableWrite].
     */
    fun abortReliableWrite() {
        gatt.abortReliableWrite()
    }

    /**
     * Executes reliable write. All writes to a characteristics which supports reliable write will be
     * executed and new values will be set permanently.
     */
    fun executeReliableWrite() {
        gatt.executeReliableWrite()
    }

    suspend fun discoverServices(): BleGattServices {
        if (connectionStateWithStatus.value?.state != GattConnectionState.STATE_CONNECTED) {
            throw IllegalStateException("Device is not connected. Current state: ${connectionStateWithStatus.value?.state}")
        }

        mutex.lock()
        return suspendCoroutine { continuation ->
            onServicesDiscovered = {
                mutex.unlock()
                continuation.resume(it)
                onServicesDiscovered = null
            }
            gatt.discoverServices()
        }
    }

    private fun onServicesDiscovered(
        gattServices: List<IBluetoothGattService>,
        status: BleGattOperationStatus,
    ) {
        logger.log(Log.INFO, "Services discovered")
        logger.log(
            Log.DEBUG,
            "Discovered services: ${gattServices.map { it.uuid }}, status: $status"
        )
        val services = gattServices.let { BleGattServices(gatt, it, logger, mutex, mtuProvider) }
        _services.value = services
        onServicesDiscovered?.invoke(services)
    }

    private fun onBondStateChanged(bondState: BondState) {
        _bondState.value = bondState
        bondStateCallback?.invoke(bondState)
    }

    private fun onEvent(event: OnMtuChanged) {
        mtuProvider.updateMtu(event.mtu)
        mtuCallback?.invoke(event)
    }

    private fun onEvent(event: OnPhyRead) {
        phyCallback?.invoke(PhyInfo(event.txPhy, event.rxPhy), event.status)
    }

    private fun onEvent(event: OnPhyUpdate) {
        phyCallback?.invoke(PhyInfo(event.txPhy, event.rxPhy), event.status)
    }

    private fun onEvent(event: OnReadRemoteRssi) {
        rssiCallback?.invoke(event)
    }

    @SuppressLint("MissingPermission")
    private fun onEvent(event: OnServiceChanged) {
        mutex.tryLock()
        gatt.discoverServices()
    }

    companion object {

        /**
         * Connects to the specified device. Device is provided using mac address.
         *
         * @param context Application context.
         * @param macAddress MAC address of a device.
         * @param options Connection options.
         * @param logger Logger which is responsible for displaying logs from the BLE device.
         * @return [BleGattClient] with initiated connection based on [options] provided.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun connect(
            context: Context,
            macAddress: String,
            options: BleGattConnectOptions = BleGattConnectOptions(),
            logger: BlekLogger = DefaultBlekLogger(context),
        ): BleGattClient {
            logger.log(Log.INFO, "Connecting to $macAddress")
            return BleGattClientFactory.connect(context, macAddress, options, logger)
        }

        /**
         * Connects to the specified device. Device is provided using mac address.
         *
         * @param context Application context.
         * @param device A server device returned by scanner.
         * @param options Connection options.
         * @param logger Logger which is responsible for displaying logs from the BLE device.
         * @return [BleGattClient] with initiated connection based on [options] provided.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun connect(
            context: Context,
            device: ServerDevice,
            options: BleGattConnectOptions = BleGattConnectOptions(),
            logger: BlekLogger = DefaultBlekLogger(context),
        ): BleGattClient {
            logger.log(Log.INFO, "Connecting to ${device.address}")
            return BleGattClientFactory.connect(context, device, options, logger)
        }
    }
}
