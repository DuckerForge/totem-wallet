package com.clearsign.core

/**
 * Keccak-256, Ethereum's and `solana_program::keccak`'s: SHA-3's permutation with the
 * `0x01 … 0x80` padding, so the JDK's SHA3-256 does not do. Hand-written because ORE decides
 * with a keccak of the round id which squares pay one miner only, and that wants computing on
 * the phone before the round. Checked against the known vectors and thirty real rounds, see `OreMaskTest`.
 */
object Keccak {
    private val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808bL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800aL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    private val ROT = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    fun hash256(input: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        // Absorb, 136-byte blocks, with Keccak's padding.
        val padded = ByteArray(((input.size / rate) + 1) * rate)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80.toByte().toInt()).toByte()
        var off = 0
        while (off < padded.size) {
            for (i in 0 until rate / 8) state[i] = state[i] xor le64(padded, off + 8 * i)
            permute(state)
            off += rate
        }
        val out = ByteArray(32)
        for (i in 0 until 4) {
            val v = state[i]
            for (k in 0 until 8) out[8 * i + k] = (v ushr (8 * k)).toByte()
        }
        return out
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            // theta
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            // rho e pi
            for (x in 0 until 5) for (y in 0 until 5) {
                val idx = x + 5 * y
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[idx], ROT[idx])
            }
            // chi
            for (y in 0 until 5) for (x in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            // iota
            a[0] = a[0] xor RC[round]
        }
    }
}
