package com.clearsign.app

import org.json.JSONArray
import org.json.JSONObject

/** Verifiable export: every entry with its attestation, plus the key that signed them. */
object LedgerBundle {
    fun build(entries: List<LedgerEntry>, publicKey: String?): String = JSONObject()
        .put("type", "clearsign-ledger").put("v", 1).put("exportedAt", System.currentTimeMillis())
        .put("alg", "ES256").put("publicKey", publicKey ?: JSONObject.NULL)
        .put("entries", JSONArray(entries.sortedBy { it.at }.map { e ->
            LedgerJson.encode(e).also { o ->
                if (e.attestation != null && e.attestationSig != null) {
                    o.put("attestation", JSONObject().put("statement", runCatching { JSONObject(e.attestation) }.getOrDefault(JSONObject())).put("signature", e.attestationSig))
                }
                o.remove("att"); o.remove("attSig")
            }
        }))
        .toString(2)
}
