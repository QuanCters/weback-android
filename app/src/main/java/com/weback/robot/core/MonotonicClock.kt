package com.weback.robot.core

/**
 * Pure Kotlin monotonic time provider for command lifecycle timestamps.
 */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}

/**
 * Default JVM monotonic clock backed by [System.nanoTime].
 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
