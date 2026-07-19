package org.emerge.demo.cyto.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent **global preferences** — small `key=value` lines under [FILE] (`cyto-prefs.txt`), cross-world
 * like [CytoSaves] / [CytoGenomes]. Loaded lazily on first access and written back whenever a value changes.
 * Distinct from world tuning (which lives on the save): these are host/input choices the player sets once.
 */
object CytoPrefs {
    private val FILE: Path get() = CytoStorage.baseDir.resolve("cyto-prefs.txt")

    private val values: MutableMap<String, String> by lazy { load() }

    /** Left-click drives the camera: left-drag on empty space pans (and clears follow), so the player never
     *  needs the right button to move around. Default off = the classic right-button camera. */
    var leftClickCamera: Boolean
        get() = values["leftClickCamera"] == "true"
        set(v) { put("leftClickCamera", v.toString()) }

    private fun put(key: String, value: String) {
        if (values[key] == value) return
        values[key] = value
        save()
    }

    private fun load(): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        runCatching {
            if (Files.exists(FILE)) for (line in Files.readAllLines(FILE)) {
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        }
        return m
    }

    private fun save() {
        runCatching {
            Files.createDirectories(FILE.parent)
            Files.write(FILE, values.entries.map { "${it.key}=${it.value}" })
        }
    }
}
