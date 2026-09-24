package com.clearsign.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Is a person here?": the platform biometric prompt, before the budget key signs something
 * the collar would not sign alone. A software key, so this is presence, not the Seed Vault's
 * hardware approval; the receipt says which pocket signs.
 */
object Presence {
    /**
     * What came back from the sheet. A print that did not match and a sheet the person closed
     * are not the same thing, and telling someone "not recognized" because they tapped away
     * is the app blaming them for its own prompt.
     */
    enum class Result { OK, DISMISSED, FAILED }

    suspend fun ask(activity: Activity, title: String, subtitle: String): Result = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        prompt.authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(Result.OK)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // What the platform reports when the person closed the sheet rather than
                // failing a print: the back gesture (10), the app cancelling (5) and the
                // sheet's own negative button (13). The numbers are the public contract;
                // the constants that name them are not on the SDK's classpath here.
                val dismissed = errorCode == 10 || errorCode == 5 || errorCode == 13
                if (cont.isActive) cont.resume(if (dismissed) Result.DISMISSED else Result.FAILED)
            }
        })
    }

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
