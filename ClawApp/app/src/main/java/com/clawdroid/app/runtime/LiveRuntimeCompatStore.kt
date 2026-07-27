package com.clawdroid.app.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * Last App↔Runtime compatibility snapshot (updated after probe/status/version).
 */
object LiveRuntimeCompatStore {
    private val ref = AtomicReference(RuntimeCompatSnapshot())

    fun snapshot(): RuntimeCompatSnapshot = ref.get()

    fun update(snapshot: RuntimeCompatSnapshot) {
        ref.set(snapshot)
    }

    fun updateFrom(
        daemonVersion: String,
        protocolVersion: Int,
        actions: Collection<String>
    ): RuntimeCompatSnapshot {
        val next = RuntimeCompatSnapshot.evaluate(
            daemonVersion = daemonVersion,
            protocolVersion = protocolVersion,
            actions = actions
        )
        ref.set(next)
        return next
    }

    fun clear() {
        ref.set(RuntimeCompatSnapshot())
    }
}
