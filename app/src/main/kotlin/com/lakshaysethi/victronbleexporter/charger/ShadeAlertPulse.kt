package com.lakshaysethi.victronbleexporter.charger

/**
 * Issue #33: Grafana shade-free 500 W alert (or explicit {"action":"restart"})
 * should pulse the MPPT off then on.
 */
object ShadeAlertPulse {
    const val OFF_HOLD_MS = 4_000L
    const val COOLDOWN_MS = 10L * 60L * 1000L

    @Volatile
    private var lastClaimAt = 0L

    fun shouldRestart(body: String): Boolean {
        val t = body.lowercase()
        if (Regex("\"action\"\\s*:\\s*\"restart\"").containsMatchIn(t)) return true
        val firing = Regex("\"status\"\\s*:\\s*\"firing\"").containsMatchIn(t)
        if (!firing) return false
        return t.contains("shade-free-expect") || t.contains("solar power below 500")
    }

    fun tryClaim(nowMs: Long, cooldownMs: Long = COOLDOWN_MS): Boolean {
        synchronized(this) {
            if (nowMs - lastClaimAt < cooldownMs) return false
            lastClaimAt = nowMs
            return true
        }
    }

    fun resetForTest() {
        lastClaimAt = 0L
    }
}
