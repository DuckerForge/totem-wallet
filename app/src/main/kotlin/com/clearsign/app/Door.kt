package com.clearsign.app

/**
 * The lock asks for the fingerprint on every return to the app. The scan camera and the share
 * sheet also stop our activity, which would trigger it for nothing. Call [hold] right before
 * launching one: the next stop is let through, once.
 */
internal object Door {
    @Volatile private var heldAt = 0L

    fun hold() { heldAt = System.currentTimeMillis() }

    /** True if a stop arrived within three seconds of a [hold]; consumed on read. */
    fun consumeHold(): Boolean {
        val ok = System.currentTimeMillis() - heldAt < 3_000L
        heldAt = 0L
        return ok
    }
}
