package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.MachineSettings

/**
 * Internal clipboard for machine settings.
 *
 * Pressing **C** on a machine copies its settings here. Pressing **V** on a machine pastes from
 * here. Only one machine's settings can be held at a time.
 */
object SettingsClipboard {

    /** The current contents, or null if nothing has been copied yet. */
    var contents: MachineSettings? = null

    /** Copy [settings] into the clipboard. */
    fun copy(settings: MachineSettings) {
        contents = settings
    }

    /** Clear the clipboard. */
    fun clear() {
        contents = null
    }
}
