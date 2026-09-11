package com.clearsign.app

import org.json.JSONArray
import org.json.JSONObject

/** JSON codec for ledger entries (versioned; unknown fields are ignored on read). */
object LedgerJson {
    const val VERSION = 1

    fun encode(e: LedgerEntry): JSONObject = JSONObject()
        .put("v", VERSION).put("id", e.id).put("gid", e.groupId).put("at", e.at).put("kind", e.kind)
        .put("dApp", e.dApp).put("host", e.host ?: JSONObject.NULL).put("pkg", e.pkg ?: JSONObject.NULL).put("cluster", e.cluster ?: JSONObject.NULL)
        .put("wallet", e.wallet).put("sig", e.signature ?: JSONObject.NULL).put("sent", e.sent).put("txi", e.txIndex).put("txn", e.txCount)
        .put("out", JSONArray(e.outflows.map { leg(it) })).put("in", JSONArray(e.inflows.map { leg(it) }))
        .put("fee", e.feeLamports).put("feeMine", e.feePaidByMe)
        .put("cp", JSONArray(e.counterparties.map { c ->
            JSONObject().put("a", c.address).put("l", c.label ?: JSONObject.NULL).put("t", c.trust).put("fee", c.isFee).put("new", c.isNewAccount)
                .put("raw", c.rawAmount).put("mint", c.mint).put("sym", c.symbol).put("dec", c.decimals)
        }))
        .put("to", e.primaryRecipient ?: JSONObject.NULL).put("toLabel", e.recipientLabel ?: JSONObject.NULL)
        .put("risks", JSONArray(e.risks.map { JSONObject().put("f", it.flag).put("s", it.severity).put("d", it.detail) }))
        .put("fiat", JSONObject().also { o -> e.fiat.forEach { (cur, s) ->
            o.put(cur, JSONObject().put("sol", s.solPrice).put("prices", JSONObject(s.prices)).put("at", s.at).put("src", s.source))
        } })
        .put("att", e.attestation ?: JSONObject.NULL).put("attSig", e.attestationSig ?: JSONObject.NULL)
        .put("note", e.note).put("tags", JSONArray(e.tags))

    private fun leg(l: Leg) = JSONObject().put("mint", l.mint).put("sym", l.symbol).put("dec", l.decimals).put("raw", l.rawAmount)

    fun decode(o: JSONObject): LedgerEntry {
        fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
        fun legs(k: String): List<Leg> = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Leg(it.optString("mint"), it.optString("sym"), it.optInt("dec"), it.optLong("raw")) } } } ?: emptyList()
        val cps = o.optJSONArray("cp")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            Counterparty(it.optString("a"), if (it.isNull("l")) null else it.optString("l"), it.optString("t", "NEW"), it.optBoolean("fee"), it.optBoolean("new"), it.optLong("raw"), it.optString("mint"), it.optString("sym"), it.optInt("dec"))
        } } } ?: emptyList()
        val risks = o.optJSONArray("risks")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { RiskNote(it.optString("f"), it.optString("s"), it.optString("d")) } } } ?: emptyList()
        val fiat = HashMap<String, FiatSnapshot>()
        o.optJSONObject("fiat")?.let { fo -> fo.keys().forEach { cur ->
            val s = fo.optJSONObject(cur) ?: return@forEach
            val prices = HashMap<String, Double>(); s.optJSONObject("prices")?.let { po -> po.keys().forEach { m -> prices[m] = po.optDouble(m) } }
            fiat[cur] = FiatSnapshot(cur, s.optDouble("sol"), prices, s.optLong("at"), s.optString("src", "spot"))
        } }
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return LedgerEntry(
            id = o.optString("id"), groupId = o.optString("gid"), at = o.optLong("at"), kind = o.optString("kind", "tx"),
            dApp = o.optString("dApp"), host = str("host"), pkg = str("pkg"), cluster = str("cluster"), wallet = o.optString("wallet"),
            signature = str("sig"), sent = o.optBoolean("sent"), txIndex = o.optInt("txi"), txCount = o.optInt("txn", 1),
            outflows = legs("out"), inflows = legs("in"), feeLamports = o.optLong("fee"), feePaidByMe = o.optBoolean("feeMine", true),
            counterparties = cps, primaryRecipient = str("to"), recipientLabel = str("toLabel"), risks = risks, fiat = fiat,
            attestation = str("att"), attestationSig = str("attSig"), note = o.optString("note"), tags = tags,
        )
    }
}
