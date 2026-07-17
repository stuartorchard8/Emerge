package org.emerge.demo.cyto.host

import java.nio.file.Path

/**
 * Where the host-shell stores persist ([CytoSaves], [CytoGenomes], [CampaignProgress]). Desktop leaves
 * the default (the process working directory, matching the old relative paths); Android points it at the
 * app's `filesDir` at startup, since a phone has no writable working directory.
 */
object CytoStorage {
    var baseDir: Path = Path.of(".")
}
