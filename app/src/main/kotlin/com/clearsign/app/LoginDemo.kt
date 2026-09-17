package com.clearsign.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Il marchio, fatto da due telefoni.
 *
 * Due telefoni arrivano dal buio e si posano inclinati finche' non formano la V
 * del logo. Poi il tratto al neon si disegna da solo attraverso di loro, dal
 * viola in basso al ciano in alto, e resta acceso un momento prima che tutto
 * ricominci.
 *
 * Non e' decorazione. La V del marchio **e' due telefoni che si toccano**, cioe'
 * la cosa che quest'app sa fare e le altre no: avvicinarne due e far passare dei
 * soldi senza indirizzi, senza link, senza nessuno in mezzo. La schermata su cui
 * gira e' quella dove non c'e' niente da fare tranne aspettare un'impronta, ed e'
 * l'unica animazione dell'app che parte senza che nessuno l'abbia chiesta.
 *
 * Prima c'era un pianeta con un campo stellato, una meteora e tre richieste in
 * arrivo. Diceva una cosa giusta e la diceva con troppa roba: sei elementi che si
 * muovono e nessuno che sia il marchio. Le regole di adesso: niente stelle,
 * niente cose che cadono, un solo movimento alla volta, e un respiro di silenzio
 * alla fine del giro, cosi' la V resta negli occhi invece di scorrere via.
 */
@Composable
internal fun GateDemo(modifier: Modifier = Modifier, opening: Boolean = false) {
    val loop = rememberInfiniteTransition(label = "velum")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(8200, easing = LinearEasing)), label = "t")

    // Il marchio vero, non un disegno che gli somiglia. E' la stessa immagine che
    // sta sul lanciatore: se un giorno cambia, cambia anche qui da sola.
    val mark = ImageBitmap.imageResource(R.mipmap.brand_bird)

    val open = remember { Animatable(0f) }
    LaunchedEffect(opening) { if (opening) open.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    Canvas(modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val op = open.value

        // Il marchio vive in un quadrato suo, non nella scatola che gli tocca:
        // quella qui e' `weight(1f)` e si prende tutta l'altezza che avanza.
        val d = min(w * 0.46f, h * 0.62f)
        val cx = w / 2f
        val cy = h * 0.17f

        val vertex = Offset(cx, cy + d * 0.30f)
        val armX = d * 0.34f
        val armY = d * 0.56f
        val leftTip = Offset(cx - armX, vertex.y - armY)
        val rightTip = Offset(cx + armX, vertex.y - armY)
        val armLen = hypot(armX, armY)

        // --- i tempi, che sono il racconto ---------------------------------
        //
        // Due telefoni arrivano e si mettono in V. Restano fermi un attimo,
        // perche' una cosa che non si ferma mai non la vedi. Poi il braccio
        // destro si accende, e nella luce i telefoni se ne vanno e resta il
        // marchio: non un dissolvenza incrociata, che sarebbe uno scambio di
        // figurine, ma una cosa che diventa l'altra dentro un lampo.
        val land = ease(seg(t, 0.00f, 0.26f))
        val hold = seg(t, 0.30f, 0.40f)
        val spark = ease(seg(t, 0.40f, 0.56f))
        val become = ease(seg(t, 0.50f, 0.66f))
        val fade = seg(t, 0.92f, 1.00f)

        // L'apertura della porta non salta il racconto, lo lascia finire e poi
        // ritira tutto.
        //
        // Prima `opening` spingeva la scena dritta al marchio: telefoni a zero,
        // logo a uno. Ma quella bandiera resta accesa mentre la porta prova lo
        // sblocco, e dopo un'impronta non riconosciuta ci restava. Risultato: i
        // telefoni non si vedevano piu' **mai**, e la porta mostrava un logo fermo.
        // Adesso il giro va per conto suo e l'apertura lo sfuma via.
        val leaving = 1f - op * 0.85f
        val phoneAlpha = land * (1f - become) * leaving
        val markAlpha = become * (1f - fade * 0.55f) * leaving
        val glow = spark * (1f - seg(t, 0.72f, 0.92f) * 0.6f)

        // --- il respiro dietro ---------------------------------------------
        drawCircle(
            Brush.radialGradient(
                listOf(NEON_MID.copy(alpha = 0.05f * (0.3f + 0.7f * max(spark, markAlpha))), Color.Transparent),
                center = Offset(cx, cy), radius = d * 0.95f,
            ),
            radius = d * 0.95f, center = Offset(cx, cy),
        )

        // --- i due telefoni, finche' ci sono -------------------------------
        if (phoneAlpha > 0.01f) {
            val phoneLen = armLen * 0.92f
            val phoneW = phoneLen * 0.40f
            listOf(leftTip to -1f, rightTip to 1f).forEach { (tip, side) ->
                val mid = Offset((tip.x + vertex.x) / 2f, (tip.y + vertex.y) / 2f)
                val away = 1f - land
                // Nella luce non svaniscono sul posto: si avvicinano di un soffio,
                // come se venissero assorbiti.
                val pull = become * d * 0.06f
                val from = Offset(
                    mid.x + side * d * 1.5f * away - side * pull,
                    mid.y - d * 0.6f * away + pull * 0.4f,
                )
                val ang = Math.toDegrees(atan2((tip.x - vertex.x).toDouble(), (vertex.y - tip.y).toDouble())).toFloat()
                phone(from, phoneLen, phoneW, ang, phoneAlpha, hold * 0.4f + spark)
            }
        }

        // --- la scintilla lungo il braccio destro ---------------------------
        // Solo mentre i telefoni ci sono ancora: e' la cosa che li trasforma. Il
        // marchio, una volta arrivato, il suo tratto ce l'ha gia' dipinto dentro.
        if (spark > 0f && become < 1f) {
            val ux = (rightTip.x - vertex.x) / armLen
            val uy = (rightTip.y - vertex.y) / armLen
            val over = armLen * 0.10f
            val a = Offset(vertex.x - ux * over, vertex.y - uy * over)
            val b = Offset(rightTip.x + ux * over, rightTip.y + uy * over)
            neon(a, b, spark, (1f - become) * (0.4f + 0.6f * spark), d, (1f - become) * leaving)
        }

        // --- e arriva il marchio -------------------------------------------
        if (markAlpha > 0.01f) {
            // Entra di un soffio piu' grande e si posa: arrivare senza muoversi
            // non e' arrivare.
            val k = d * (1.06f - 0.06f * become)
            drawImage(
                image = mark,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mark.width, mark.height),
                dstOffset = IntOffset((cx - k / 2f).toInt(), (cy - k / 2f).toInt()),
                dstSize = IntSize(k.toInt(), k.toInt()),
                alpha = markAlpha,
            )
        }
    }
}

/** Il viola in basso e il ciano in alto: i due estremi del marchio. */
private val NEON_LOW = Color(0xFF9524F3)
private val NEON_MID = Color(0xFF666CF4)
private val NEON_HIGH = Color(0xFF0BF5EC)

/**
 * Il tratto che si disegna da solo, con il colore che scorre lungo la sua
 * lunghezza.
 *
 * A pezzi e non in un colpo solo perche' il colore cambia strada facendo, e
 * perche' e' cosi' che si mostra solo la parte gia' arrivata. Tre passate: un
 * alone largo e tenue, una media, e il filo vero, che e' quello che si legge.
 */
private fun DrawScope.neon(a: Offset, b: Offset, reveal: Float, glow: Float, d: Float, fade: Float = 1f) {
    if (reveal <= 0f) return
    // Spesso come nel logo, non come un tubo al neon: misurato sul marchio, che
    // e' l'unica cosa di cui il tratto e' una parte.
    val core = d * 0.017f
    val steps = 44
    for (i in 0 until steps) {
        val u0 = i / steps.toFloat()
        if (u0 >= reveal) break
        val u1 = min((i + 1) / steps.toFloat(), reveal)
        val p0 = Offset(a.x + (b.x - a.x) * u0, a.y + (b.y - a.y) * u0)
        val p1 = Offset(a.x + (b.x - a.x) * u1, a.y + (b.y - a.y) * u1)
        val u = (u0 + u1) / 2f
        val c = if (u < 0.5f) lerp(NEON_LOW, NEON_MID, u * 2f) else lerp(NEON_MID, NEON_HIGH, (u - 0.5f) * 2f)
        drawLine(c.copy(alpha = (0.09f + 0.13f * glow) * fade), p0, p1, core * 4.4f, StrokeCap.Round)
        drawLine(c.copy(alpha = (0.24f + 0.22f * glow) * fade), p0, p1, core * 2.1f, StrokeCap.Round)
        drawLine(c.copy(alpha = fade), p0, p1, core, StrokeCap.Round)
    }
    // La punta accesa mentre viaggia, e niente quando e' arrivata: un filo che
    // continua a brillare in cima direbbe che sta ancora succedendo qualcosa.
    if (reveal < 1f) {
        val head = Offset(a.x + (b.x - a.x) * reveal, a.y + (b.y - a.y) * reveal)
        val c = if (reveal < 0.5f) lerp(NEON_LOW, NEON_MID, reveal * 2f) else lerp(NEON_MID, NEON_HIGH, (reveal - 0.5f) * 2f)
        drawCircle(c.copy(alpha = 0.22f * fade), core * 3.4f, head)
        drawCircle(c.copy(alpha = fade), core * 1.15f, head)
    }
}

/**
 * Un telefono, ridotto a quello che lo rende riconoscibile.
 *
 * Corpo, bordo, vetro e l'altoparlante: quattro tratti. Tutto il resto, la
 * fotocamera, i tasti, il riflesso, a questa misura diventa sporco. Il vetro
 * prende il colore del neon quando il tratto passa, ed e' l'unica cosa che lega
 * i due oggetti invece di lasciarli uno accanto all'altro.
 */
private fun DrawScope.phone(center: Offset, len: Float, wide: Float, angle: Float, alpha: Float, glow: Float) {
    if (alpha <= 0.01f) return
    rotate(angle, center) {
        val tl = Offset(center.x - wide / 2f, center.y - len / 2f)
        val sz = Size(wide, len)
        val r = CornerRadius(wide * 0.20f)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF3A3A50), Color(0xFF1A1A26)), startY = tl.y, endY = tl.y + len),
            tl, sz, r, alpha = alpha,
        )
        drawRoundRect(Color(0xFF8A8ABC).copy(alpha = alpha), tl, sz, r, style = Stroke(1.6.dp.toPx()))
        val ins = wide * 0.075f
        val gtl = Offset(tl.x + ins, tl.y + ins)
        val gsz = Size(wide - ins * 2f, len - ins * 2f)
        val gr = CornerRadius(wide * 0.15f)
        drawRoundRect(Color(0xFF0E0E18).copy(alpha = alpha), gtl, gsz, gr)
        if (glow > 0.01f) drawRoundRect(NEON_MID.copy(alpha = 0.14f * glow * alpha), gtl, gsz, gr)
        drawRoundRect(
            Color(0xFF3C3C4E).copy(alpha = alpha * 0.7f),
            Offset(center.x - wide * 0.09f, tl.y + ins * 2.1f),
            Size(wide * 0.18f, max(1f, wide * 0.02f)),
            CornerRadius(wide * 0.02f),
        )
    }
}

/** La fetta di tempo fra due istanti del giro, da zero a uno. */
private fun seg(t: Float, from: Float, to: Float): Float =
    if (t <= from) 0f else if (t >= to) 1f else (t - from) / (to - from)

/** Niente si muove a velocita' costante: rallenta arrivando. */
private fun ease(p: Float): Float {
    val c = min(1f, max(0f, p))
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
