package com.clearsign.core

/**
 * What a Token-2022 mint may do to you after you bought it. The old standard could only lie
 * before the purchase; extensions reach into your wallet afterwards, and one is the engine of
 * the largest automated scam on Solana in 2026: a permanent delegate can burn or move the token
 * out of your account forever, unasked, while the creator keeps the SOL from the pool. "Is
 * Token-2022" alone says nothing, PYUSD and EURC are built on it too; the extensions are in the
 * mint account, free to read, and this parses them. A second wallet does not help: the delegate burns it wherever it sits.
 */
data class MintExtensions(
    /** Someone can move or burn this token out of your account, for ever, unasked. */
    val permanentDelegate: Boolean = false,
    /** A program the creator chose runs on every transfer, and can make selling fail. */
    val transferHook: Boolean = false,
    /** A cut taken by the token itself on every transfer, in basis points. */
    val transferFeeBps: Int? = null,
    /** It cannot be transferred at all. Whatever it is, it is not tradeable. */
    val nonTransferable: Boolean = false,
    /** New accounts start frozen, so buyers can be stopped from selling by default. */
    val defaultFrozen: Boolean = false,
) {
    val any: Boolean get() = permanentDelegate || transferHook || nonTransferable || defaultFrozen || (transferFeeBps ?: 0) > 0

    companion object {
        val NONE = MintExtensions()
    }
}

/**
 * Read the extensions out of a raw Token-2022 mint account. Layout, worth writing down: base
 * mint 82 bytes, padding to 165 (a token account's length, so the two can be told apart), one
 * byte of account type at 165 (1 = mint), then TLV entries from 166: u16 LE type, u16 LE
 * length, that many bytes. Shorter than 166 is a plain SPL mint and returns
 * [MintExtensions.NONE]; so does garbage, because a parser that throws turns "could not read it" into a crash.
 */
fun readMintExtensions(data: ByteArray): MintExtensions {
    if (data.size <= 166 || data[165].toInt() != 1) return MintExtensions.NONE
    var permanentDelegate = false
    var transferHook = false
    var fee: Int? = null
    var nonTransferable = false
    var defaultFrozen = false

    fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    fun nonZero(at: Int, len: Int): Boolean {
        if (at + len > data.size) return false
        for (i in at until at + len) if (data[i].toInt() != 0) return true
        return false
    }

    var p = 166
    while (p + 4 <= data.size) {
        val type = u16(p)
        val len = u16(p + 2)
        val body = p + 4
        if (type == 0 || len < 0 || body + len > data.size) break
        when (type) {
            // TransferFeeConfig: two authorities, the withheld amount, then the old
            // and the new fee. The one that applies is the newer, last of the pair.
            1 -> if (len >= 108) fee = u16(body + 106)
            6 -> if (len >= 1 && data[body].toInt() == 2) defaultFrozen = true
            9 -> nonTransferable = true
            // An optional pubkey: present but all zeros means nobody holds it.
            12 -> if (nonZero(body, 32)) permanentDelegate = true
            // Authority first, then the hook program. A zero program is no hook.
            14 -> if (len >= 64 && nonZero(body + 32, 32)) transferHook = true
        }
        p = body + len
    }
    return MintExtensions(permanentDelegate, transferHook, fee, nonTransferable, defaultFrozen)
}
