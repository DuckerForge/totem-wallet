package com.clearsign.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * Writing a payment request onto a real NFC sticker.
 *
 * A tag costs a few cents and any phone reads it: Apex, Phantom, Solflare, or
 * a browser when the request is the web form. The shop sticks it to the
 * counter, the tip jar, the van; the customer touches it and the payment
 * opens. The Seeker writes it here, once, and can lock it so nobody rewrites
 * it with their own address later: that is the sticker version of address
 * poisoning, and the lock is the answer.
 *
 * Reader mode is one per activity, so while this is on the ordinary reader
 * (the one that opens Send from a tag) is off; [stop] hands the radio back.
 */
object NfcWriter {
    sealed class Outcome {
        object Written : Outcome()
        object Locked : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    fun isAvailable(ctx: Context): Boolean = NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true

    /** Start listening; the first tag that comes gets [uri], locked afterwards when [lock]. */
    fun start(ctx: Context, uri: String, lock: Boolean, onOutcome: (Outcome) -> Unit) {
        val activity = activityOf(ctx) ?: return
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        adapter.enableReaderMode(
            activity,
            { tag -> onOutcome(write(tag, uri, lock)) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun stop(ctx: Context) {
        val activity = activityOf(ctx) ?: return
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
        (activity as? MainActivity)?.resumeReader()
    }

    private fun write(tag: Tag, uri: String, lock: Boolean): Outcome {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        Ndef.get(tag)?.let { ndef ->
            return runCatching {
                ndef.connect()
                if (!ndef.isWritable) return Outcome.Failed("read only")
                if (ndef.maxSize < msg.toByteArray().size) return Outcome.Failed("too small: " + ndef.maxSize + " bytes")
                ndef.writeNdefMessage(msg)
                val locked = lock && ndef.canMakeReadOnly() && ndef.makeReadOnly()
                runCatching { ndef.close() }
                if (locked) Outcome.Locked else Outcome.Written
            }.getOrElse { Outcome.Failed(it.message ?: "write failed") }
        }
        NdefFormatable.get(tag)?.let { f ->
            return runCatching {
                f.connect()
                if (lock) f.formatReadOnly(msg) else f.format(msg)
                runCatching { f.close() }
                if (lock) Outcome.Locked else Outcome.Written
            }.getOrElse { Outcome.Failed(it.message ?: "format failed") }
        }
        return Outcome.Failed("not an NDEF tag")
    }

    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
