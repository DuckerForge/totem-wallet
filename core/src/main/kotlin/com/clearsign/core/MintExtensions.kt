package com.clearsign.core

/**
 * What a Token-2022 mint is allowed to do to you after you have bought it.
 *
 * The old standard could only lie to you before the purchase: mint more, freeze
 * your balance, or have no way out. Token extensions added powers that reach
 * into your wallet **afterwards**, and one of them is the engine behind the
 * largest automated scam on Solana in 2026: a **permanent delegate** stays
 * delegate over that token for ever and can burn or move it out of your account
 * with no further approval from you. Buy, wait, and the balance is zero while
 * the creator keeps the SOL from the pool.
 *
 * Knowing a coin "is Token-2022" is not enough to see any of this, and that was
 * all the registry told us: a single flag, worth a mild penalty, on a standard
 * that is also what PYUSD and EURC are built on. The extensions are in the mint
 * account itself, free to read, and this is the parser for them.
 *
 * A second wallet does not help against these. The coin sits in whatever wallet
 * you put it in, and the delegate burns it there.
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
 * Read the extensions out of a raw Token-2022 mint account.
 *
 * Layout, which is not guessable and worth writing down: the base mint is 82
 * bytes, then padding up to 165 (the length of a token *account*, so the two can
 * be told apart), then one byte of account type at 165 (1 = mint), then a
 * sequence of TLV entries from 166: little-endian u16 type, little-endian u16
 * length, then that many bytes.
 *
 * Anything shorter than 166 bytes is a plain SPL mint with no extensions, which
 * is the overwhelming majority and returns [MintExtensions.NONE]. Garbage in
 * returns NONE too: this feeds a safety score, and a parser that throws on a
 * malformed account would turn "we could not read it" into a crash.
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
