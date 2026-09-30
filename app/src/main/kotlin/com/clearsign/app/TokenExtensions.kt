package com.clearsign.app

import com.clearsign.core.MintExtensions
import com.clearsign.core.readMintExtensions
import java.util.concurrent.ConcurrentHashMap

/**
 * Token-2022 extensions read from the mint account: the powers a coin keeps over holders.
 * Jupiter's registry only says "Token-2022", which alone means little (PYUSD uses it). One
 * `getAccountInfo` per Token-2022 mint, cached for the process. Null means unreadable and is
 * graded as no extensions.
 */
object TokenExtensions {
    private val cache = ConcurrentHashMap<String, MintExtensions>()

    fun cached(mint: String): MintExtensions? = cache[mint]

    /** Blocking: call on IO. [token2022] false skips the call: a classic mint cannot carry extensions. */
    fun of(mint: String, token2022: Boolean): MintExtensions? {
        if (!token2022) return MintExtensions.NONE
        cache[mint]?.let { return it }
        val acc = runCatching { SolanaRpc.getAccountInfoRaw(SolanaRpc.urlFor(null), mint) }.getOrNull() ?: return null
        val ext = runCatching { readMintExtensions(acc.data) }.getOrNull() ?: return null
        cache[mint] = ext
        return ext
    }
}
