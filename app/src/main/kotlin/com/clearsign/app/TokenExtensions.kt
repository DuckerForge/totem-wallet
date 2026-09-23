package com.clearsign.app

import com.clearsign.core.MintExtensions
import com.clearsign.core.readMintExtensions
import java.util.concurrent.ConcurrentHashMap

/**
 * The powers a Token-2022 coin keeps over you after you own it, read from the mint account.
 * Jupiter's registry only says "is it Token-2022", which does not matter, PYUSD is built on it
 * too; what matters is which extensions the mint carries, and that is only in the account. One
 * `getAccountInfo`, cached for the process, only for mints the registry already calls
 * Token-2022. Null means unreadable and is never an accusation: graded as if it had no extensions.
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
