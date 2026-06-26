package com.example.data.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple Circuit Breaker to handle scraper failures gracefully.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val resetTimeoutMillis: Long = 300_000 // 5 minutes
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    private var state = State.CLOSED

    fun canAttempt(): Boolean {
        synchronized(this) {
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime.get() >= resetTimeoutMillis) {
                    state = State.HALF_OPEN
                    return true
                }
                return false
            }
            return true
        }
    }

    fun recordSuccess() {
        synchronized(this) {
            failureCount.set(0)
            state = State.CLOSED
        }
    }

    fun recordFailure() {
        synchronized(this) {
            val count = failureCount.incrementAndGet()
            lastFailureTime.set(System.currentTimeMillis())
            if (count >= failureThreshold) {
                state = State.OPEN
            }
        }
    }

    fun getState(): State = state
}
