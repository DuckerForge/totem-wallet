package com.clearsign.app

import com.clearsign.core.BalanceDelta
import com.clearsign.core.DecodedInstruction
import com.clearsign.core.InstructionKind
import com.clearsign.core.NATIVE_SOL_MINT

/**
 * A small, self-contained Solana transaction reader — just enough to build a
 * clear-signing receipt from the real bytes a dApp asks us to sign, and to
 * splice our Seed Vault signature back into the transaction.
 *
 * It parses both legacy and v0 wire formats. For v0, accounts that live in
 * Address Lookup Tables are not present in the message, so instructions that
 * reference them are reported with a null destination (resolving them needs an
 * RPC round-trip — a documented next step, alongside Helius simulation).
 */
object SolanaTx {

    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111"

    private const val U64_ALL_ONES = -1L // 0xFFFFFFFFFFFFFFFF read as signed Long

    data class Compiled(val programIdIndex: Int, val accounts: IntArray, val data: ByteArray)

    /** One v0 address-table lookup: which table, and which of its entries are loaded. */
    data class Lookup(val table: String, val writableIndexes: IntArray, val readonlyIndexes: IntArray)

    data class Decoded(
        val version: Int,            // -1 = legacy, 0 = v0
        val numRequiredSignatures: Int,
        val numReadonlySigned: Int,
        val numReadonlyUnsigned: Int,
        val signaturesArrayStart: Int, // always 0
        val messageStart: Int,       // offset where the message begins
        val staticAccountKeys: List<String>, // base58, only the in-message keys
        val instructions: List<Compiled>,
        val raw: ByteArray,
        val lookups: List<Lookup> = emptyList(), // v0 only; resolved via RPC (SolanaRpc.resolveLookups)
    ) {
        val feePayer: String? get() = staticAccountKeys.firstOrNull()

        /** Writable static accounts = total keys minus the read-only ones. */
        val writableAccounts: Int
            get() = (staticAccountKeys.size - numReadonlySigned - numReadonlyUnsigned).coerceAtLeast(0)

        /** Distinct program ids this message invokes, in first-seen order. */
        val programs: List<String>
            get() = instructions.mapNotNull { staticAccountKeys.getOrNull(it.programIdIndex) }.distinct()

        /**
         * Static keys that are *writable* — the only accounts whose SOL balance a
         * transfer can change, so the candidate set for "where the money goes".
         * Signers come first (writable = first `numRequiredSignatures - numReadonlySigned`),
         * then non-signers (writable until the last `numReadonlyUnsigned`).
         */
        val writableKeys: List<String>
            get() {
                val n = staticAccountKeys.size
                val writableSigned = (numRequiredSignatures - numReadonlySigned).coerceIn(0, n)
                val writableUnsignedEnd = (n - numReadonlyUnsigned).coerceIn(numRequiredSignatures, n)
                val out = ArrayList<String>()
                for (i in 0 until writableSigned) out.add(staticAccountKeys[i])
                for (i in numRequiredSignatures until writableUnsignedEnd) out.add(staticAccountKeys[i])
                return out
            }
    }

    /*
     * The message part of a serialized transaction: everything after the
     * signature array. This is what an ed25519 signature must cover — the Seed
     * Vault signs exactly the bytes it is handed, so it must be handed this.
     */
    /**
     * A transaction can declare at most this many signatures. The wire format
     * allows a much larger number, and `count * 64` on a large one overflows to
     * a negative offset that slips past a `<= size` check and blows up inside
     * the copy. The limit is not arbitrary: a Solana message cannot hold more
     * signers than it has accounts, and an account list is a single byte.
     */
    private const val MAX_SIGNATURES = 255

    fun messageBytes(tx: ByteArray): ByteArray {
        val r = Reader(tx)
        val count = r.shortVec()
        require(count in 0..MAX_SIGNATURES) { "signature count out of range" }
        val start = r.pos + count * 64
        require(start <= tx.size) { "truncated transaction" }
        return tx.copyOfRange(start, tx.size)
    }

    /**
     * The transaction's own first signature, base58, which is its id on chain.
     *
     * Worth having when a send gets no answer: the bytes were already signed, so
     * the id is knowable without the node telling us, and the chain can be asked
     * whether it landed.
     */
    fun firstSignature(signedTx: ByteArray): String? = runCatching {
        val r = Reader(signedTx)
        val count = r.shortVec()
        if (count < 1 || r.pos + 64 > signedTx.size) return null
        val sig = signedTx.copyOfRange(r.pos, r.pos + 64)
        if (sig.all { it == 0.toByte() }) null else Base58.encode(sig)
    }.getOrNull()

    /** Splice a 64-byte signature into the transaction's signature array. */
    fun attachSignature(tx: ByteArray, signerIndex: Int, signature: ByteArray): ByteArray {
        require(signature.size == 64) { "signature must be 64 bytes" }
        val r = Reader(tx)
        val count = r.shortVec()
        require(count in 0..MAX_SIGNATURES) { "signature count out of range" }
        val sigStart = r.pos
        require(signerIndex in 0 until count) { "signer index out of range" }
        val out = tx.copyOf()
        System.arraycopy(signature, 0, out, sigStart + signerIndex * 64, 64)
        return out
    }

    fun decode(tx: ByteArray): Decoded? = try {
        val r = Reader(tx)
        val sigCount = r.shortVec()
        r.skip(sigCount * 64) // signatures
        val messageStart = r.pos

        var version = -1
        val first = r.peek().toInt() and 0xFF
        if (first and 0x80 != 0) {
            version = first and 0x7F
            r.skip(1)
        }

        val numRequiredSignatures = r.u8()
        val numReadonlySigned = r.u8()
        val numReadonlyUnsigned = r.u8()

        val keyCount = r.shortVec()
        val keys = ArrayList<String>(keyCount)
        repeat(keyCount) { keys.add(Base58.encode(r.bytes(32))) }

        r.skip(32) // recent blockhash

        val ixCount = r.shortVec()
        val ixs = ArrayList<Compiled>(ixCount)
        repeat(ixCount) {
            val programIdIndex = r.u8()
            val accN = r.shortVec()
            val accounts = IntArray(accN) { r.u8() }
            val dataLen = r.shortVec()
            val data = r.bytes(dataLen)
            ixs.add(Compiled(programIdIndex, accounts, data))
        }
        // v0: address-table lookups. Their addresses live on-chain, so the
        // receipt resolves them with one RPC read (SolanaRpc.resolveLookups).
        val lookups = ArrayList<Lookup>()
        if (version == 0 && r.pos < tx.size) {
            val n = r.shortVec()
            repeat(n) {
                val table = Base58.encode(r.bytes(32))
                val wn = r.shortVec(); val w = IntArray(wn) { r.u8() }
                val rn = r.shortVec(); val ro = IntArray(rn) { r.u8() }
                lookups.add(Lookup(table, w, ro))
            }
        }

        Decoded(version, numRequiredSignatures, numReadonlySigned, numReadonlyUnsigned, 0, messageStart, keys, ixs, tx, lookups)
    } catch (_: Exception) {
        null
    }

    /** Map compiled instructions to the core's decoded-instruction model. */
    fun instructions(d: Decoded): List<DecodedInstruction> = d.instructions.mapNotNull { ix ->
        val program = d.staticAccountKeys.getOrNull(ix.programIdIndex) ?: return@mapNotNull null
        fun key(i: Int) = ix.accounts.getOrNull(i)?.let { d.staticAccountKeys.getOrNull(it) }
        when (program) {
            SYSTEM_PROGRAM -> when (if (ix.data.size >= 4) le32(ix.data, 0) else -1) {
                // transfer = 4-byte LE tag == 2, then u64 lamports
                2 -> if (ix.data.size >= 12) DecodedInstruction(InstructionKind.SOL_TRANSFER, program, destination = key(1), amountRaw = le64(ix.data, 4)) else null
                // assign = tag 1, then the new owner program (32 bytes); accounts[0] is the account handed over
                1 -> if (ix.data.size >= 36) DecodedInstruction(InstructionKind.ASSIGN_OWNER, program, destination = Base58.encode(ix.data.copyOfRange(4, 36)), subject = key(0)) else null
                // advanceNonceAccount = tag 4: a durable-nonce transaction
                4 -> DecodedInstruction(InstructionKind.DURABLE_NONCE, program, subject = key(0))
                else -> null
            }
            TOKEN_PROGRAM, TOKEN_2022_PROGRAM -> when (ix.data.getOrNull(0)?.toInt()?.and(0xFF)) {
                3 -> DecodedInstruction(InstructionKind.SPL_TRANSFER, program, destination = key(1), amountRaw = le64(ix.data, 1))
                12 -> DecodedInstruction(InstructionKind.SPL_TRANSFER, program, destination = key(2), amountRaw = le64(ix.data, 1))
                4, 13 -> {
                    val amt = le64(ix.data, 1)
                    DecodedInstruction(InstructionKind.TOKEN_APPROVE, program, destination = key(1), amountRaw = amt, isUnlimitedApproval = amt == U64_ALL_ONES)
                }
                6 -> DecodedInstruction(InstructionKind.SET_AUTHORITY, program, destination = key(0))
                9 -> DecodedInstruction(InstructionKind.CLOSE_ACCOUNT, program, destination = key(1))
                else -> DecodedInstruction(InstructionKind.UNKNOWN, program)
            }
            COMPUTE_BUDGET_PROGRAM -> null // fee/limit hints; nothing for the receipt
            else -> DecodedInstruction(InstructionKind.UNKNOWN, program)
        }
    }

    /** Best-effort balance deltas from the decoded instructions (SOL transfers). */
    fun deltas(d: Decoded, myWallet: String): List<BalanceDelta> {
        val out = ArrayList<BalanceDelta>()
        for (ix in d.instructions) {
            val program = d.staticAccountKeys.getOrNull(ix.programIdIndex) ?: continue
            if (program == SYSTEM_PROGRAM && ix.data.size >= 12 && le32(ix.data, 0) == 2) {
                val from = ix.accounts.getOrNull(0)?.let { d.staticAccountKeys.getOrNull(it) }
                val to = ix.accounts.getOrNull(1)?.let { d.staticAccountKeys.getOrNull(it) }
                val lamports = le64(ix.data, 4)
                if (from != null) out.add(BalanceDelta(from, NATIVE_SOL_MINT, "SOL", 9, -lamports))
                if (to != null) out.add(BalanceDelta(to, NATIVE_SOL_MINT, "SOL", 9, lamports))
            }
        }
        return out
    }

    /** Compute-budget hints: (requested unit limit, price in micro-lamports/CU). */
    data class ComputeBudget(val unitLimit: Long?, val priceMicroLamports: Long?)

    fun computeBudget(d: Decoded): ComputeBudget {
        var limit: Long? = null
        var price: Long? = null
        for (ix in d.instructions) {
            if (d.staticAccountKeys.getOrNull(ix.programIdIndex) != COMPUTE_BUDGET_PROGRAM) continue
            when (ix.data.getOrNull(0)?.toInt()?.and(0xFF)) {
                2 -> if (ix.data.size >= 5) limit = le32(ix.data, 1).toLong() and 0xFFFFFFFFL // SetComputeUnitLimit u32
                3 -> if (ix.data.size >= 9) price = le64(ix.data, 1)                          // SetComputeUnitPrice u64
            }
        }
        return ComputeBudget(limit, price)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private class Reader(val a: ByteArray) {
        var pos = 0
        fun peek(): Byte = a[pos]
        fun u8(): Int = a[pos++].toInt() and 0xFF
        fun skip(n: Int) { pos += n }
        fun bytes(n: Int): ByteArray { val s = a.copyOfRange(pos, pos + n); pos += n; return s }
        /** compact-u16 (shortvec) length. */
        fun shortVec(): Int {
            var result = 0; var shift = 0
            while (true) {
                val b = a[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }
}

/** Minimal Base58 (Bitcoin alphabet) codec for addresses. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { arr -> ALPHABET.forEachIndexed { i, c -> arr[c.code] = i } }

    /** Decode; throws IllegalArgumentException on a character outside the alphabet. */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val d = if (c.code < 128) INDEXES[c.code] else -1
            require(d >= 0) { "invalid base58 character '$c'" }
            input58[i] = d.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) zeros++
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var start = zeros
        while (start < input58.size) {
            var remainder = 0
            for (i in start until input58.size) {
                val digit = (input58[i].toInt() and 0xFF) + remainder * 58
                input58[i] = (digit / 256).toByte()
                remainder = digit % 256
            }
            decoded[--outputStart] = remainder.toByte()
            if (input58[start].toInt() == 0) start++
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) outputStart++
        return decoded.copyOfRange(outputStart - zeros, decoded.size)
    }

    /** Decode a 32-byte public key, or null when the text isn't one. */
    fun decodePubkey(input: String): ByteArray? = runCatching { decode(input) }.getOrNull()?.takeIf { it.size == 32 }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val b = input.copyOf()
        val enc = CharArray(b.size * 2)
        var outputStart = enc.size
        var start = zeros
        while (start < b.size) {
            var remainder = 0
            for (i in start until b.size) {
                val digit = (b[i].toInt() and 0xFF) + remainder * 256
                b[i] = (digit / 58).toByte()
                remainder = digit % 58
            }
            enc[--outputStart] = ALPHABET[remainder]
            if (b[start].toInt() == 0) start++
        }
        while (outputStart < enc.size && enc[outputStart] == ALPHABET[0]) outputStart++
        repeat(zeros) { enc[--outputStart] = ALPHABET[0] }
        return String(enc, outputStart, enc.size - outputStart)
    }
}
