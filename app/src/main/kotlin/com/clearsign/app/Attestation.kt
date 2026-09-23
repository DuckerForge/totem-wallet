package com.clearsign.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Attested receipts: every approved receipt is signed by a hardware-backed key belonging to
 * this app install, exportable evidence of what the wallet showed at the moment of signing,
 * verifiable by anyone with the app's public key. No extra biometric prompt: the key is the app's, not the user's.
 */
object Attestation {
    private const val TAG = "ClearSign-Attest"
    private const val ALIAS = "clearsign-attestation-p256"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(): Boolean = try {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            try { kpg.initialize(spec(true)); kpg.generateKeyPair() } catch (_: StrongBoxUnavailableException) { kpg.initialize(spec(false)); kpg.generateKeyPair() }
        }
        true
    } catch (e: Exception) { Log.w(TAG, "keystore unavailable: ${e.message}"); false }

    /** X.509 SubjectPublicKeyInfo, base64 — what a verifier needs. */
    fun publicKeyBase64(): String? = try {
        if (!ensureKey()) null else Base64.encodeToString(keyStore().getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    } catch (e: Exception) { null }

    /** ECDSA-SHA256 over the UTF-8 payload, DER, base64. */
    fun sign(payload: String): String? = try {
        if (!ensureKey()) null else {
            val key = (keyStore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
            val s = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(payload.toByteArray(Charsets.UTF_8)) }
            Base64.encodeToString(s.sign(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { Log.w(TAG, "sign failed: ${e.message}"); null }

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The statement being attested — stored verbatim, so what was signed is exactly what is kept. */
    fun statement(
        at: Long, dApp: String, host: String?, cluster: String?, payloadHashes: List<String>,
        outflows: List<String>, inflows: List<String>, risks: List<String>, signer: String, txSignature: String?,
    ): String = JSONObject()
        .put("v", 1).put("at", at).put("dApp", dApp).put("host", host ?: JSONObject.NULL).put("cluster", cluster ?: "mainnet-beta")
        .put("payloadHashes", JSONArray(payloadHashes)).put("outflows", JSONArray(outflows)).put("inflows", JSONArray(inflows))
        .put("risks", JSONArray(risks)).put("signer", signer).put("txSignature", txSignature ?: JSONObject.NULL)
        .toString()

    /** Everything a third party needs to verify one receipt. */
    fun exportBundle(statement: String, signature: String): String = JSONObject()
        .put("type", "clearsign-attested-receipt").put("alg", "ES256").put("publicKey", publicKeyBase64() ?: JSONObject.NULL)
        .put("statement", JSONObject(statement)).put("signature", signature)
        .toString(2)
}
