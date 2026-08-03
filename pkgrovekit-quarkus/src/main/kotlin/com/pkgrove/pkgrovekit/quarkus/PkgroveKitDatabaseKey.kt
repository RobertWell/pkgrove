package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.jdbc.DatabaseKey

/**
 * A [DatabaseKey] for a database declared in Quarkus configuration
 * (`pkgrovekit.databases.<key>.*`).
 *
 * Unlike hand-written application keys (typically singleton `object`s, where
 * JVM identity equality is exactly right), config-declared keys are referenced
 * BY NAME from application code — the [PkgroveKitProducer]-built [Relay]
 * registers `PkgroveKitDatabaseKey("main")` and a transfer plan elsewhere must
 * be able to say `PkgroveKitDatabaseKey("main")` and mean the same registry
 * entry. Equality/hashCode are therefore VALUE-based on [DatabaseKey.keyName],
 * restricted to this class (a config key never equals an application `object`
 * key that happens to share a name).
 */
class PkgroveKitDatabaseKey(name: String) : DatabaseKey(name) {
    init {
        require(name.isNotBlank()) { "database key name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is PkgroveKitDatabaseKey && other.keyName == keyName

    override fun hashCode(): Int = keyName.hashCode()
}
