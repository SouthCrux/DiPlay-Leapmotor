package com.shilapi.xcertplay.vehicle.leapmotor

import com.shilapi.xcertplay.airplay.UltraClimateBackend
import com.shilapi.xcertplay.airplay.UltraClimateSessionRegistry
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Provision only from measured OEM evidence for this exact firmware, never guessed UI defaults. */
class LeapmotorClimateVerification(
    val firmwareFingerprint: String,
    zoneMapping: Map<UltraClimateBackend.Zone, LeapmotorClimateSide>,
    stepsBySide: Map<LeapmotorClimateSide, Double>,
    val incrementEvidence: String,
) {
    val zoneMapping = zoneMapping.toMap()
    val stepsBySide = stepsBySide.toMap()
    init {
        require(firmwareFingerprint.isNotBlank() && incrementEvidence.isNotBlank())
        require(this.zoneMapping.keys == UltraClimateBackend.Zone.entries.toSet())
        require(this.zoneMapping.values.toSet() == LeapmotorClimateSide.entries.toSet())
        require(this.stepsBySide.keys == LeapmotorClimateSide.entries.toSet())
        require(this.stepsBySide.values.all { it.isFinite() && it > 0 })
    }
}

/**
 * A concrete, explicitly registered CarProperty route. No service acquisition or permission grant
 * occurs here. Opening is synchronous and must run on a session worker. Each session gets a fresh
 * manager owner; revocation closes it. A missing permission/verification/readback opens nothing.
 */
class LeapmotorClimateBackendFactory internal constructor(
    private val verification: LeapmotorClimateVerification,
    private val fingerprint: () -> String,
    private val authorized: () -> Boolean,
    private val openAccess: () -> OwnedAccess?,
    private val backendFactory: (LeapmotorClimateAccess, LeapmotorClimateVerification, () -> Boolean) -> LeapmotorUltraClimateBackend,
) : UltraClimateSessionRegistry.Factory {
    data class OwnedAccess(val access: LeapmotorClimateAccess, val owner: Closeable)

    constructor(
        verification: LeapmotorClimateVerification,
        fingerprint: () -> String,
        authorized: () -> Boolean,
        openAccess: () -> OwnedAccess?,
    ) : this(verification, fingerprint, authorized, openAccess, { access, verified, permission ->
        LeapmotorUltraClimateBackend(
            LeapmotorClimateController(access, enableCommands = true,
                verifiedStepCelsiusBySide = verified.stepsBySide), verified.zoneMapping, permission)
    })

    private fun permitted(): Boolean = runCatching {
        fingerprint() == verification.firmwareFingerprint && authorized()
    }.getOrDefault(false)

    override fun open(): UltraClimateSessionRegistry.OwnedBackend? {
        if (!permitted()) return null
        var access: OwnedAccess? = null
        var backend: LeapmotorUltraClimateBackend? = null
        val closed = AtomicBoolean()
        val owner = Closeable {
            if (closed.compareAndSet(false, true)) {
                try { backend?.close() } finally { access?.owner?.close() }
            }
        }
        var published = false
        try {
            access = openAccess() ?: return null
            if (!permitted()) return null
            val raw = access.access
            val guarded = object : LeapmotorClimateAccess {
                private fun checkPermission() {
                    if (!permitted()) throw SecurityException("Climate authorization unavailable")
                }
                override fun configurations(): List<LeapmotorClimateConfiguration> {
                    checkPermission(); return raw.configurations()
                }
                override fun readTarget(areaId: Int): CarPropertyReading? {
                    checkPermission(); return raw.readTarget(areaId)
                }
                override fun writeTarget(areaId: Int, celsius: Float) {
                    checkPermission(); raw.writeTarget(areaId, celsius)
                }
            }
            val opened = backendFactory(guarded, verification, ::permitted)
            backend = opened
            opened.refreshForOpening()
            if (!permitted() || opened.capabilities().isEmpty() || opened.snapshot() == null) return null
            opened.maintain { owner.close() }
            published = true
            return UltraClimateSessionRegistry.OwnedBackend(opened, owner)
        } catch (_: Exception) {
            return null
        } finally {
            if (!published) runCatching { owner.close() }
        }
    }
}
