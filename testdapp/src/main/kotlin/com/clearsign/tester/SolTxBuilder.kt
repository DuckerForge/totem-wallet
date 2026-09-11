package com.clearsign.tester

import java.io.ByteArrayOutputStream

/**
 * A tiny legacy-Solana transaction encoder — just enough for the test dApp to
 * craft known transactions (transfers, hidden fees, unlimited approvals, memos)
 * and hand them to ClearSign for signing. The signature array is left zero-filled
 * for the wallet to fill in.
 */
object SolTxBuilder {

    val SYSTEM_PROGRAM = ByteArray(32) // 32 zero bytes
    val TOKEN_PROGRAM = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val MEMO_PROGRAM = Base58.decode("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    val COMPUTE_BUDGET = Base58.decode("ComputeBudget111111111111111111111111111111")

    private const val U64_MAX = -1L // 0xFFFFFFFFFFFFFFFF

    class AccountMeta(val pubkey: ByteArray, val isSigner: Boolean, val isWritable: Boolean)
    class Instruction(val programId: ByteArray, val keys: List<AccountMeta>, val data: ByteArray)

    fun systemTransfer(from: ByteArray, to: ByteArray, lamports: Long) = Instruction(
        SYSTEM_PROGRAM,
        listOf(AccountMeta(from, true, true), AccountMeta(to, false, true)),
        le32(2) + le64(lamports),
    )

    fun tokenApprove(source: ByteArray, delegate: ByteArray, owner: ByteArray, amount: Long) = Instruction(
        TOKEN_PROGRAM,
        listOf(AccountMeta(source, false, true), AccountMeta(delegate, false, false), AccountMeta(owner, true, false)),
        byteArrayOf(4) + le64(amount), // 4 = Approve
    )

    /** Approve for the maximum u64 — an "unlimited" spending delegation (DANGER). */
    fun tokenApproveUnlimited(source: ByteArray, delegate: ByteArray, owner: ByteArray) =
        tokenApprove(source, delegate, owner, U64_MAX)

    /** SPL transferChecked (3 = Transfer would drop decimals; 12 = TransferChecked). */
    fun tokenTransferChecked(source: ByteArray, mint: ByteArray, dest: ByteArray, owner: ByteArray, amount: Long, decimals: Int) = Instruction(
        TOKEN_PROGRAM,
        listOf(AccountMeta(source, false, true), AccountMeta(mint, false, false), AccountMeta(dest, false, true), AccountMeta(owner, true, false)),
        byteArrayOf(12) + le64(amount) + byteArrayOf(decimals.toByte()),
    )

    /** SetAuthority: hands control of a token account/mint to another key (DANGER). */
    fun tokenSetAuthority(account: ByteArray, currentAuthority: ByteArray, newAuthority: ByteArray, authorityType: Int = 2) = Instruction(
        TOKEN_PROGRAM,
        listOf(AccountMeta(account, false, true), AccountMeta(currentAuthority, true, false)),
        byteArrayOf(6, authorityType.toByte(), 1) + newAuthority, // 6 = SetAuthority, option=1 (Some)
    )

    /** CloseAccount: closes a token account, sending its rent to [destination]. */
    fun tokenCloseAccount(account: ByteArray, destination: ByteArray, owner: ByteArray) = Instruction(
        TOKEN_PROGRAM,
        listOf(AccountMeta(account, false, true), AccountMeta(destination, false, true), AccountMeta(owner, true, false)),
        byteArrayOf(9), // 9 = CloseAccount
    )

    /** ComputeBudget: set a priority fee (adds a hidden per-CU cost). */
    fun setComputeUnitPrice(microLamports: Long) = Instruction(
        COMPUTE_BUDGET, emptyList(), byteArrayOf(3) + le64(microLamports), // 3 = SetComputeUnitPrice
    )

    /** InitializeMint2 on a freshly created mint account (best-effort NFT/token mint). */
    fun initializeMint(mint: ByteArray, mintAuthority: ByteArray, decimals: Int = 0) = Instruction(
        TOKEN_PROGRAM,
        listOf(AccountMeta(mint, false, true)),
        byteArrayOf(20, decimals.toByte()) + mintAuthority + byteArrayOf(0), // 20 = InitializeMint2, freezeAuthority option=0
    )

    /** System Assign: hands [account] (maybe the user's *wallet*) to [newOwner] program. */
    fun systemAssign(account: ByteArray, newOwner: ByteArray) = Instruction(
        SYSTEM_PROGRAM, listOf(AccountMeta(account, true, true)), le32(1) + newOwner,
    )

    fun memo(text: String) = Instruction(MEMO_PROGRAM, emptyList(), text.toByteArray(Charsets.UTF_8))

    /** Compile instructions into a serialized legacy transaction (unsigned). */
    fun build(feePayer: ByteArray, recentBlockhash: ByteArray, instructions: List<Instruction>): ByteArray {
        val merged = LinkedHashMap<String, AccountMeta>()
        fun add(m: AccountMeta) {
            val k = Base58.encode(m.pubkey)
            val e = merged[k]
            merged[k] = if (e == null) m else AccountMeta(m.pubkey, e.isSigner || m.isSigner, e.isWritable || m.isWritable)
        }
        add(AccountMeta(feePayer, isSigner = true, isWritable = true))
        for (ix in instructions) {
            ix.keys.forEach { add(it) }
            add(AccountMeta(ix.programId, isSigner = false, isWritable = false))
        }

        val fp = Base58.encode(feePayer)
        val all = merged.values.toList()
        val signerWritable = all.filter { it.isSigner && it.isWritable }.sortedByDescending { Base58.encode(it.pubkey) == fp }
        val signerReadonly = all.filter { it.isSigner && !it.isWritable }
        val nonSignerWritable = all.filter { !it.isSigner && it.isWritable }
        val nonSignerReadonly = all.filter { !it.isSigner && !it.isWritable }
        val ordered = signerWritable + signerReadonly + nonSignerWritable + nonSignerReadonly

        val numRequiredSignatures = signerWritable.size + signerReadonly.size
        val numReadonlySigned = signerReadonly.size
        val numReadonlyUnsigned = nonSignerReadonly.size

        val index = HashMap<String, Int>()
        ordered.forEachIndexed { i, m -> index[Base58.encode(m.pubkey)] = i }

        val msg = ByteArrayOutputStream()
        msg.write(numRequiredSignatures)
        msg.write(numReadonlySigned)
        msg.write(numReadonlyUnsigned)
        msg.write(shortVec(ordered.size))
        ordered.forEach { msg.write(it.pubkey) }
        msg.write(recentBlockhash)
        msg.write(shortVec(instructions.size))
        for (ix in instructions) {
            msg.write(index.getValue(Base58.encode(ix.programId)))
            msg.write(shortVec(ix.keys.size))
            ix.keys.forEach { msg.write(index.getValue(Base58.encode(it.pubkey))) }
            msg.write(shortVec(ix.data.size))
            msg.write(ix.data)
        }
        val message = msg.toByteArray()

        val tx = ByteArrayOutputStream()
        tx.write(shortVec(numRequiredSignatures))
        repeat(numRequiredSignatures) { tx.write(ByteArray(64)) } // empty signatures
        tx.write(message)
        return tx.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le64(v: Long): ByteArray = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun shortVec(nIn: Int): ByteArray {
        var n = nIn
        val out = ByteArrayOutputStream()
        while (true) {
            var b = n and 0x7F
            n = n ushr 7
            if (n != 0) b = b or 0x80
            out.write(b)
            if (n == 0) break
        }
        return out.toByteArray()
    }
}

object Base58 {
    private const val A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val b = input.copyOf()
        val enc = CharArray(b.size * 2)
        var outStart = enc.size
        var start = zeros
        while (start < b.size) {
            var rem = 0
            for (i in start until b.size) {
                val d = (b[i].toInt() and 0xFF) + rem * 256
                b[i] = (d / 58).toByte()
                rem = d % 58
            }
            enc[--outStart] = A[rem]
            if (b[start].toInt() == 0) start++
        }
        while (outStart < enc.size && enc[outStart] == A[0]) outStart++
        repeat(zeros) { enc[--outStart] = A[0] }
        return String(enc, outStart, enc.size - outStart)
    }

    fun decode(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val input58 = IntArray(s.length) { A.indexOf(s[it]) }
        var zeros = 0
        while (zeros < input58.size && input58[zeros] == 0) zeros++
        val decoded = ByteArray(s.length)
        var outStart = decoded.size
        var start = zeros
        while (start < input58.size) {
            var rem = 0
            for (i in start until input58.size) {
                val d = rem * 58 + input58[i]
                input58[i] = d / 256
                rem = d % 256
            }
            decoded[--outStart] = rem.toByte()
            if (input58[start] == 0) start++
        }
        while (outStart < decoded.size && decoded[outStart].toInt() == 0) outStart++
        val result = ByteArray(zeros + (decoded.size - outStart))
        System.arraycopy(decoded, outStart, result, zeros, decoded.size - outStart)
        return result
    }
}
