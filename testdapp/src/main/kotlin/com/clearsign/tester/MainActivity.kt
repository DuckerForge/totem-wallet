package com.clearsign.tester

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Deterministic dummy counterparties (structurally valid 32-byte keys).
private val RECIPIENT = ByteArray(32) { (it + 1).toByte() }
private val FEE_ADDR = ByteArray(32) { (60 + it).toByte() }
private val DELEGATE = ByteArray(32) { (120 + it).toByte() }
private val DUMMY_MINT = ByteArray(32) { (170 + it).toByte() }
private val DUMMY_TOKEN_ACCT = ByteArray(32) { (200 + it).toByte() }
// Real Solana incinerator (burn) address — must match ClearSign's assets/known_bad.json.
private val INCINERATOR = Base58.decode("1nc1nerator11111111111111111111111111111111")
// A wallet nobody has ever used: zero on-chain history (the drainer tell).
private val FRESH_WALLET = ByteArray(32) { (200 + it * 3 and 0xFF).toByte() }

/**
 * The attacks this tester can ask for. In English, like the wallet: the tester is on camera in
 * the demo video, and an Italian label next to an English receipt reads as two different apps.
 */
enum class Scenario(val title: String, val expected: String) {
    SIMPLE_TRANSFER("\uD83D\uDCB8 Send SOL", "A small amount of SOL leaves"),
    HIDDEN_FEE("\uD83D\uDD75\uFE0F Hidden fee", "One transfer plus a second, quiet one"),
    SPL_TRANSFER("\uD83E\uDE99 Send an SPL token", "One of your tokens, the smallest amount"),
    UNLIMITED_APPROVAL("\u267E\uFE0F Unlimited approval", "DANGER: spending rights with no cap"),
    SET_AUTHORITY("\uD83D\uDD11 Change authority", "DANGER: hands over control of an account"),
    CLOSE_ACCOUNT("\uD83D\uDDD1\uFE0F Close an account", "Closes a token account and takes the rent back"),
    MEMO_MULTI("\uD83E\uDDFE Memo, transfer, fee", "Several instructions at once, with a memo"),
    PRIORITY_FEE("\u26A1 Priority fee and transfer", "ComputeBudget plus a send"),
    NFT_MINT("\uD83C\uDFA8 Mint, best effort", "InitializeMint for a new token or NFT"),
    SWAP("\uD83D\uDD04 A real swap (Jupiter)", "SOL to USDC, v0 with lookup tables"),
    BUNDLE_3TX("\uD83D\uDCE6 Three at once", "Three transactions in one ask: you read them ALL, not just the first"),
    BURN_ADDRESS("\uD83D\uDD25 Send to a burn address", "DANGER: the recipient is on the blocklist"),
    DRAIN_ALL("\uD83E\uDDFA Drain the wallet", "DANGER: 95% of the balance to a wallet with no history"),
    ASSIGN_WALLET("\uD83C\uDFF4\u200D\u2620\uFE0F Give your wallet away", "DANGER: System Assign of your account to a program"),
    GASLESS("\uD83C\uDF81 Someone else pays the fee", "WARN: a stranger pays, you sign the transfer"),
    AGENT_HONEST("\uD83E\uDD16 Agent Gate, honest agent", "An AI agent declares a tiny send; the wallet checks claim against effect: they agree \u2713 (sign only)"),
    AGENT_LIAR("\uD83E\uDD16 Agent Gate, lying agent", "DANGER: the agent declares a SOL to USDC swap, the transaction is a send \u2192 blocked"),
    // The worst case at once: how the screen stacks when the warnings are six, not one, and
    // whether the amount, the map and the button stay reachable.
    ALL_ALARMS("\uD83D\uDEA8 Every alarm at once", "DANGER: burn address, unlimited approval, authority change, hidden fee, brand new wallet"),
}

class MainActivity : ComponentActivity() {

    private lateinit var sender: ActivityResultSender
    private val mwa by lazy {
        MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = Uri.parse("https://clearsign.tester"),
                iconUri = Uri.parse("favicon.ico"),
                identityName = "ClearSign Tester",
            ),
            timeout = 120_000,
        ).apply { blockchain = Solana.Mainnet }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var status by remember { mutableStateOf("Pick an attack. It goes to the wallet over Mobile Wallet Adapter.") }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("ClearSign Tester", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(Color(0x2233D17A)).padding(12.dp),
                        ) {
                            Text("MAINNET · SOLO FIRMA", color = Color(0xFF33D17A), fontSize = 13.sp)
                            Text("Le transazioni NON vengono inviate: nessuna spesa.", color = Color(0xFFBFC8D4), fontSize = 12.sp)
                        }
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Scenario.entries.forEach { s ->
                            Button(onClick = { run(s) { status = it } }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                Text(s.title, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun run(scenario: Scenario, setStatus: (String) -> Unit) {
        if (scenario == Scenario.AGENT_HONEST || scenario == Scenario.AGENT_LIAR) { runAgent(scenario, setStatus); return }
        setStatus("${scenario.title}\nExpected: ${scenario.expected}\n\nOpening the wallet… (sign only, nothing is sent)")
        lifecycleScope.launch {
            val result = mwa.transact(sender) { auth ->
                val feePayer = auth.publicKey
                val ownerB58 = Base58.encode(feePayer)
                val txs = buildTxs(scenario, feePayer, ownerB58)
                signTransactions(txs.toTypedArray())
            }
            setStatus(
                when (result) {
                    is TransactionResult.Success ->
                        "✅ ${scenario.title}\nClearSign ha firmato ${result.payload.signedPayloads.size} tx (NON inviata).\nAtteso: ${scenario.expected}"
                    is TransactionResult.NoWalletFound ->
                        "❌ Nessun wallet MWA. È installato ClearSign?"
                    is TransactionResult.Failure ->
                        "❌ ${scenario.title}\nRifiutata/errore: ${result.e.message}"
                },
            )
        }
    }

    /**
     * Agent Gate demo: this app plays the AI agent. It never holds a key: it asks the wallet (via
     * MWA authorize) which account to use, builds a transaction, and hands it to
     * apex://agent/sign with a declared intent. The wallet simulates the bytes and compares them with the claim. send=0: signature only.
     */
    private fun runAgent(scenario: Scenario, setStatus: (String) -> Unit) {
        setStatus("${scenario.title}\nAtteso: ${scenario.expected}\n\n1/2 chiedo a Apex quale wallet usare…")
        lifecycleScope.launch {
            val auth = mwa.transact(sender) { a -> a.publicKey }
            val feePayer = (auth as? TransactionResult.Success)?.payload ?: run { setStatus("❌ Connessione a Omni rifiutata o wallet assente."); return@launch }
            val ownerB58 = Base58.encode(feePayer)
            val tx = withContext(Dispatchers.IO) {
                val bh = MainnetRpc.latestBlockhash() ?: ByteArray(32)
                SolTxBuilder.build(feePayer, bh, listOf(SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 1_000L)))
            }
            val intent = org.json.JSONObject().apply {
                put("agent", "Tester Agent")
                if (scenario == Scenario.AGENT_HONEST) {
                    put("action", "transfer"); put("outMint", "SOL"); put("outAmount", 0.000001); put("to", Base58.encode(RECIPIENT))
                    put("reason", "Demo: micro-invio di prova dichiarato correttamente")
                } else {
                    put("action", "swap"); put("outMint", "SOL"); put("outAmount", 0.000001); put("inMint", "USDC"); put("inAmount", 0.0001)
                    put("reason", "Demo: the agent LIES. It declares a swap; the transaction is a send.")
                }
            }
            val uri = Uri.Builder().scheme("apex").authority("agent").path("/sign")
                .appendQueryParameter("tx", android.util.Base64.encodeToString(tx, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                .appendQueryParameter("intent", intent.toString())
                .appendQueryParameter("account", ownerB58)
                .appendQueryParameter("send", "0")
                .build()
            setStatus("${scenario.title}\n2/2 opening Agent Gate with the declared intent:\n${intent.getString("action")} ${intent.optString("outAmount")} ${intent.optString("outMint")}" + (if (scenario == Scenario.AGENT_LIAR) " → USDC (false)" else " → ${Base58.encode(RECIPIENT).take(6)}…"))
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                .onFailure { setStatus("❌ The wallet is not installed, or Agent Gate is unavailable: ${it.message}") }
        }
    }

    private suspend fun buildTxs(scenario: Scenario, feePayer: ByteArray, ownerB58: String): List<ByteArray> =
        withContext(Dispatchers.IO) {
            if (scenario == Scenario.SWAP) {
                return@withContext listOf(
                    MainnetRpc.jupiterSwapTx(ownerB58) ?: error("Jupiter non ha restituito una tx (riprova)"),
                )
            }
            val blockhash = MainnetRpc.latestBlockhash() ?: ByteArray(32)

            // A real bundle: three independent transactions signed together. Each
            // must be reviewable in ClearSign — this is the multi-tx test.
            if (scenario == Scenario.BUNDLE_3TX) {
                return@withContext listOf(
                    listOf(SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 4_000L)),
                    listOf(SolTxBuilder.systemTransfer(feePayer, FEE_ADDR, 2_000L)),
                    listOf(SolTxBuilder.memo("Bundle tx #3"), SolTxBuilder.systemTransfer(feePayer, DELEGATE, 1_000L)),
                ).map { SolTxBuilder.build(feePayer, blockhash, it) }
            }
            // Real token account when available, else a dummy (still shows the receipt).
            val tok = MainnetRpc.tokenAccounts(ownerB58).firstOrNull()
            val tAcct = tok?.let { Base58.decode(it.pubkey) } ?: DUMMY_TOKEN_ACCT
            val tMint = tok?.let { Base58.decode(it.mint) } ?: DUMMY_MINT
            val tDec = tok?.decimals ?: 0

            if (scenario == Scenario.GASLESS) {
                // Someone else is the fee payer (index 0); the wallet signs at index 1.
                val ixs = listOf(SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 2_000L))
                return@withContext listOf(SolTxBuilder.build(FEE_ADDR, blockhash, ixs))
            }
            val ixs = when (scenario) {
                Scenario.DRAIN_ALL -> {
                    val bal = MainnetRpc.balance(ownerB58) ?: 10_000_000L
                    listOf(SolTxBuilder.systemTransfer(feePayer, FRESH_WALLET, (bal * 95) / 100))
                }
                Scenario.ASSIGN_WALLET -> listOf(SolTxBuilder.systemAssign(feePayer, DELEGATE))
                Scenario.SIMPLE_TRANSFER -> listOf(SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 1_000L))
                Scenario.HIDDEN_FEE -> listOf(
                    SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 20_000L),
                    SolTxBuilder.systemTransfer(feePayer, FEE_ADDR, 7_500L),
                )
                Scenario.SPL_TRANSFER -> listOf(
                    SolTxBuilder.tokenTransferChecked(tAcct, tMint, RECIPIENT, feePayer, 1L, tDec),
                )
                Scenario.UNLIMITED_APPROVAL -> listOf(
                    SolTxBuilder.tokenApproveUnlimited(tAcct, DELEGATE, feePayer),
                )
                Scenario.SET_AUTHORITY -> listOf(
                    SolTxBuilder.tokenSetAuthority(tAcct, feePayer, DELEGATE),
                )
                Scenario.CLOSE_ACCOUNT -> listOf(
                    SolTxBuilder.tokenCloseAccount(tAcct, feePayer, feePayer),
                )
                Scenario.MEMO_MULTI -> listOf(
                    SolTxBuilder.memo("Pagamento di prova ClearSign"),
                    SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 5_000L),
                    SolTxBuilder.systemTransfer(feePayer, FEE_ADDR, 1_500L),
                )
                Scenario.PRIORITY_FEE -> listOf(
                    SolTxBuilder.setComputeUnitPrice(50_000L),
                    SolTxBuilder.systemTransfer(feePayer, RECIPIENT, 3_000L),
                )
                Scenario.NFT_MINT -> listOf(
                    SolTxBuilder.initializeMint(DUMMY_MINT, feePayer, decimals = 0),
                )
                Scenario.BURN_ADDRESS -> listOf(
                    SolTxBuilder.systemTransfer(feePayer, INCINERATOR, 1_000L),
                )
                // Five things wrong in one transaction, any one of which would stop it alone.
                Scenario.ALL_ALARMS -> listOf(
                    SolTxBuilder.systemTransfer(feePayer, INCINERATOR, 1_000L),
                    SolTxBuilder.systemTransfer(feePayer, FRESH_WALLET, 12_000L),
                    SolTxBuilder.systemTransfer(feePayer, FEE_ADDR, 7_500L),
                    SolTxBuilder.tokenApproveUnlimited(tAcct, DELEGATE, feePayer),
                    SolTxBuilder.tokenSetAuthority(tAcct, feePayer, DELEGATE),
                )
                Scenario.SWAP, Scenario.BUNDLE_3TX, Scenario.GASLESS, Scenario.AGENT_HONEST, Scenario.AGENT_LIAR -> emptyList() // handled above
            }
            listOf(SolTxBuilder.build(feePayer, blockhash, ixs))
        }
}
