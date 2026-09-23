package com.clearsign.core

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A contact handed over by touch. One phone offers its address, a name, and a signature over
 * both with its attestation key; the other checks the signature against the key inside and
 * saves the contact as verified by touch. It proves the phone physically here declared this
 * address as its own, not who holds the phone.
 * `apex-contact:<address>?n=<name>&t=<millis>&k=<key b64url>&s=<sig b64url>`
 */
object ContactTap {
    const val SCHEME = "apex-contact:"

    data class Card(val address: String, val name: String, val at: Long, val keyB64Url: String, val sigB64Url: String)

    /** The bytes that get signed. */
    fun payload(address: String, name: String, at: Long): String = "$address|$name|$at"

    fun encode(address: String, name: String, at: Long, keyDer: ByteArray, sigDer: ByteArray): String {
        val u = Base64.getUrlEncoder().withoutPadding()
        return SCHEME + address + "?n=" + enc(name) + "&t=" + at + "&k=" + u.encodeToString(keyDer) + "&s=" + u.encodeToString(sigDer)
    }

    fun parse(uri: String): Card? {
        val t = uri.trim()
        if (!t.startsWith(SCHEME)) return null
        val body = t.removePrefix(SCHEME)
        val address = body.substringBefore('?')
        if (address.length !in 32..44) return null
        val q = body.substringAfter('?', "").split('&').mapNotNull { p -> p.substringBefore('=').takeIf { it.isNotEmpty() }?.let { it to dec(p.substringAfter('=', "")) } }.toMap()
        val name = q["n"] ?: return null
        val at = q["t"]?.toLongOrNull() ?: return null
        val k = q["k"] ?: return null
        val s = q["s"] ?: return null
        return Card(address, name, at, k, s)
    }

    /** True when the signature inside the card holds for the address and name inside the card. */
    fun verify(c: Card): Boolean = runCatching {
        val u = Base64.getUrlDecoder()
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(u.decode(c.keyB64Url)))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload(c.address, c.name, c.at).toByteArray(Charsets.UTF_8)) }.verify(u.decode(c.sigB64Url))
    }.getOrDefault(false)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
