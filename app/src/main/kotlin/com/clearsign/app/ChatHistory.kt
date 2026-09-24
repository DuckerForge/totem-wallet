package com.clearsign.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The conversation, kept between openings. It lived in a `remember` and was gone when the sheet
 * closed, and the transcript is the audit trail: every tool the model ran leaves a line saying
 * what it tried and what Totem decided. On disk and nowhere else: a wallet whose claim is that
 * nothing leaves the phone does not ship this to a server, and the budget key exists here only.
 * Filed per wallet, so switching accounts does not show another one's conversation.
 */
object ChatHistory {
    private const val DIR = "chat"

    /** Kept on disk. Old turns are history, not context. */
    private const val KEEP = 120

    /**
     * How much of it the model gets back. Free tiers meter tokens per minute, and re-sending an
     * afternoon of conversation on every message spends that on nothing. Recent turns carry the thread.
     */
    const val CONTEXT_TURNS = 16

    private fun file(ctx: Context, wallet: String?): File {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        // A wallet is base58, so it is already a safe file name; the fallback
        // covers "no wallet connected yet".
        val name = wallet?.takeIf { it.isNotBlank() }?.take(44) ?: "none"
        return File(dir, "$name.json")
    }

    fun load(ctx: Context, wallet: String? = Settings.watchWallet(ctx)): List<Brain.Turn> {
        val f = file(ctx, wallet)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = o.optString("role").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Brain.Turn(
                    role = role,
                    text = o.optString("text"),
                    tool = o.optString("tool").takeIf { it.isNotEmpty() },
                    verdict = o.optString("verdict").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, turns: List<Brain.Turn>, wallet: String? = Settings.watchWallet(ctx)) {
        runCatching {
            val arr = JSONArray()
            turns.takeLast(KEEP).forEach { t ->
                arr.put(
                    JSONObject().put("role", t.role).put("text", t.text)
                        .put("tool", t.tool ?: JSONObject.NULL)
                        .put("verdict", t.verdict ?: JSONObject.NULL),
                )
            }
            file(ctx, wallet).writeText(arr.toString())
        }
    }

    fun clear(ctx: Context, wallet: String? = Settings.watchWallet(ctx)) {
        runCatching { file(ctx, wallet).delete() }
    }

    /** What the model sees: the last few turns, trimmed so the window never opens on a tool result with no question in front of it. */
    fun context(turns: List<Brain.Turn>): List<Brain.Turn> {
        if (turns.size <= CONTEXT_TURNS) return turns
        val tail = turns.takeLast(CONTEXT_TURNS)
        val firstUser = tail.indexOfFirst { it.role == "user" }
        return if (firstUser <= 0) tail else tail.drop(firstUser)
    }
}
