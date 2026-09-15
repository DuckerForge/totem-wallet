package com.clearsign.app

import com.clearsign.core.MintExtensions
import com.clearsign.core.readMintExtensions
import java.util.concurrent.ConcurrentHashMap

/**
 * The powers a Token-2022 coin keeps over you after you own it, read from the
 * mint account on chain.
 *
 * Jupiter's registry answers "is it Token-2022" and stops there, and that is the
 * one question whose answer does not matter: the standard is also what PYUSD is
 * built on. What matters is which extensions the mint actually carries, and that
 * is only in the account itself. One `getAccountInfo`, cached for the life of
 * the process, and only for mints the registry already says are Token-2022, so
 * the ordinary coin costs nothing.
 *
 * Null means we could not read it, and null is never an accusation: the same
 * rule the sellability check follows. A coin we could not ask about is graded as
 * if it had no extensions, exactly as it was before this existed.
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
