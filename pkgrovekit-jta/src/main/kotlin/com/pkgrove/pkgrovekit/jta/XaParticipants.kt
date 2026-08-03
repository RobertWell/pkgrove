package com.pkgrove.pkgrovekit.jta

import com.pkgrove.pkgrovekit.coordination.Participant
import com.pkgrove.pkgrovekit.coordination.ParticipantCapability
import com.pkgrove.pkgrovekit.coordination.ParticipantId
import javax.sql.XADataSource

/**
 * Registry of XA-capable participants (HEL-170). Registration REQUIRES a real
 * [XADataSource] — capability is proven by construction, never assumed: there
 * is no way to register a plain DataSource (or DuckDB) here, so a participant
 * that reaches [JtaCoordinator] is positively XA-capable.
 */
class XaParticipants private constructor(
    private val entries: Map<ParticipantId, XADataSource>,
) {
    class RegistrationException(message: String) : IllegalArgumentException(message)

    operator fun contains(id: ParticipantId): Boolean = id in entries

    internal fun dataSource(id: ParticipantId): XADataSource =
        entries[id] ?: throw RegistrationException("participant '$id' is not registered")

    /** The [Participant] declarations to place in a [com.pkgrove.pkgrovekit.coordination.CoordinationPlan]. */
    fun declarations(): List<Participant> =
        entries.keys.map { Participant(it, ParticipantCapability.XaCapable) }

    /** Declaration for one registered participant. */
    fun declaration(id: ParticipantId): Participant {
        dataSource(id) // existence check
        return Participant(id, ParticipantCapability.XaCapable)
    }

    class Builder internal constructor() {
        private val entries = LinkedHashMap<ParticipantId, XADataSource>()

        fun register(id: ParticipantId, xaDataSource: XADataSource): Builder {
            if (id in entries) throw RegistrationException("participant '$id' registered twice")
            entries[id] = xaDataSource
            return this
        }

        fun register(id: String, xaDataSource: XADataSource): Builder =
            register(ParticipantId(id), xaDataSource)

        internal fun build(): XaParticipants {
            if (entries.isEmpty()) throw RegistrationException("no participants registered")
            return XaParticipants(entries.toMap())
        }
    }

    companion object {
        fun build(block: Builder.() -> Unit): XaParticipants = Builder().apply(block).build()
    }
}
