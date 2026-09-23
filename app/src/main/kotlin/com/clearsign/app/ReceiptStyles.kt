package com.clearsign.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.Receipt
import com.clearsign.core.Severity

/**
 * Alternative receipt layouts, one per theme family. Same data as the card
 * layout (hero, risks, split map, details, stats) — a different object:
 * a paper till receipt for Ember, a terminal log for Phosphor.
 */

// ---- shared pieces ------------------------------------------------------------

@Composable
private fun SplitMapBox(dests: List<NodeDest>, danger: Boolean, coin: ImageBitmap? = null, backCoin: ImageBitmap? = null, onTap: (NodeDest) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(if (dests.size > 2) 250.dp else 200.dp).clip(rs(18)).background(Halo.cardSoft)
            .background(Brush.radialGradient(colors = listOf((if (danger) Halo.red else Halo.cyan).copy(alpha = 0.10f), Color.Transparent), radius = 520f))
            .haloBorder(rs(18)),
    ) {
        NodeMap(dests = dests, danger = danger, coin = coin, backCoin = backCoin) { d -> if (d.address != null) onTap(d) }
        Text(stringResource(R.string.tap_node), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

@Composable
private fun verdictOf(r: Receipt): Pair<String, Color> = when (r.highestSeverity) {
    Severity.DANGER -> stringResource(R.string.verdict_danger) to Halo.red
    Severity.WARN -> stringResource(R.string.verdict_warn) to Halo.amber
    Severity.INFO -> stringResource(R.string.verdict_ok) to Halo.mint
}

// ---- PAPER (Ember) -------------------------------------------------------------

@Composable
private fun DashedRule() {
    val col = Halo.muted.copy(alpha = 0.55f)
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(col, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)))
    }
}

/** The torn bottom edge of a till receipt. */
@Composable
private fun ZigzagEdge(color: Color) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val w = size.width; val h = size.height; val tooth = 12f
        val path = Path().apply {
            moveTo(0f, 0f); var x = 0f; var up = false
            while (x < w) { x += tooth; lineTo(x, if (up) 0f else h); up = !up }
            lineTo(w, 0f); close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PaperRow(label: String, value: String, valueColor: Color = Halo.ink, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("·".repeat(60), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted.copy(alpha = 0.4f), maxLines = 1, modifier = Modifier.weight(1f).clipToBounds())
        Spacer(Modifier.width(6.dp))
        Text(value, fontFamily = Sora, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, color = valueColor, style = Tabular, maxLines = 1)
    }
}

@Composable
internal fun PaperReceipt(r: Receipt, dests: List<NodeDest>, danger: Boolean, backCoin: ImageBitmap? = null, onTap: (NodeDest) -> Unit) {
    val accent = if (danger) Halo.red else Halo.mint
    val paper = Halo.cardSoft
    Column(Modifier.staggeredEntrance(0, r)) {
        Column(
            Modifier.fillMaxWidth().clip(rs(6)).background(paper).haloBorder(rs(6)).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.paper_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Halo.ink, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(stringResource(R.string.app_name) + " · " + stringResource(R.string.tagline), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            DashedRule()
            // amounts
            Text(stringResource(R.string.pay).uppercase(), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
            if (r.outflows.isEmpty()) Text(stringResource(R.string.no_transfer), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
            else r.outflows.forEachIndexed { i, d -> AmountText("−", d, 30.sp, accent, countUp = i == 0) }
            if (r.inflows.isNotEmpty()) {
                Text(stringResource(R.string.receive).uppercase(), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
                r.inflows.forEach { AmountText("+", it, 19.sp, Halo.cyan, FontWeight.SemiBold) }
            }
            DashedRule()
            // risks
            if (r.risks.isNotEmpty()) {
                Text(stringResource(R.string.risk_hdr_warn).uppercase(), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = sevColor(r.highestSeverity))
                r.risks.forEach { risk ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("!", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = sevColor(risk.severity)); Spacer(Modifier.width(8.dp))
                        Column { Text(stringResource(riskTitle(risk.flag)), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink); Text(risk.detail, fontFamily = Inter, fontSize = 12.sp, color = Halo.muted) }
                    }
                }
                DashedRule()
            }
            // where the funds go
            if (dests.isNotEmpty()) {
                Text(stringResource(R.string.split_hdr).uppercase(), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
                dests.forEach { d -> PaperRow(d.label + (if (d.isFee) " · " + stringResource(R.string.fee_tag) else ""), d.deltaText, d.color, onClick = if (d.address != null) ({ onTap(d) }) else null) }
                DashedRule()
            }
            PaperRow(stringResource(R.string.network_fee), fmtSol(r.feeLamports, 6) + " SOL")
            val (vTxt, vCol) = verdictOf(r)
            PaperRow(stringResource(R.string.verdict), vTxt, vCol, bold = true)
            DashedRule()
            Text(stringResource(R.string.paper_footer), fontFamily = Mono, fontSize = 10.sp, color = Halo.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        ZigzagEdge(paper)
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.staggeredEntrance(1, r)) { SplitMapBox(dests, danger, rememberCoinBitmap(r.outflows.firstOrNull()?.mint), backCoin, onTap) }
    if (r.calls.isNotEmpty()) { Spacer(Modifier.height(12.dp)); CallsCard(r.calls) }
}

// ---- TERMINAL (Phosphor) ---------------------------------------------------------

private class TermLine(val text: String, val color: Color? = null, val dest: NodeDest? = null)

/** Lines appear as if typed: a reveal keyed on the receipt, caret on the last visible line. */
@Composable
private fun TypewriterLines(lines: List<TermLine>, key: Any, onTap: (NodeDest) -> Unit) {
    val total = lines.sumOf { it.text.length + 1 }
    val p = rememberReveal(key, durationMs = (total * 14).coerceIn(300, 2400))
    val shown = (total * p).toInt()
    var consumed = 0
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEachIndexed { i, l ->
            val start = consumed; consumed += l.text.length + 1
            if (start >= shown) return@forEachIndexed
            val visible = l.text.take((shown - start).coerceIn(0, l.text.length))
            val last = consumed > shown
            Row(Modifier.then(if (l.dest?.address != null) Modifier.clickable { onTap(l.dest) } else Modifier), verticalAlignment = Alignment.CenterVertically) {
                Text(visible, fontFamily = Mono, fontSize = 12.5.sp, color = l.color ?: Halo.ink, maxLines = 2)
                if (last || i == lines.lastIndex) { Spacer(Modifier.width(3.dp)); BlinkCaret(Halo.mint, 13.dp, 7.dp) }
            }
        }
    }
}

@Composable
internal fun TerminalReceipt(r: Receipt, dests: List<NodeDest>, danger: Boolean, backCoin: ImageBitmap? = null, onTap: (NodeDest) -> Unit) {
    val ctx = LocalContext.current
    val accent = if (danger) Halo.red else Halo.mint
    val (vTxt, vCol) = verdictOf(r)
    val lines = remember(r) {
        buildList {
            add(TermLine("$ clearsign --receipt", Halo.muted))
            if (r.outflows.isEmpty()) add(TermLine("> " + ctx.getString(R.string.pay).lowercase().padEnd(9) + ctx.getString(R.string.no_transfer), Halo.ink))
            else r.outflows.forEach { add(TermLine("> " + ctx.getString(R.string.pay).lowercase().padEnd(9) + "−" + fmtAmt(it), accent)) }
            r.inflows.forEach { add(TermLine("> " + ctx.getString(R.string.receive).lowercase().padEnd(9) + "+" + fmtAmt(it), Halo.cyan)) }
            dests.forEach { d -> add(TermLine("> " + ctx.getString(R.string.to_label).lowercase().padEnd(9) + d.label + " [" + (d.trust?.name ?: "NEW") + "]" + (if (d.isFee) " fee" else "") + "  " + d.deltaText, Halo.ink, d)) }
            add(TermLine("> fee      " + fmtSol(r.feeLamports, 6) + " SOL", Halo.muted))
            r.risks.forEach { add(TermLine("> " + it.severity.name.lowercase().padEnd(9) + ctx.getString(riskTitle(it.flag)) + " — " + it.detail, sevColor(it.severity))) }
            add(TermLine("> " + (ctx.getString(R.string.verdict).lowercase() + " ").padEnd(10) + vTxt.uppercase(), vCol))
        }
    }
    Column(
        Modifier.staggeredEntrance(0, r).fillMaxWidth().clip(rs(4)).background(Halo.ground.copy(alpha = 0.6f)).haloBorder(rs(4)).padding(14.dp),
    ) {
        Text("+" + "-".repeat(80), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted.copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.fillMaxWidth().clipToBounds())
        Spacer(Modifier.height(6.dp))
        TypewriterLines(lines, r, onTap)
        Spacer(Modifier.height(6.dp))
        Text("+" + "-".repeat(80), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted.copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.fillMaxWidth().clipToBounds())
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.staggeredEntrance(1, r)) { SplitMapBox(dests, danger, rememberCoinBitmap(r.outflows.firstOrNull()?.mint), backCoin, onTap) }
    if (r.calls.isNotEmpty()) { Spacer(Modifier.height(12.dp)); CallsCard(r.calls) }
}
