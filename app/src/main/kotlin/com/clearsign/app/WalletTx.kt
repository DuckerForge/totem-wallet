package com.clearsign.app

import java.io.ByteArrayOutputStream

/**
 * The wallet's own transaction builder (legacy message format): revoke a
 * delegate, close empty accounts, send SOL / tokens, pay for a theme. The
 * signature slot is left zeroed for the Seed Vault; ClearSign then runs the
 * same receipt pipeline on these bytes as on any dApp transaction.
 */
object WalletTx {
    val SYSTEM_PROGRAM = ByteArray(32)
    val TOKEN_PROGRAM: ByteArray = Base58.decode(SolanaTx.TOKEN_PROGRAM)
    val TOKEN_2022: ByteArray = Base58.decode(SolanaTx.TOKEN_2022_PROGRAM)
    val ATA_PROGRAM: ByteArray = Base58.decode(Pda.ATA_PROGRAM)
    val MEMO_PROGRAM: ByteArray = Base58.decode("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    val COMPUTE_BUDGET: ByteArray = Base58.decode(SolanaTx.COMPUTE_BUDGET_PROGRAM)

    class AccountMeta(val pubkey: ByteArray, val isSigner: Boolean, val isWritable: Boolean)
    class Instruction(val programId: ByteArray, val keys: List<AccountMeta>, val data: ByteArray)

    fun tokenProgramFor(id: String): ByteArray = if (id == SolanaTx.TOKEN_2022_PROGRAM) TOKEN_2022 else TOKEN_PROGRAM

    fun systemTransfer(from: ByteArray, to: ByteArray, lamports: Long) = Instruction(
        SYSTEM_PROGRAM, listOf(AccountMeta(from, true, true), AccountMeta(to, false, true)), le32(2) + le64(lamports),
    )

    /** SPL Revoke (5): the delegate on [source] can no longer spend. */
    fun tokenRevoke(source: ByteArray, owner: ByteArray, program: ByteArray = TOKEN_PROGRAM) = Instruction(
        program, listOf(AccountMeta(source, false, true), AccountMeta(owner, true, false)), byteArrayOf(5),
    )

    /** SPL CloseAccount (9): reclaims the rent of an empty token account to [destination]. */
    fun tokenCloseAccount(account: ByteArray, destination: ByteArray, owner: ByteArray, program: ByteArray = TOKEN_PROGRAM) = Instruction(
        program,
        listOf(AccountMeta(account, false, true), AccountMeta(destination, false, true), AccountMeta(owner, true, false)),
        byteArrayOf(9),
    )

    /** SPL TransferChecked (12). */
    fun tokenTransferChecked(source: ByteArray, mint: ByteArray, dest: ByteArray, owner: ByteArray, amount: Long, decimals: Int, program: ByteArray = TOKEN_PROGRAM) = Instruction(
        program,
        listOf(AccountMeta(source, false, true), AccountMeta(mint, false, false), AccountMeta(dest, false, true), AccountMeta(owner, true, false)),
        byteArrayOf(12) + le64(amount) + byteArrayOf(decimals.toByte()),
    )

    /** Associated Token Account `CreateIdempotent` (1): no-op when the ATA already exists. */
    fun createAtaIdempotent(payer: ByteArray, ata: ByteArray, owner: ByteArray, mint: ByteArray, program: ByteArray = TOKEN_PROGRAM) = Instruction(
        ATA_PROGRAM,
        listOf(
            AccountMeta(payer, true, true), AccountMeta(ata, false, true), AccountMeta(owner, false, false),
            AccountMeta(mint, false, false), AccountMeta(SYSTEM_PROGRAM, false, false), AccountMeta(program, false, false),
        ),
        byteArrayOf(1),
    )

    fun memo(text: String) = Instruction(MEMO_PROGRAM, emptyList(), text.toByteArray(Charsets.UTF_8))

    fun setComputeUnitPrice(microLamports: Long) = Instruction(COMPUTE_BUDGET, emptyList(), byteArrayOf(3) + le64(microLamports))
    fun setComputeUnitLimit(units: Int) = Instruction(COMPUTE_BUDGET, emptyList(), byteArrayOf(2) + le32(units))

    /** Compile instructions into a serialized legacy transaction (unsigned, signature slots zeroed). */
    fun build(feePayer: ByteArray, recentBlockhash: ByteArray, instructions: List<Instruction>): ByteArray {
        require(recentBlockhash.size == 32)
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
        val index = HashMap<String, Int>()
        ordered.forEachIndexed { i, m -> index[Base58.encode(m.pubkey)] = i }

        val msg = ByteArrayOutputStream()
        msg.write(numRequiredSignatures); msg.write(signerReadonly.size); msg.write(nonSignerReadonly.size)
        msg.write(shortVec(ordered.size)); ordered.forEach { msg.write(it.pubkey) }
        msg.write(recentBlockhash)
        msg.write(shortVec(instructions.size))
        for (ix in instructions) {
            msg.write(index.getValue(Base58.encode(ix.programId)))
            msg.write(shortVec(ix.keys.size)); ix.keys.forEach { msg.write(index.getValue(Base58.encode(it.pubkey))) }
            msg.write(shortVec(ix.data.size)); msg.write(ix.data)
        }
        val tx = ByteArrayOutputStream()
        tx.write(shortVec(numRequiredSignatures))
        repeat(numRequiredSignatures) { tx.write(ByteArray(64)) }
        tx.write(msg.toByteArray())
        return tx.toByteArray()
    }

    const val MAX_TX_BYTES = 1232

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le64(v: Long): ByteArray = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun shortVec(nIn: Int): ByteArray {
        var n = nIn; val out = ByteArrayOutputStream()
        while (true) { val b = n and 0x7F; n = n ushr 7; if (n == 0) { out.write(b); break } else out.write(b or 0x80) }
        return out.toByteArray()
    }
}
