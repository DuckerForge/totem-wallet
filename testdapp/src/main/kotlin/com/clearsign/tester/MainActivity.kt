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

enum class Scenario(val title: String, val expected: String) {
    SIMPLE_TRANSFER("💸 Trasferimento SOL", "Invii una piccola somma di SOL"),
    HIDDEN_FEE("🕵️ Fee nascosta", "Un invio + una 2ª uscita 'nascosta'"),
    SPL_TRANSFER("🪙 Trasferimento token SPL", "Invii un tuo token (importo minimo)"),
    UNLIMITED_APPROVAL("♾️ Approvazione illimitata", "DANGER: delega di spesa senza limiti"),
    SET_AUTHORITY("🔑 Cambio autorità", "DANGER: cede il controllo di un account"),
    CLOSE_ACCOUNT("🗑️ Chiusura account", "Chiude un token account e recupera il rent"),
    MEMO_MULTI("🧾 Memo + transfer + fee", "Multi-istruzione con memo e fee"),
    PRIORITY_FEE("⚡ Priority fee + transfer", "ComputeBudget (fee di priorità) + invio"),
    NFT_MINT("🎨 Mint (best-effort)", "InitializeMint di un nuovo token/NFT"),
    SWAP("🔄 Swap reale (Jupiter)", "Swap SOL→USDC (v0 + lookup tables)"),
    BUNDLE_3TX("📦 Bundle 3 tx", "3 transazioni in un colpo: le verifichi TUTTE, non solo la 1ª"),
    BURN_ADDRESS("🔥 Invio a indirizzo di burn", "DANGER: destinatario in blocklist (incinerator)"),
    DRAIN_ALL("🪣 Svuota il wallet", "DANGER: il 95% del saldo a un wallet senza storico"),
    ASSIGN_WALLET("🏴‍☠️ Cede il tuo wallet", "DANGER: System Assign del tuo account a un programma"),
    GASLESS("🎁 Fee pagate da altri", "WARN: fee payer estraneo, tu firmi il trasferimento"),
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
                    var status by remember { mutableStateOf("Scegli uno scenario → va a ClearSign via MWA.") }
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
        setStatus("${scenario.title}\nAtteso: ${scenario.expected}\n\nApro ClearSign… (solo firma, non invio)")
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
                Scenario.SWAP, Scenario.BUNDLE_3TX, Scenario.GASLESS -> emptyList() // handled above
            }
            listOf(SolTxBuilder.build(feePayer, blockhash, ixs))
        }
}
