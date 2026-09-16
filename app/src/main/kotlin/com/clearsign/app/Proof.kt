package com.clearsign.app

import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A payment, proven on the spot.
 *
 * Every receipt this phone signs carries a statement (what left, what came
 * in, to whom, the chain signature) signed with the phone's own P‑256 key.
 * Packed into one line it fits a QR: the person who was paid points their
 * phone at it and sees the same numbers, checked against the key inside,
 * without waiting for an explorer. Format, one line, four parts:
 *
 *   apex-proof:1.<key b64url>.<statement b64url>.<signature b64url>
 *
 * The statement travels as the exact bytes that were signed, never
 * re-serialised, so the check is the check.
 */
object Proof {
    const val PREFIX = "apex-proof:1."

    /** A scanned proof waiting for its sheet. */
    val incoming = mutableStateOf<String?>(null)

    data class Verified(val statement: JSONObject, val signer: String, val txSignature: String?, val outflows: List<String>, val inflows: List<String>, val at: Long)

    fun encode(statementJson: String, signatureB64: String, publicKeyB64: String): String {
        val u = Base64.getUrlEncoder().withoutPadding()
        return PREFIX + u.encodeToString(Base64.getDecoder().decode(publicKeyB64)) + "." +
            u.encodeToString(statementJson.toByteArray(Charsets.UTF_8)) + "." +
            u.encodeToString(Base64.getDecoder().decode(signatureB64))
    }

    fun looksLike(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** Null when the text is not a proof or the signature does not hold. */
    fun verify(text: String): Verified? = runCatching {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val parts = t.removePrefix(PREFIX).split('.')
        if (parts.size != 3) return null
        val u = Base64.getUrlDecoder()
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(u.decode(parts[0])))
        val statement = u.decode(parts[1])
        val sig = u.decode(parts[2])
        if (!check(key, statement, sig)) return null
        val o = JSONObject(String(statement, Charsets.UTF_8))
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        Verified(o, o.optString("signer"), o.optString("txSignature").takeIf { it.isNotEmpty() && it != "null" }, list("outflows"), list("inflows"), o.optLong("at"))
    }.getOrNull()

    fun check(key: PublicKey, payload: ByteArray, signature: ByteArray): Boolean =
        runCatching { Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload) }.verify(signature) }.getOrDefault(false)
}
