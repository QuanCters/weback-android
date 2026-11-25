package com.weback.robot.protocol.inventory

import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials

/**
 * Deterministic offline fixture inventory provider returning sample Laser SLAM and excluded
 * devices for local integration verification without live network access.
 */
class OfflineFixtureInventoryGateway(
    private val fixtureItems: List<DeviceInventoryItem> = defaultFixtures()
) : DeviceInventoryGateway {

    override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult =
        InventoryFetchResult.Success(fixtureItems)

    companion object {
        fun defaultFixtures(): List<DeviceInventoryItem> = listOf(
            DeviceInventoryItem(
                thingName = "offline_laser_01",
                subType = "xs-x8-eu",
                nickname = "WeBack Simulator Robot",
                isAdmin = true,
                capability = DeviceCapabilityClassifier.classify("xs-x8-eu"),
                initialState = RobotState(
                    name = "WeBack Simulator Robot",
                    batteryPercentage = 100,
                    mode = RobotMode.DOCK,
                    isDustbinClean = true,
                    errorCode = 0
                )
            ),
            DeviceInventoryItem(
                thingName = "offline_plug_01",
                subType = "sock-pw-1",
                nickname = "Offline Unsupported Plug",
                isAdmin = true,
                capability = DeviceCapabilityClassifier.classify("sock-pw-1"),
                initialState = null
            )
        )
    }
}
