package dev.ziggle.imgui

import java.io.File

/**
 * Persists each editor panel's docked/floating choice across sessions (imgui's own `.ini` is disabled).
 * A tiny `id 0|1` text file under `~/.ziggle/ui/`; [Chrome] seeds a panel's initial pin state from here
 * and writes back through it whenever the user toggles the padlock. Writes are immediate (toggles are
 * rare — one per user click), so there's no dirty/flush dance.
 */
class PanelPrefs(private val file: File) {

    private val pinned = HashMap<String, Boolean>()

    init {
        runCatching { load() }
    }

    /** The saved pin state for [id], or null if never set. */
    operator fun get(id: String): Boolean? = pinned[id]

    /** Record [id]'s pin state and persist. */
    operator fun set(id: String, value: Boolean) {
        pinned[id] = value
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(pinned.entries.joinToString("\n") { (k, v) -> "$k ${if (v) 1 else 0}" })
        }
    }

    private fun load() {
        if (!file.exists()) return
        file.readLines().forEach { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { pinned[parts[0]] = it != 0 }
        }
    }
}
