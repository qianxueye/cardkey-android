package cc.cardkey.protocol

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

object CardOperationGate {
    private val held = AtomicBoolean(false)
    private val needsRemoval = mutableSetOf<String>()

    fun tryAcquire(): Closeable? {
        if (!held.compareAndSet(false, true)) return null
        return object : Closeable {
            private val released = AtomicBoolean(false)
            override fun close() { if (released.compareAndSet(false, true)) held.set(false) }
        }
    }

    @Synchronized fun requireRemoval(reader: String) { needsRemoval += reader }
    @Synchronized fun isRemovalRequired(reader: String): Boolean = reader in needsRemoval
    @Synchronized fun observedRemoval(reader: String) { needsRemoval -= reader }
}
