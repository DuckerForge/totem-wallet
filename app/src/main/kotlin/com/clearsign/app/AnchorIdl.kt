package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.ProgramCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * Decodes a call to an unknown program from the IDL it published on chain (Anchor: an account
 * at `createWithSeed(findProgramAddress([], pid), "anchor:idl", pid)` holding zlib-compressed
 * JSON). "Unknown program, 200 bytes" becomes "Jupiter v6 · route(in_amount: 2 000 000,
 * slippage_bps: 50)". IDLs are cached on disk per program for a week, misses for a day.
 */
object AnchorIdl {
    private const val TAG = "ClearSign-IDL"
    private const val TTL_OK_MS = 7L * 24 * 3600_000
    private const val TTL_MISS_MS = 24L * 3600_000

    private class Instr(val name: String, val disc: ByteArray, val args: List<Pair<String, Any>>) // arg type: String or JSONObject
    private class Idl(val name: String?, val instructions: List<Instr>)

    private val memo = ConcurrentHashMap<String, Idl?>()

    /** Decode one instruction; null when the program has no readable IDL or the data doesn't match. */
    fun decode(ctx: Context, rpcUrl: String, programId: String, data: ByteArray): ProgramCall? {
        val idl = idlFor(ctx, rpcUrl, programId) ?: return null
        if (data.size < 8) return null
        val ix = idl.instructions.firstOrNull { it.disc.contentEquals(data.copyOfRange(0, 8)) } ?: return null
        val args = ArrayList<Pair<String, String>>()
        val r = Cursor(data, 8)
        for ((name, type) in ix.args) {
            val v = r.read(type) ?: break     // first unsupported type ends the (partial) decode
            args.add(name to v)
        }
        return ProgramCall(programId, idl.name, ix.name, args)
    }

    private fun idlFor(ctx: Context, rpcUrl: String, programId: String): Idl? {
        memo[programId]?.let { return it }
        if (memo.containsKey(programId)) return null
        val dir = File(ctx.cacheDir, "idl").apply { mkdirs() }
        val f = File(dir, "$programId.json"); val miss = File(dir, "$programId.miss")
        val now = System.currentTimeMillis()
        val cached: Idl? = when {
            f.exists() && now - f.lastModified() < TTL_OK_MS -> runCatching { parse(f.readText()) }.getOrNull()
            miss.exists() && now - miss.lastModified() < TTL_MISS_MS -> { memo[programId] = null; return null }
            else -> null
        }
        if (cached != null) { memo[programId] = cached; return cached }
        val json = fetch(rpcUrl, programId)
        val idl = json?.let { runCatching { parse(it) }.getOrNull() }
        if (idl != null && json != null) { f.writeText(json); miss.delete() } else { miss.writeText(""); f.delete() }
        memo[programId] = idl
        return idl
    }

    /** IDL account → JSON text, or null. Layout: 8 disc | 32 authority | u32 len | zlib(json). */
    private fun fetch(rpcUrl: String, programId: String): String? {
        val pid = Base58.decodePubkey(programId) ?: return null
        val base = Pda.findProgramAddress(emptyList(), pid)?.first ?: return null
        val addr = Base58.encode(Pda.createWithSeed(base, "anchor:idl", pid))
        val acc = SolanaRpc.getAccountInfoRaw(rpcUrl, addr) ?: return null
        val d = acc.data
        if (d.size < 44) return null
        val len = (d[40].toInt() and 0xFF) or ((d[41].toInt() and 0xFF) shl 8) or ((d[42].toInt() and 0xFF) shl 16) or ((d[43].toInt() and 0xFF) shl 24)
        if (len <= 0 || 44 + len > d.size) return null
        return try {
            val inf = Inflater(); inf.setInput(d, 44, len)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
            inf.end()
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) { Log.w(TAG, "inflate failed for $programId: ${e.message}"); null }
    }

    private fun parse(json: String): Idl {
        val o = JSONObject(json)
        val name = o.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotEmpty() } ?: o.optString("name").takeIf { it.isNotEmpty() }
        val arr = o.optJSONArray("instructions") ?: JSONArray()
        val ixs = (0 until arr.length()).mapNotNull { i ->
            val ix = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = ix.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val disc = ix.optJSONArray("discriminator")?.let { d -> ByteArray(minOf(8, d.length())) { k -> d.optInt(k).toByte() } }?.takeIf { it.size == 8 }
                ?: sha256("global:" + snake(n)).copyOfRange(0, 8)
            val args = ix.optJSONArray("args")?.let { a ->
                (0 until a.length()).mapNotNull { k -> val ao = a.optJSONObject(k) ?: return@mapNotNull null; ao.optString("name") to (ao.opt("type") ?: "unknown") }
            } ?: emptyList()
            Instr(n, disc, args)
        }
        return Idl(name, ixs)
    }

    /** Anchor's sighash uses the snake_case method name; new IDLs already are. */
    private fun snake(s: String): String = buildString {
        s.forEachIndexed { i, c -> if (c.isUpperCase()) { if (i > 0 && s[i - 1] != '_') append('_'); append(c.lowercaseChar()) } else append(c) }
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    /** Borsh reader for the scalar types an IDL can declare. */
    private class Cursor(val d: ByteArray, var pos: Int) {
        fun read(type: Any): String? = when (type) {
            "u8" -> u(1)?.toString(); "i8" -> u(1)?.toByte()?.toString()
            "u16" -> u(2)?.toString(); "i16" -> u(2)?.toShort()?.toString()
            "u32" -> u(4)?.toString(); "i32" -> u(4)?.toInt()?.toString()
            "u64" -> u(8)?.let { java.lang.Long.toUnsignedString(it) }; "i64" -> u(8)?.toString()
            "bool" -> u(1)?.let { if (it != 0L) "true" else "false" }
            "pubkey", "publicKey" -> take(32)?.let { Base58.encode(it) }
            "string" -> u(4)?.toInt()?.let { n -> take(n)?.let { String(it, Charsets.UTF_8) } }
            "f64" -> u(8)?.let { java.lang.Double.longBitsToDouble(it).toString() }
            "f32" -> u(4)?.let { java.lang.Float.intBitsToFloat(it.toInt()).toString() }
            is JSONObject -> when {
                type.has("option") -> u(1)?.let { tag -> if (tag == 0L) "none" else read(type.get("option")) }
                type.has("vec") -> u(4)?.toInt()?.let { n -> if (n > 64) null else (0 until n).map { read(type.get("vec")) ?: return null }.joinToString(", ", "[", "]") }
                type.has("array") -> {
                    val a = type.optJSONArray("array"); val t = a?.opt(0); val n = a?.optInt(1) ?: -1
                    if (t == null || n < 0 || n > 64) null else (0 until n).map { read(t) ?: return null }.joinToString(", ", "[", "]")
                }
                else -> null   // defined structs / enums: stop here
            }
            else -> null
        }
        private fun take(n: Int): ByteArray? { if (n < 0 || pos + n > d.size) return null; val b = d.copyOfRange(pos, pos + n); pos += n; return b }
        private fun u(n: Int): Long? { val b = take(n) ?: return null; var v = 0L; for (i in b.indices) v = v or ((b[i].toLong() and 0xFF) shl (8 * i)); return v }
    }
}
