package com.weback.robot.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Authentication-specific monotonic clock backed by [SystemClock.elapsedRealtime] so
 * Android deep-sleep time counts strictly toward token expiry (`IC-301`).
 */
fun interface AuthMonotonicClock {
    fun elapsedRealtimeMs(): Long
}

/**
 * Production Android authentication monotonic clock backed by [SystemClock.elapsedRealtime].
 */
object AndroidAuthMonotonicClock : AuthMonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Wall-clock epoch provider used exclusively as a non-authoritative rollback/restore guard.
 */
fun interface WallClock {
    fun epochMs(): Long
}

object SystemWallClock : WallClock {
    override fun epochMs(): Long = System.currentTimeMillis()
}

data class BootTimeSnapshot(
    val bootGenerationId: String?,
    val elapsedRealtimeMs: Long,
    val wallEpochMs: Long
)

fun interface BootSessionTimeProvider {
    fun snapshot(): BootTimeSnapshot
}

/**
 * Captures current Android boot generation (`Settings.Global.BOOT_COUNT`), deep-sleep-inclusive
 * elapsed realtime, and wall epoch milliseconds. Returns null `bootGenerationId` when boot
 * identity cannot be safely verified (causing fail-closed reauthentication on restore).
 */
class AndroidBootSessionTimeProvider(
    context: Context,
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val wallClock: WallClock = SystemWallClock
) : BootSessionTimeProvider {
    private val appContext = context.applicationContext

    override fun snapshot(): BootTimeSnapshot {
        val bootId = try {
            val count = Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1
            )
            if (count >= 0) "boot_$count" else null
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
        return BootTimeSnapshot(
            bootGenerationId = bootId,
            elapsedRealtimeMs = authClock.elapsedRealtimeMs(),
            wallEpochMs = wallClock.epochMs()
        )
    }
}
