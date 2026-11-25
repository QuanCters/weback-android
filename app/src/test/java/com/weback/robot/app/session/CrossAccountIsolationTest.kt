package com.weback.robot.app.session

import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAccountIsolationTest {

    @Test
    fun testRapidAccountSwitchDiscardsStaleAccountInventoryAndSelection() = runTest {
        val time = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 10_000L, 1_700_000_000_000L) }
        val store = object : TokenStore {
            var rec: PersistedSessionRecord? = null
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                rec = record
                return TokenSaveResult.Success
            }
            override suspend fun load(): TokenLoadResult = rec?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                rec = null
                return TokenClearResult.Success
            }
        }
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult =
                AuthResult.Success(
                    SessionCredentials("jwt-${credentials.account}", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L)
                )
        }

        val accountAInventoryGate = CompletableDeferred<InventoryFetchResult>()
        val inventoryGateway = object : DeviceInventoryGateway {
            override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult {
                return if (session.jwtToken == "jwt-A") {
                    accountAInventoryGate.await()
                } else {
                    InventoryFetchResult.Success(
                        listOf(
                            DeviceInventoryItem(
                                thingName = "robot-B",
                                subType = "xs-x8-eu",
                                nickname = "B Robot",
                                isAdmin = true,
                                capability = DeviceCapabilityClassifier.classify("xs-x8-eu")
                            )
                        )
                    )
                }
            }
        }

        val coordinator = SessionCoordinator(authGateway, store, time)
        val repo = DeviceInventoryRepository(coordinator, inventoryGateway)

        // Sign in A and launch slow inventory fetch
        coordinator.signIn(AuthCredentials("+1", "A", "pw".toCharArray()))
        val fetchA = async { repo.refreshInventory() }
        testScheduler.advanceUntilIdle()

        // Switch quickly to Account B before Account A inventory responds
        coordinator.signIn(AuthCredentials("+1", "B", "pw".toCharArray()))
        repo.refreshInventory()

        val loadedBeforeLate = repo.inventoryState.value as InventoryState.Loaded
        assertEquals(listOf("robot-B"), loadedBeforeLate.items.map { it.thingName })
        assertNull(repo.selectedDevice.value)

        // Late Account A response arrives -> must be discarded cleanly
        accountAInventoryGate.complete(
            InventoryFetchResult.Success(
                listOf(
                    DeviceInventoryItem(
                        thingName = "robot-A",
                        subType = "xs-x8-eu",
                        nickname = "A Robot",
                        isAdmin = true,
                        capability = DeviceCapabilityClassifier.classify("xs-x8-eu")
                    )
                )
            )
        )
        fetchA.await()

        val loadedAfterLate = repo.inventoryState.value as InventoryState.Loaded
        assertEquals(listOf("robot-B"), loadedAfterLate.items.map { it.thingName })
    }
}
