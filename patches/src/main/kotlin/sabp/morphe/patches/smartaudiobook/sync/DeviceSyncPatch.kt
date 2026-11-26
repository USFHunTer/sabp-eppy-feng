package sabp.morphe.patches.smartaudiobook.sync

import app.morphe.patcher.patch.resourcePatch
import sabp.morphe.patches.smartaudiobook.smartAudioBookCompatibility
import sabp.morphe.patches.smartaudiobook.ui.eppyUiPatch

/**
 * Turns on eppy's offer of the other device's position (docs/adr/0002): eppy reads SABP's own position.sabp.dat in the
 * book folder, which a folder-sync app brings from the other device. eppy itself sends nothing anywhere.
 */
@Suppress("unused")
val deviceSyncPatch = resourcePatch(
    name = "Sync between devices",
    description = "For a library folder kept the same on two devices by a folder-sync app such as Syncthing. When " +
        "you open a book that the other device played further, eppy offers to jump there. Bookmarks and character " +
        "notes already travel with the book folder. eppy itself sends nothing anywhere; see the README for setup.",
    default = false,
) {
    compatibleWith(smartAudioBookCompatibility)
    dependsOn(eppyUiPatch)

    execute {
        document("res/values/strings.xml").use { doc ->
            val el = doc.createElement("string")
            el.setAttribute("name", "eppy_device_sync")
            el.setAttribute("translatable", "false")
            el.textContent = "on"
            doc.documentElement.appendChild(el)
        }
    }
}
