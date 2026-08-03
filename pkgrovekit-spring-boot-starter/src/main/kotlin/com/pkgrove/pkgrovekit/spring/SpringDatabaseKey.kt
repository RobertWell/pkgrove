package com.pkgrove.pkgrovekit.spring

import com.pkgrove.pkgrovekit.jdbc.DatabaseKey

/**
 * [DatabaseKey] with VALUE equality on [keyName]. Databases declared under
 * `pkgrovekit.databases.<key>` are registered by the auto-configuration, so
 * application code cannot share the registering instance by reference —
 * `SpringDatabaseKey("<key>")` constructed at a use site must address the same
 * registry entry. The base class's identity equality (correct for the
 * singleton-object keys hand-written apps use) would make every configured
 * database unreachable here.
 */
class SpringDatabaseKey(name: String) : DatabaseKey(name) {
    override fun equals(other: Any?): Boolean =
        other is SpringDatabaseKey && other.keyName == keyName

    override fun hashCode(): Int = keyName.hashCode()
}
