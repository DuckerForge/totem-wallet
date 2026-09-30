package com.clearsign.app

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.clearsign.core.Ndef
import com.clearsign.core.Type4Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Merchant side of a tap: the phone emulates an NFC tag holding a payment request. Android
 * passes raw APDUs from the other phone's reader to [Type4Tag], pure Kotlin under test, so
 * only the radio needs two devices. Nothing secret is reachable: the service holds one
 * `solana:` request string, armed only while the tap screen is open.
 */
class TapService : HostApduService() {

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        val tag = current ?: return Type4Tag.SW_NOT_FOUND
        return tag.process(apdu ?: return Type4Tag.SW_WRONG_LENGTH)
    }

    override fun onDeactivated(reason: Int) {
        current?.reset()
        if (reason == DEACTIVATION_LINK_LOSS) _tapped.value = System.currentTimeMillis()
    }

    companion object {
        private var current: Type4Tag? = null

        private val _request = MutableStateFlow<String?>(null)
        /** The request currently on the air, or null when the phone is emitting nothing. */
        val request: StateFlow<String?> get() = _request

        private val _tapped = MutableStateFlow(0L)
        /** Stamped when a reader finishes with us: the merchant's "it went through". */
        val tapped: StateFlow<Long> get() = _tapped

        /** True while this phone is pretending to be a tag, so nothing else drives the radio. */
        val armed: StateFlow<Boolean> get() = _armed
        private val _armed = MutableStateFlow(false)

        /** Start emitting [uri]. Only while the tap screen is open. */
        fun arm(uri: String) {
            current = Type4Tag(Ndef.uriMessage(uri))
            _request.value = uri
            _armed.value = true
        }

        fun disarm() {
            current = null
            _request.value = null
            _armed.value = false
        }

        /**
         * Claim the AID while the tap screen is in front. `D2760000850101` is the standard NDEF tag
         * application and X claims it too on this phone; with two services and no default, Android
         * won't pick us. `setPreferredService` routes it here only while this screen is up.
         */
        fun preferWhileVisible(ctx: Context, on: Boolean) {
            // Inside a sheet the context wraps the Activity, so `as? Activity` is null and the
            // other app gets the tap. Unwrap until the Activity.
            var c: Context? = ctx
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            val activity = c as? android.app.Activity ?: return
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(activity) ?: return
            val ce = runCatching { android.nfc.cardemulation.CardEmulation.getInstance(adapter) }.getOrNull() ?: return
            val me = android.content.ComponentName(activity, TapService::class.java)
            runCatching { if (on) ce.setPreferredService(activity, me) else ce.unsetPreferredService(activity) }
        }

        fun isSupported(ctx: Context): Boolean =
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    }
}
