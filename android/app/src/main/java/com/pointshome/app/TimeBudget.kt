package com.pointshome.app

/** Reserves a short slice before allowing use. Crashes cannot restore already reserved time. */
internal class TimeBudget(
    private val readMillis: () -> Long,
    private val adjustMillis: (Long) -> Long,
    private val clock: () -> Long,
) {
    private var until = 0L
    private var startedAt = 0L
    private var active = false
    @Synchronized
    fun availableMillis(): Long = if (active) (readMillis() + until - clock()).coerceAtLeast(0) else readMillis()
    @Synchronized
    fun resume(): Boolean {
        val now = clock()
        if (active && now < until) return true
        if (active && now > until) adjustMillis(-(now - until))
        active = false
        if (readMillis() <= 0) return false
        val slice = -adjustMillis(-5_000L)
        if (slice <= 0) return false
        until = now + slice
        startedAt = now
        active = true
        return true
    }
    @Synchronized
    fun pause(at: Long = clock()) {
        if (active) {
            val unused = until - at.coerceAtLeast(startedAt)
            active = false
            if (unused != 0L) adjustMillis(unused)
        }
    }
}
