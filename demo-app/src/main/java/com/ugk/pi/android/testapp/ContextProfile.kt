package com.ugk.pi.android.testapp

/**
 * A supported context-window profile and the session budget derived from it.
 */
data class ContextProfile(
    val stableId: String,
    val displayLabel: String,
    val tokenCapacity: Int,
    val sessionMaxMessages: Int,
    val sessionMaxChars: Int
) {
    companion object {
        const val DEFAULT_CONFIG: String = "200K"
        const val SAFE_FALLBACK: String = "128K"

        /** Profiles in ascending capacity order. */
        val supported: List<ContextProfile> = listOf(
            ContextProfile("32K", "32K", 32_768, 60, 8_000),
            ContextProfile("64K", "64K", 65_536, 100, 12_000),
            ContextProfile("128K", "128K", 131_072, 160, 20_000),
            ContextProfile("200K", "200K", 204_800, 220, 30_000),
            ContextProfile("1M", "1M", 1_048_576, 400, 50_000),
            ContextProfile("2M", "2M", 2_097_152, 800, 80_000)
        )

        /** Profiles in the order shown by the settings screen. */
        val uiOrdered: List<ContextProfile> = listOf(
            profileFor("64K"),
            profileFor("128K"),
            profileFor("200K"),
            profileFor("1M"),
            profileFor("2M"),
            profileFor("32K")
        )

        /** Returns the profile for a known value, or the safe 128K profile. */
        fun resolve(
            contextWindow: String?,
            fallback: ContextProfile = profileFor(SAFE_FALLBACK)
        ): ContextProfile {
            val normalized = contextWindow?.trim()?.uppercase().orEmpty()
            return supported.firstOrNull { profile ->
                normalized.startsWith(profile.stableId) ||
                    normalized.startsWith(profile.displayLabel.trim().uppercase())
            } ?: fallback
        }

        /** Keeps persisted unknown values intact while centralizing blank defaults. */
        fun configValueOrDefault(contextWindow: String?): String =
            contextWindow?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG

        private fun profileFor(stableId: String): ContextProfile =
            supported.first { it.stableId == stableId }
    }
}
