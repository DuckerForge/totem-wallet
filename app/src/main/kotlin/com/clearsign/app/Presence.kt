package com.clearsign.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Is a person here?" — the platform biometric prompt, used before the envelope
 * key signs something the collar would not sign on its own. The envelope is a
 * software key, so this is presence, not the Seed Vault's hardware approval;
 * the receipt says which pocket is signing.
 */
object Presence {
    suspend fun confirm(activity: Activity, title: String, subtitle: String): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        prompt.authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
        })
    }
}
