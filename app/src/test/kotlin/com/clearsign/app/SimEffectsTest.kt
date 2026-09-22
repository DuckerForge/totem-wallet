package com.clearsign.app

import com.clearsign.core.AddressTrust
import com.clearsign.core.EffectContext
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.RiskEngine
import com.clearsign.core.RiskFlag
import com.clearsign.core.Severity
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La parte senza rete di `simulateEffects`, provata da file JSON scritti come
 * li manda il nodo: una risposta di `getMultipleAccounts` e una di
 * `simulateTransaction`, ognuna con il suo slot.
 *
 * E' la prova di non regressione del difetto numero uno del piano RPC: una
 * lettura di partenza mancante che diventava `Ok`, con il cancello anti
 * prosciugamento spento in silenzio. E dello slot: due stati di due momenti
 * diversi non si sottraggono.
 */
class SimEffectsTest {
    private val owner = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9"
    private val dest = "39FeRfGVCH5Bq5fzChHmL3LAsQCXAvFt7idaLipAQsCs"
    private val tokAcc = "8gCUy6vGz9y2sVYUx5r1xY2pP8hZq3Sx6Jq9Uu4wzM1e"
    private val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private fun json(name: String) = JSONObject(javaClass.getResource("/sim/$name.json")!!.readText())
    private fun pre(name: String, keys: List<String>) = assertNotNull(SolanaRpc.preStateOf(json(name), keys))
    private fun sim(name: String) = assertNotNull(SolanaRpc.simOf(json(name)))

    private val transfer = SolanaRpc.Tracked(owner, emptyList(), listOf(dest), emptyList())
    private val usdc = SolanaRpc.TokenAccountInfo(tokAcc, mint, 6, 1_000_000L, 2_039_280L, null, 0L, "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", null, "initialized")
    private val drain = SolanaRpc.Tracked(owner, listOf(usdc), listOf(dest), emptyList())

    private fun effects(pre: SolanaRpc.PreState, sim: SolanaRpc.Sim, tracked: SolanaRpc.Tracked, tokens: List<SolanaRpc.TokenAccountInfo> = emptyList()) =
        SolanaRpc.effectsOf(pre, sim, tracked, tokens, { "USDC" }) { 6 }

    @Test fun `stessi slot, un trasferimento di quasi tutto il SOL`() {
        val out = assertIs<SolanaRpc.SimOutcome.Ok>(effects(pre("pre-transfer", transfer.order()), sim("sim-transfer"), transfer))
        assertEquals(2_000_000_000L, out.preBalances[NATIVE_SOL_MINT])
        val mine = out.deltas.single { it.owner == owner }
        assertEquals(-1_900_005_000L, mine.rawAmount)
        val theirs = out.deltas.single { it.owner == dest }
        assertEquals(1_900_000_000L, theirs.rawAmount)
        assertEquals(150L, out.computeUnits)
    }

    @Test fun `e il cancello anti prosciugamento lo vede`() {
        val out = assertIs<SolanaRpc.SimOutcome.Ok>(effects(pre("pre-transfer", transfer.order()), sim("sim-transfer"), transfer))
        val risks = RiskEngine().assessEffects(out.deltas, EffectContext(myWallet = owner, preBalances = out.preBalances, primaryRecipient = dest), AddressTrust())
        val drain = risks.single { it.flag == RiskFlag.DRAINS_BALANCE }
        assertEquals(Severity.DANGER, drain.severity)
    }

    @Test fun `slot diversi non si sottraggono`() {
        assertIs<SolanaRpc.SimOutcome.Unavailable>(effects(pre("pre-transfer", transfer.order()), sim("sim-transfer-later"), transfer))
    }

    @Test fun `senza slot non si sottrae`() {
        val p = pre("pre-transfer", transfer.order()).copy(slot = null)
        assertIs<SolanaRpc.SimOutcome.Unavailable>(effects(p, sim("sim-transfer").copy(slot = null), transfer))
        assertIs<SolanaRpc.SimOutcome.Unavailable>(effects(p, sim("sim-transfer"), transfer))
    }

    @Test fun `il proprietario assente dallo stato di partenza non e' Ok`() {
        // Il nodo risponde null per un conto che non conosce: era la falla numero uno.
        val p = pre("pre-transfer-noowner", transfer.order())
        assertEquals(0L, p.accounts[owner]?.lamports)
        val missing = p.copy(accounts = p.accounts - owner)
        assertIs<SolanaRpc.SimOutcome.Unavailable>(effects(missing, sim("sim-transfer"), transfer))
    }

    @Test fun `un conto tracciato non letto non si inventa`() {
        val p = pre("pre-transfer", transfer.order())
        assertIs<SolanaRpc.SimOutcome.Unavailable>(effects(p.copy(accounts = p.accounts - dest), sim("sim-transfer"), transfer))
    }

    @Test fun `il nodo l'ha eseguita ed e' andata male`() {
        val out = assertIs<SolanaRpc.SimOutcome.Failed>(effects(pre("pre-transfer", transfer.order()), sim("sim-failed"), transfer))
        assertTrue("Custom" in out.err)
    }

    @Test fun `una moneta che se ne va tutta, col saldo di partenza letto adesso`() {
        val out = assertIs<SolanaRpc.SimOutcome.Ok>(effects(pre("pre-drain", drain.order()), sim("sim-drain"), drain, listOf(usdc)))
        assertEquals(1_000_000L, out.preBalances[mint])
        val gone = out.deltas.single { it.mint == mint }
        assertEquals(-1_000_000L, gone.rawAmount)
        assertEquals("USDC", gone.symbol)
        val risks = RiskEngine().assessEffects(out.deltas, EffectContext(myWallet = owner, preBalances = out.preBalances, primaryRecipient = dest), AddressTrust())
        assertTrue(risks.any { it.flag == RiskFlag.DRAINS_BALANCE })
    }

    @Test fun `il saldo di partenza viene dalla lettura, non dall'elenco in cache`() {
        // L'elenco dice un milione, la catena adesso dice zero: vale la catena.
        val p = pre("pre-drain", drain.order())
        val stale = p.copy(accounts = p.accounts + (tokAcc to SolanaRpc.AcctPre(2_039_280L, 0L)))
        val out = assertIs<SolanaRpc.SimOutcome.Ok>(effects(stale, sim("sim-drain"), drain, listOf(usdc)))
        assertEquals(0L, out.preBalances[mint])
        assertTrue(out.deltas.none { it.mint == mint })
    }

    @Test fun `un conto non tracciato non entra nei saldi di partenza`() {
        val other = usdc.copy(pubkey = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", mint = "So11111111111111111111111111111111111111112", amount = 5L)
        val out = assertIs<SolanaRpc.SimOutcome.Ok>(effects(pre("pre-drain", drain.order()), sim("sim-drain"), drain, listOf(usdc, other)))
        assertNull(out.preBalances[other.mint])
    }

    // ---- riallineare le letture ------------------------------------------

    @Test fun `la seconda lettura sullo slot giusto vince`() {
        val first = pre("pre-transfer", transfer.order())
        val second = pre("pre-transfer-later", transfer.order())
        assertEquals(second, SolanaRpc.alignedPre(first, second, 500000103L, transfer.order()))
    }

    @Test fun `uno stato fermo da tutte e due le parti vale anche in mezzo`() {
        val first = pre("pre-transfer", transfer.order())        // slot ...100
        val second = pre("pre-transfer-later", transfer.order()) // slot ...103, stessi numeri
        val aligned = assertNotNull(SolanaRpc.alignedPre(first, second, 500000102L, transfer.order()))
        assertEquals(500000102L, aligned.slot)
        assertEquals(first.accounts, aligned.accounts)
    }

    @Test fun `uno stato che si e' mosso non si puo' racchiudere`() {
        val first = pre("pre-transfer", transfer.order())
        val moved = pre("pre-transfer-moved", transfer.order())
        assertNull(SolanaRpc.alignedPre(first, moved, 500000102L, transfer.order()))
    }

    @Test fun `la simulazione fuori dalla finestra non si accetta`() {
        val first = pre("pre-transfer", transfer.order())
        val second = pre("pre-transfer-later", transfer.order())
        assertNull(SolanaRpc.alignedPre(first, second, 500000099L, transfer.order()))
        assertNull(SolanaRpc.alignedPre(first, second, 500000104L, transfer.order()))
        assertNull(SolanaRpc.alignedPre(first, second, null, transfer.order()))
    }

    // ---- il filo -----------------------------------------------------------

    @Test fun `lo slot si legge dal contesto, e solo da li'`() {
        assertEquals(500000100L, SolanaRpc.slotOf(json("pre-transfer")))
        assertNull(SolanaRpc.slotOf(JSONObject("""{"jsonrpc":"2.0","result":[],"id":1}""")))
        assertNull(SolanaRpc.slotOf(JSONObject("""{"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.1.0"},"value":null},"id":1}""")))
    }

    @Test fun `un errore del nodo e' passeggero solo se parla del nodo`() {
        assertTrue(SolanaRpc.isTransient(JSONObject("""{"code":429,"message":"Too many requests"}""")))
        assertTrue(SolanaRpc.isTransient(JSONObject("""{"code":-32429,"message":"rate limited"}""")))
        assertTrue(!SolanaRpc.isTransient(JSONObject("""{"code":-32002,"message":"Transaction simulation failed: Blockhash not found"}""")))
        assertTrue(!SolanaRpc.isTransient(JSONObject("""{"code":-32602,"message":"Invalid param: WrongSize"}""")))
    }

    @Test fun `gia' processata vuol dire atterrata`() {
        assertTrue(SolanaRpc.isAlreadyProcessed(JSONObject("""{"code":-32002,"message":"Transaction simulation failed: This transaction has already been processed","data":{"err":"AlreadyProcessed","logs":[]}}""")))
        assertTrue(!SolanaRpc.isAlreadyProcessed(JSONObject("""{"code":-32002,"message":"Transaction simulation failed: Blockhash not found","data":{"err":"BlockhashNotFound","logs":[]}}""")))
    }

    @Test fun `il motivo del rifiuto e' l'ultima riga che lo dice`() {
        val err = JSONObject("""{"code":-32002,"message":"Transaction simulation failed: Error processing Instruction 0","data":{"logs":["Program 11111111111111111111111111111111 invoke [1]","Transfer: insufficient lamports 5000, need 1000000","Program 11111111111111111111111111111111 failed: custom program error: 0x1"]}}""")
        assertEquals("Program 11111111111111111111111111111111 failed: custom program error: 0x1", SolanaRpc.rejectionReason(err))
        assertEquals("Blockhash not found", SolanaRpc.rejectionReason(JSONObject("""{"code":-32002,"message":"Blockhash not found"}""")))
    }
}
