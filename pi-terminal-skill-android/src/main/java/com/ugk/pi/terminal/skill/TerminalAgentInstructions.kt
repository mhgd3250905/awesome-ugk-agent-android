package com.ugk.pi.terminal.skill

import android.content.Context
import java.nio.charset.StandardCharsets

/** Loads the SDK runtime Agent contract packaged with the terminal skill. */
internal object TerminalAgentInstructions {
    private const val ASSET_PATH = "ugk/AGENTS.md"

    fun load(context: Context): String {
        val instructions = context.assets.open(ASSET_PATH)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
            .trim()
        check(instructions.isNotEmpty()) { "Packaged runtime Agent AGENTS.md is empty" }
        return instructions
    }
}
