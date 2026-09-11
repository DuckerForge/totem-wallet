package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressTrustTest {

    private val alice = "AL1CExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxWALL"
    private val trust = AddressTrust(
        allowlist = mapOf(alice to "Alice"),
        history = setOf("H1STORYxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxSEEN"),
        flagged = setOf("BADxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxDRAIN"),
        sanctioned = setOf("0FACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxLIST"),
    )

    @Test fun trustLevels() {
        assertEquals(TrustLevel.TRUSTED, trust.level(alice))
        assertEquals(TrustLevel.KNOWN, trust.level("H1STORYxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxSEEN"))
        assertEquals(TrustLevel.NEW, trust.level("Someone000000000000000000000000000000NEW"))
        assertEquals(TrustLevel.FLAGGED, trust.level("BADxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxDRAIN"))
        assertEquals(TrustLevel.FLAGGED, trust.level("0FACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxLIST"))
    }

    @Test fun detectsAddressPoisoningLookalike() {
        // Same shown ends as Alice (AL1C … WALL) but a different full address.
        val poison = "AL1C000000000000000000000000000000000WALL"
        assertEquals(alice, trust.lookalikeOf(poison))
    }

    @Test fun realKnownAddressIsNotFlaggedAsLookalike() {
        assertNull(trust.lookalikeOf(alice))
    }

    @Test fun unrelatedAddressHasNoLookalike() {
        assertNull(trust.lookalikeOf("Zebra999999999999999999999999999999QRST"))
    }
}

class RiskEngineTest {
    private val engine = RiskEngine()
    private val trust = AddressTrust(
        allowlist = mapOf("Merchant0000000000000000000000000000GOOD" to "Coffee Shop"),
        history = emptySet(),
    )

    @Test fun unlimitedApprovalIsDanger() {
        val risks = engine.assess(
            listOf(DecodedInstruction(InstructionKind.TOKEN_APPROVE, "TokenkegQ", isUnlimitedApproval = true)),
            trust,
        )
        assertTrue(risks.any { it.flag == RiskFlag.UNLIMITED_APPROVAL && it.severity == Severity.DANGER })
    }

    @Test fun setAuthorityIsDanger() {
        val risks = engine.assess(
            listOf(DecodedInstruction(InstructionKind.SET_AUTHORITY, "TokenkegQ")),
            trust,
        )
        assertTrue(risks.any { it.flag == RiskFlag.AUTHORITY_CHANGE && it.severity == Severity.DANGER })
    }

    @Test fun failedSimulationBlocks() {
        val risks = engine.assess(emptyList(), trust, simulationSucceeded = false)
        assertTrue(risks.any { it.flag == RiskFlag.SIMULATION_FAILED && it.severity == Severity.DANGER })
    }

    @Test fun newRecipientIsWarnNotDanger() {
        val risks = engine.assess(
            listOf(DecodedInstruction(InstructionKind.SOL_TRANSFER, "11111111", destination = "Fresh00000000000000000000000000000NEW1")),
            trust,
        )
        val flag = risks.single { it.flag == RiskFlag.NEW_UNKNOWN_RECIPIENT }
        assertEquals(Severity.WARN, flag.severity)
    }

    @Test fun trustedRecipientHasNoWarning() {
        val risks = engine.assess(
            listOf(DecodedInstruction(InstructionKind.SPL_TRANSFER, "TokenkegQ", destination = "Merchant0000000000000000000000000000GOOD")),
            trust,
        )
        assertTrue(risks.none { it.flag == RiskFlag.NEW_UNKNOWN_RECIPIENT })
    }

    @Test fun maliciousScanBlocks() {
        val risks = engine.assess(
            emptyList(), trust,
            scan = ScanResult(malicious = true, reason = "Known wallet drainer"),
        )
        assertTrue(risks.any { it.flag == RiskFlag.BLOCKED_MALICIOUS && it.severity == Severity.DANGER })
    }
}

class EffectRiskTest {
    private val engine = RiskEngine()
    private val me = "Me000000000000000000000000000000000000ME"
    private val fresh = "Fresh00000000000000000000000000000NEW1"
    private val noTrust = AddressTrust()

    @Test fun drainToBrandNewWalletIsDanger() {
        val deltas = listOf(BalanceDelta(me, NATIVE_SOL_MINT, "SOL", 9, -950_000_000), BalanceDelta(fresh, NATIVE_SOL_MINT, "SOL", 9, 950_000_000))
        val ctx = EffectContext(me, preBalances = mapOf(NATIVE_SOL_MINT to 1_000_000_000), primaryRecipient = fresh, recipientBrandNew = true)
        val risks = engine.assessEffects(deltas, ctx, noTrust)
        assertEquals(Severity.DANGER, risks.single { it.flag == RiskFlag.DRAINS_BALANCE }.severity)
        assertTrue(risks.any { it.flag == RiskFlag.BRAND_NEW_RECIPIENT })
    }

    @Test fun drainToTrustedContactIsOnlyWarn() {
        val trust = AddressTrust(allowlist = mapOf(fresh to "my new wallet"))
        val deltas = listOf(BalanceDelta(me, NATIVE_SOL_MINT, "SOL", 9, -950_000_000))
        val ctx = EffectContext(me, preBalances = mapOf(NATIVE_SOL_MINT to 1_000_000_000), primaryRecipient = fresh, recipientBrandNew = true)
        val risks = engine.assessEffects(deltas, ctx, trust)
        assertEquals(Severity.WARN, risks.single { it.flag == RiskFlag.DRAINS_BALANCE }.severity)
        assertFalse(risks.any { it.flag == RiskFlag.BRAND_NEW_RECIPIENT })
    }

    @Test fun smallPaymentIsNotADrain() {
        val deltas = listOf(BalanceDelta(me, NATIVE_SOL_MINT, "SOL", 9, -10_000_000))
        val ctx = EffectContext(me, preBalances = mapOf(NATIVE_SOL_MINT to 1_000_000_000), primaryRecipient = fresh)
        assertTrue(engine.assessEffects(deltas, ctx, noTrust).none { it.flag == RiskFlag.DRAINS_BALANCE })
    }

    @Test fun foreignFeePayerWarns() {
        val ctx = EffectContext(me, feePayer = "Relayer000000000000000000000000000000000")
        assertTrue(engine.assessEffects(emptyList(), ctx, noTrust).any { it.flag == RiskFlag.FOREIGN_FEE_PAYER && it.severity == Severity.WARN })
    }

    @Test fun assigningOwnWalletIsDangerButAssigningAnotherAccountIsNot() {
        val mine = engine.assess(listOf(DecodedInstruction(InstructionKind.ASSIGN_OWNER, "1111", destination = "Prog", subject = me)), noTrust, myWallet = me)
        assertTrue(mine.any { it.flag == RiskFlag.WALLET_OWNER_CHANGE && it.severity == Severity.DANGER })
        val other = engine.assess(listOf(DecodedInstruction(InstructionKind.ASSIGN_OWNER, "1111", destination = "Prog", subject = fresh)), noTrust, myWallet = me)
        assertTrue(other.none { it.flag == RiskFlag.WALLET_OWNER_CHANGE })
    }

    @Test fun durableNonceWarnsAndItalianTextIsUsed() {
        val risks = RiskEngine("it").assess(listOf(DecodedInstruction(InstructionKind.DURABLE_NONCE, "1111")), noTrust)
        val r = risks.single { it.flag == RiskFlag.DURABLE_NONCE }
        assertEquals(Severity.WARN, r.severity)
        assertTrue(r.detail.contains("durable nonce"))
    }
}

class SimulationGuardTest {
    private fun d(owner: String, amt: Long) = BalanceDelta(owner, "USDC", "USDC", 6, amt)

    @Test fun noDriftWhenEffectsMatch() {
        val a = listOf(d("me", -50_000_000), d("shop", 50_000_000))
        val b = listOf(d("shop", 50_000_000), d("me", -50_000_000)) // reordered, same net
        assertFalse(SimulationGuard.hasDrifted(a, b))
    }

    @Test fun driftWhenAmountChanges() {
        val a = listOf(d("me", -50_000_000))
        val b = listOf(d("me", -500_000_000)) // 10x drain after preview
        assertTrue(SimulationGuard.hasDrifted(a, b))
        assertEquals(RiskFlag.STATE_DRIFT, SimulationGuard.confirm(a, b).risk?.flag)
    }

    @Test fun tinySwapVarianceIsNotDrift() {
        // A swap re-simulated a slot later moves by a few units: within 1% is fine.
        val a = listOf(d("me", -50_000_000), BalanceDelta("me", "SOL", "SOL", 9, 331_000_000))
        val b = listOf(d("me", -50_000_000), BalanceDelta("me", "SOL", "SOL", 9, 330_500_000))
        assertFalse(SimulationGuard.hasDrifted(a, b))
    }

    @Test fun newPartyOrSignFlipIsDrift() {
        val a = listOf(d("me", -50_000_000), d("shop", 50_000_000))
        assertTrue(SimulationGuard.hasDrifted(a, a + d("thief", 1)))   // a new receiver appeared
        assertTrue(SimulationGuard.hasDrifted(listOf(d("me", 10)), listOf(d("me", -10)))) // inflow became outflow
    }
}

class ReceiptBuilderTest {
    private val trust = AddressTrust(allowlist = mapOf("Mario00000000000000000000000000000FREND" to "Mario"))
    private val builder = ReceiptBuilder(trust)

    private fun receipt(): Receipt = builder.build(
        myWallet = "ME000000000000000000000000000000000MINE",
        deltas = listOf(
            BalanceDelta("ME000000000000000000000000000000000MINE", "USDC", "USDC", 6, -50_000_000),
            BalanceDelta("Mario00000000000000000000000000000FREND", "USDC", "USDC", 6, 50_000_000),
        ),
        instructions = listOf(
            DecodedInstruction(InstructionKind.SPL_TRANSFER, "TokenkegQ", destination = "Mario00000000000000000000000000000FREND", amountRaw = 50_000_000),
        ),
        feeLamports = 5_000,
        risks = emptyList(),
    )

    @Test fun separatesOutflowsAndResolvesTrustedRecipient() {
        val r = receipt()
        assertEquals(1, r.outflows.size)
        assertEquals("Mario", r.recipientLabel)
        assertEquals(TrustLevel.TRUSTED, r.recipientTrust)
        assertFalse(r.blocksApproval)
    }

    @Test fun rendersLocalizedPlainLanguage() {
        val r = receipt()
        val en = ReceiptBuilder.render(r, "en")
        val it = ReceiptBuilder.render(r, "it")
        val es = ReceiptBuilder.render(r, "es")
        assertTrue(en.any { it.contains("You send 50.000000 USDC to Mario (trusted contact)") }, "EN: $en")
        assertTrue(it.any { line -> line.contains("Invii 50.000000 USDC a Mario (contatto fidato)") }, "IT: $it")
        assertTrue(es.any { it.contains("Envías 50.000000 USDC a Mario (contacto de confianza)") }, "ES: $es")
    }

    private val me = "ME000000000000000000000000000000000MINE"
    private val shop = "Shop00000000000000000000000000000000STORE"
    private val fee1 = "Fee100000000000000000000000000000000WALL"
    private val fee2 = "Fee200000000000000000000000000000000WALL"

    /** A SOL payment that fans out to a main recipient + two hidden fee wallets. */
    private fun splitReceipt(): Receipt = builder.build(
        myWallet = me,
        deltas = listOf(
            BalanceDelta(me, "SOL", "SOL", 9, -1_000_000_000),   // pays 1 SOL
            BalanceDelta(shop, "SOL", "SOL", 9, 700_000_000),    // 0.7 SOL to the shop
            BalanceDelta(fee1, "SOL", "SOL", 9, 200_000_000),    // 0.2 SOL fee
            BalanceDelta(fee2, "SOL", "SOL", 9, 100_000_000),    // 0.1 SOL fee
        ),
        instructions = emptyList(),
        feeLamports = 5_000,
        risks = emptyList(),
    )

    @Test fun revealsFeeSplitAcrossWallets() {
        val r = splitReceipt()
        assertTrue(r.isSplit)
        assertEquals(3, r.distributions.size)
        // largest first, and it is the non-fee main recipient
        assertEquals(shop, r.distributions[0].address)
        assertFalse(r.distributions[0].isFee)
        assertEquals(0.7, r.distributions[0].share, 1e-9)
        // the two smaller same-mint receivers are flagged as fees
        assertTrue(r.distributions[1].isFee)
        assertTrue(r.distributions[2].isFee)
        assertEquals(shop, r.primaryRecipient)
    }

    @Test fun singleRecipientIsNotASplit() {
        val r = receipt()
        assertFalse(r.isSplit)
        assertEquals(1, r.distributions.size)
        assertFalse(r.distributions[0].isFee)
    }
}

/** End-to-end flow through the ports, with in-memory fakes for the device/network. */
class ClearSignFlowTest {
    private val me = "ME000000000000000000000000000000000MINE"
    private val mario = "Mario00000000000000000000000000000FREND"
    private val poison = "Mario11111111111111111111111111111FREND" // shares Mari…REND with Mario

    private fun flow(
        decoded: List<DecodedInstruction>,
        previewDeltas: List<BalanceDelta>?,
        approveDeltas: List<BalanceDelta>? = previewDeltas,
        scan: ScanResult = ScanResult(),
        trust: AddressTrust = AddressTrust(allowlist = mapOf(mario to "Mario")),
    ): ClearSignFlow {
        val sims = ArrayDeque(listOf(previewDeltas, approveDeltas))
        return ClearSignFlow(
            decoder = object : TransactionDecoder { override fun decode(serializedTx: ByteArray) = decoded },
            simulator = object : Simulator { override fun simulate(serializedTx: ByteArray) = if (sims.isEmpty()) approveDeltas else sims.removeFirst() },
            scanner = object : TransactionScanner { override fun scan(serializedTx: ByteArray, recipients: List<String>) = scan },
            signer = object : HardwareSigner {
                override fun publicKey() = me
                override fun sign(serializedTx: ByteArray) = byteArrayOf(1, 2, 3)
            },
            trust = trust,
        )
    }

    private val tx = byteArrayOf(0)

    @Test fun happyPathSigns() {
        val deltas = listOf(
            BalanceDelta(me, "USDC", "USDC", 6, -50_000_000),
            BalanceDelta(mario, "USDC", "USDC", 6, 50_000_000),
        )
        val f = flow(listOf(DecodedInstruction(InstructionKind.SPL_TRANSFER, "Tok", destination = mario, amountRaw = 50_000_000)), deltas)
        val preview = f.preview(tx, 5_000)
        assertFalse(preview.receipt.blocksApproval)
        val outcome = f.approveAndSign(tx, preview)
        assertTrue(outcome is ClearSignFlow.SignOutcome.Signed)
    }

    @Test fun poisoningRecipientRefusedEvenIfUserTapsOk() {
        val deltas = listOf(BalanceDelta(me, "USDC", "USDC", 6, -50_000_000))
        val f = flow(
            listOf(DecodedInstruction(InstructionKind.SPL_TRANSFER, "Tok", destination = poison, amountRaw = 50_000_000)),
            deltas,
        )
        val preview = f.preview(tx, 5_000)
        assertTrue(preview.receipt.blocksApproval, "poisoning look-alike must block")
        val outcome = f.approveAndSign(tx, preview)
        assertTrue(outcome is ClearSignFlow.SignOutcome.Refused)
        assertEquals(RiskFlag.LOOKALIKE_ADDRESS, (outcome as ClearSignFlow.SignOutcome.Refused).risk.flag)
    }

    @Test fun toctouDriftRefusedAtSigning() {
        val preview = listOf(BalanceDelta(me, "USDC", "USDC", 6, -50_000_000), BalanceDelta(mario, "USDC", "USDC", 6, 50_000_000))
        val drained = listOf(BalanceDelta(me, "USDC", "USDC", 6, -5_000_000_000)) // huge drain at approval
        val f = flow(
            listOf(DecodedInstruction(InstructionKind.SPL_TRANSFER, "Tok", destination = mario, amountRaw = 50_000_000)),
            previewDeltas = preview,
            approveDeltas = drained,
        )
        val p = f.preview(tx, 5_000)
        assertFalse(p.receipt.blocksApproval)
        val outcome = f.approveAndSign(tx, p)
        assertTrue(outcome is ClearSignFlow.SignOutcome.Refused)
        assertEquals(RiskFlag.STATE_DRIFT, (outcome as ClearSignFlow.SignOutcome.Refused).risk.flag)
    }
}
