package com.clearsign.app

import com.clearsign.core.RiskFlag

/**
 * The honest inventory of what this app does for the user: every defense actually
 * implemented, one line each, so "what does it protect me from" has a precise answer.
 */
object Protections {
    data class Item(val icon: HIcon, val titleRes: Int, val bodyRes: Int)

    val items: List<Item> = listOf(
        Item(HIcon.FLASK, R.string.p_sim_t, R.string.p_sim_b),
        Item(HIcon.SIGN, R.string.p_receipt_t, R.string.p_receipt_b),
        Item(HIcon.SKULL, R.string.p_risks_t, R.string.p_risks_b),
        Item(HIcon.DRIFT, R.string.p_toctou_t, R.string.p_toctou_b),
        Item(HIcon.BAN, R.string.p_blocklist_t, R.string.p_blocklist_b),
        Item(HIcon.MASK, R.string.p_lookalike_t, R.string.p_lookalike_b),
        Item(HIcon.SEEDLING, R.string.p_intel_t, R.string.p_intel_b),
        Item(HIcon.SCAN, R.string.p_trace_t, R.string.p_trace_b),
        Item(HIcon.MEGAPHONE, R.string.p_community_t, R.string.p_community_b),
        Item(HIcon.INFO, R.string.p_idl_t, R.string.p_idl_b),
        Item(HIcon.COINS, R.string.p_fee_t, R.string.p_fee_b),
        Item(HIcon.HISTORY, R.string.p_dapp_t, R.string.p_dapp_b),
        Item(HIcon.FINGERPRINT, R.string.p_hw_t, R.string.p_hw_b),
        Item(HIcon.HOLD, R.string.p_hold_t, R.string.p_hold_b),
        Item(HIcon.BLOCK, R.string.p_bundle_t, R.string.p_bundle_b),
        Item(HIcon.SHIELD_LOCK, R.string.p_attest_t, R.string.p_attest_b),
        Item(HIcon.KEY, R.string.p_hygiene_t, R.string.p_hygiene_b),
        Item(HIcon.LOCK, R.string.p_privacy_t, R.string.p_privacy_b),
    )

    /** Distinct risk types the engine can raise (drives the "N risk types" counter). */
    val riskTypes: Int get() = RiskFlag.entries.size
}
