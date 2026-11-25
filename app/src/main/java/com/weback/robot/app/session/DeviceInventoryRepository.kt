package com.weback.robot.app.session

import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns session-scoped inventory cache and selected device identity (`SEC-304`, `IC-302`).
 * Automatically wipes cached inventory and device selection on any `sessionEpoch` change.
 */
class DeviceInventoryRepository(
    private val sessionCoordinator: SessionCoordinator,
    private val inventoryGateway: DeviceInventoryGateway
) {
    private val lock = Any()

    private val _inventoryState = MutableStateFlow<InventoryState>(InventoryState.Idle)
    val inventoryState: StateFlow<InventoryState> = _inventoryState.asStateFlow()

    private val _selectedDevice = MutableStateFlow<DeviceInventoryItem?>(null)
    val selectedDevice: StateFlow<DeviceInventoryItem?> = _selectedDevice.asStateFlow()

    private var boundEpoch: Long = sessionCoordinator.currentEpoch()

    init {
        sessionCoordinator.registerOnEpochAdvanced { newEpoch ->
            synchronized(lock) {
                if (newEpoch > boundEpoch) {
                    boundEpoch = newEpoch
                    _inventoryState.value = InventoryState.Idle
                    _selectedDevice.value = null
                }
            }
        }
    }

    suspend fun refreshInventory(): InventoryState {
        val authorized = sessionCoordinator.authorizeActiveSessionForSend()
            ?: run {
                val active = sessionCoordinator.sessionState.value
                val epoch = sessionCoordinator.currentEpoch()
                val errState = InventoryState.Error(ConnectivityError.Unauthenticated, epoch)
                if (active is SessionState.Authenticated) {
                    sessionCoordinator.invalidateForEpoch(active.epoch, com.weback.robot.domain.model.AuthFailureReason.TokenExpiredOrRevoked)
                }
                return errState
            }

        val capturedEpoch = authorized.epoch
        synchronized(lock) {
            if (sessionCoordinator.currentEpoch() != capturedEpoch) {
                return _inventoryState.value
            }
            boundEpoch = capturedEpoch
            if (_inventoryState.value !is InventoryState.Loaded) {
                _inventoryState.value = InventoryState.Loading
            }
        }

        return when (val fetchResult = inventoryGateway.fetchThings(authorized.session)) {
            is InventoryFetchResult.Success -> synchronized(lock) {
                if (sessionCoordinator.currentEpoch() != capturedEpoch) {
                    // Discard stale Account A inventory response after logout or Account B switch
                    return@synchronized _inventoryState.value
                }
                val newState = if (fetchResult.items.isEmpty()) {
                    InventoryState.Empty
                } else {
                    InventoryState.Loaded(fetchResult.items, capturedEpoch)
                }
                _inventoryState.value = newState
                newState
            }
            is InventoryFetchResult.Failure -> {
                if (fetchResult.error == ConnectivityError.TokenExpired ||
                    fetchResult.error == ConnectivityError.AuthorizationDenied
                ) {
                    sessionCoordinator.invalidateForEpoch(
                        capturedEpoch,
                        com.weback.robot.domain.model.AuthFailureReason.TokenExpiredOrRevoked
                    )
                }
                synchronized(lock) {
                    if (sessionCoordinator.currentEpoch() != capturedEpoch) {
                        return@synchronized _inventoryState.value
                    }
                    val errState = InventoryState.Error(fetchResult.error, capturedEpoch)
                    val existingLoaded = _inventoryState.value as? InventoryState.Loaded
                    if (existingLoaded == null || existingLoaded.epoch != capturedEpoch) {
                        _inventoryState.value = errState
                    }
                    errState
                }
            }
        }
    }

    fun selectDevice(thingName: String, expectedEpoch: Long): DeviceInventoryItem? {
        synchronized(lock) {
            if (sessionCoordinator.currentEpoch() != expectedEpoch) return null
            val loaded = _inventoryState.value as? InventoryState.Loaded ?: return null
            if (loaded.epoch != expectedEpoch) return null
            val found = loaded.items.firstOrNull { it.thingName == thingName } ?: return null
            _selectedDevice.value = found
            return found
        }
    }
}
