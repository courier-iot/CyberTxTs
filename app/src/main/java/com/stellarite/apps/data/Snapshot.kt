package com.stellarite.apps.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** One installed module as recorded in a snapshot. */
@Serializable
data class SnapModule(
    val repo: String,
    val pkg: String? = null,
    val version: String? = null,
) {
    val name: String get() = repo.substringAfter('/')
}

/** Everything one device records: its modules plus the settings worth carrying over. Never the Access Key. */
@Serializable
data class DeviceSnapshot(
    val name: String,
    val savedAt: Long,
    val modules: List<SnapModule>,
    val theme: String,
    val autoPatchOn: Boolean,
    val excluded: List<String>,
    val included: List<String>,
    /** Extra sources (and F-Droid picks), so a restore can bring the catalog back too. */
    val sources: List<Source> = emptyList(),
) {
    /** Ignores the timestamp so "nothing changed" can be detected. */
    fun fingerprint(): Int = copy(savedAt = 0L).hashCode()
}

/**
 * The file stored in the vault. Each device only ever rewrites its own section,
 * so two devices saving at different times never overwrite each other's data.
 */
@Serializable
data class Snapshot(
    val format: Int = 1,
    val devices: Map<String, DeviceSnapshot> = emptyMap(),
) {
    fun withDevice(id: String, device: DeviceSnapshot): Snapshot = copy(devices = devices + (id to device))

    fun toText(): String = json.encodeToString(this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Reads snapshot text; throws [IOException] with a readable message if it is not one. */
        fun parse(text: String): Snapshot {
            val s = try {
                json.decodeFromString<Snapshot>(text)
            } catch (e: IllegalArgumentException) {
                throw IOException("That is not a Stellarite snapshot.")
            }
            if (s.format > 1) throw IOException("This snapshot was made by a newer version of Stellarite Apps.")
            return s
        }
    }
}

/** How a recorded module compares with what is on this device. */
enum class ModStatus { MISSING, INSTALLED, DIFFERENT }

fun statusOf(recorded: SnapModule, installed: String?): ModStatus = when {
    installed == null -> ModStatus.MISSING
    recorded.version != null && Installer.sameVersion(installed, recorded.version) -> ModStatus.INSTALLED
    else -> ModStatus.DIFFERENT
}
