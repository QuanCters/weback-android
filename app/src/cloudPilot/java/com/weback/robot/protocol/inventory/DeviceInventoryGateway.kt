package com.weback.robot.protocol.inventory

import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.SessionCredentials

sealed interface InventoryFetchResult {
    data class Success(val items: List<DeviceInventoryItem>) : InventoryFetchResult
    data class Failure(val error: ConnectivityError) : InventoryFetchResult
}

interface DeviceInventoryGateway {
    suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult
}
