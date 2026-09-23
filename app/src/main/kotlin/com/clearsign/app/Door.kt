package com.clearsign.app

/**
 * The door asks for the print every time you come back into the app. But the camera for a
 * scan, or the share sheet, is another activity over ours: ours stops, the door fired anyway,
 * and on return it asked for the finger for something never left. Whoever launches one of
 * those calls [hold] just before; the stop that follows is let through, once.
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
