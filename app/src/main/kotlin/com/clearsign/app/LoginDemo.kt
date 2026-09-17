package com.clearsign.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
    // Una volta sola. Un racconto che riparte in continuazione smette di essere
    // un racconto e diventa uno sfondo che si muove: la prima volta lo guardi,
    // la terza ti da' fastidio. Arrivato in fondo resta il marchio, fermo.
    val run = remember { Animatable(0f) }
    LaunchedEffect(Unit) { run.animateTo(1f, tween(6000, easing = LinearEasing)) }
    val t = run.value

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

        // La V dei telefoni non e' una V qualsiasi che gli somiglia: e' **quella
        // del marchio**, presa appoggiando le sagome dei due telefoni sopra
        // l'icona finche' non ci cascano dentro. Il vertice e' basso, a 0,82 del
        // lato, e i bracci si aprono di 38 gradi dalla verticale: leggerli da un
        // ritaglio di righe li dava molto piu' chiusi, perche' la parte bassa
        // dell'immagine e' un'altra cosa e sporcava la misura.
        val vertex = Offset(cx, cy + d * 0.320f)
        val armX = d * 0.480f
        val armY = d * 0.615f
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
        // I due telefoni sono **gia' li'** al primo fotogramma. Prima entravano
        // da fuori, e quell'arrivo costava un terzo del racconto per dire una
        // cosa che si vede da sola: che sono due telefoni. La scena si apre
        // sulla V gia' fatta, e quello che succede dopo e' l'unica cosa che
        // succede.
        val hold = seg(t, 0.04f, 0.20f)
        val spark = ease(seg(t, 0.22f, 0.50f))
        // Fra la fine del tratto e la dissolvenza c'e' una battuta ferma: la V
        // di due Seeker con il bordo acceso. Prima `become` partiva a 0,50 e il
        // tratto cominciava a scivolare **mentre** lo stavo ancora disegnando,
        // quindi si staccava dal telefono a meta' corsa.
        val become = ease(seg(t, 0.62f, 0.80f))

        // L'apertura della porta non salta il racconto, lo lascia finire e poi
        // ritira tutto.
        //
        // Prima `opening` spingeva la scena dritta al marchio: telefoni a zero,
        // logo a uno. Ma quella bandiera resta accesa mentre la porta prova lo
        // sblocco, e dopo un'impronta non riconosciuta ci restava. Risultato: i
        // telefoni non si vedevano piu' **mai**, e la porta mostrava un logo fermo.
        // Adesso il giro va per conto suo e l'apertura lo sfuma via.
        val leaving = 1f - op * 0.85f
        // I telefoni se ne vanno in fretta una volta cominciato: a dissolvenza
        // lineare il corpo sparisce ma le isole della fotocamera restano
        // leggibili, e sembrano due macchie che galleggiano sul marchio.
        val gone = (1f - become).let { it * it * it }
        val phoneAlpha = gone * leaving
        // Niente smorzatura in coda: serviva a nascondere lo stacco quando il
        // giro ripartiva, e il giro non riparte piu'. Lasciandola, il marchio
        // restava per sempre al quarantacinque per cento.
        val markAlpha = become * leaving
        val glow = spark * (1f - seg(t, 0.84f, 0.96f) * 0.6f)

        // --- il respiro dietro ---------------------------------------------
        drawCircle(
            Brush.radialGradient(
                listOf(NEON_MID.copy(alpha = 0.05f * (0.3f + 0.7f * max(spark, markAlpha))), Color.Transparent),
                center = Offset(cx, cy), radius = d * 0.95f,
            ),
            radius = d * 0.95f, center = Offset(cx, cy),
        )

        // --- i due telefoni, finche' ci sono -------------------------------
        val phoneLen = armLen * 0.98f
        val phoneW = phoneLen * (69.56f / 150.86f)   // il Seeker vero: 150,86 x 69,56 mm
        // Nella luce non svaniscono sul posto: si avvicinano di un soffio, come
        // se venissero assorbiti. E' l'unico spostamento rimasto.
        val pull = become * d * 0.06f
        // Dove sta un telefono adesso. Serve due volte, ai telefoni e al tratto
        // che ci corre sopra, e finche' e' un conto solo non possono staccarsi.
        fun seat(tip: Offset, side: Float): Offset {
            val mid = Offset((tip.x + vertex.x) / 2f, (tip.y + vertex.y) / 2f)
            return Offset(
                mid.x - side * pull,
                mid.y + pull * 0.4f,
            )
        }
        if (phoneAlpha > 0.01f) {
            listOf(leftTip to -1f, rightTip to 1f).forEach { (tip, side) ->
                val ang = Math.toDegrees(atan2((tip.x - vertex.x).toDouble(), (vertex.y - tip.y).toDouble())).toFloat()
                // Uno di fronte e uno di dorso. Due dorsi uguali erano la stessa
                // cosa detta due volte, e per non farli sembrare inclinati in
                // modo diverso andava pure specchiato quello di sinistra, che e'
                // una toppa. Cosi' invece i due lati del telefono si vedono
                // tutti e due, e il tratto corre sul dorso, dove sta nel marchio.
                phone(seat(tip, side), phoneLen, phoneW, ang, phoneAlpha, hold * 0.4f + spark, back = side > 0f)
            }
        }

        // --- la scintilla lungo il telefono destro --------------------------
        // Solo mentre i telefoni ci sono ancora: e' la cosa che li trasforma. Il
        // marchio, una volta arrivato, il suo tratto ce l'ha gia' dipinto dentro.
        //
        // Il tratto corre **sul vetro**, non accanto: stesso centro e stessa
        // lunghezza del telefono destro, rientrato di un raggio d'angolo perche'
        // la punta tonda si fermi dentro il bordo. Prima andava da vertice a
        // punta e sbordava di un decimo per parte, e quando i telefoni venivano
        // tirati dentro restava dov'era: due oggetti vicini invece di uno.
        if (spark > 0f && become < 1f) {
            val ux = (rightTip.x - vertex.x) / armLen
            val uy = (rightTip.y - vertex.y) / armLen
            // Non in mezzo al telefono: sul suo **bordo sinistro**, quello che
            // guarda il vertice. Nel marchio il tratto e' la piega fra i due
            // pannelli, non una riga in mezzo a uno.
            val edge = Offset(uy, -ux)
            val c = seat(rightTip, 1f) + edge * (phoneW * 0.46f)
            val half = phoneLen / 2f - phoneW * 0.15f
            // Nel marchio il tratto non e' l'asse del braccio: parte sotto il
            // vertice, taglia la V ed esce dalla punta destra, molto piu'
            // inclinato. Misurato dentro l'immagine. Mentre i telefoni si
            // dissolvono il tratto ci scivola sopra, cosi' il marchio non
            // arriva: si posa su qualcosa che e' gia' al posto giusto.
            val a = mix(Offset(c.x - ux * half, c.y - uy * half), Offset(cx - d * 0.310f, cy + d * 0.280f), become)
            val b = mix(Offset(c.x + ux * half, c.y + uy * half), Offset(cx + d * 0.360f, cy - d * 0.330f), become)
            neon(a, b, spark, (1f - become) * (0.4f + 0.6f * spark), d, (1f - become * 0.8f) * leaving)
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
 * Un Seeker.
 *
 * Il telefono e' gia' disegnato altrove, e non a occhio: `drawPhone` in
 * TapAnimation viene dal disegno di fabbrica del Seeker, isola a tre obiettivi
 * col flash di fianco, tasti sul lato, 150,86 x 69,56 mm. Lo usa il tocco fra
 * due telefoni. Disegnarne un altro qui voleva dire tenere due Seeker diversi
 * nella stessa app e vederli divergere alla prima correzione.
 *
 * Uno dei due di dorso, perche' davanti, a questa misura, un telefono e' un
 * rettangolo nero che potrebbe essere di chiunque: l'isola a tre obiettivi e'
 * quello che dice Seeker. L'altro di fronte, cosi' del telefono si vedono tutti
 * e due i lati invece della stessa cosa detta due volte.
 */
private fun DrawScope.phone(
    center: Offset,
    len: Float,
    wide: Float,
    angle: Float,
    alpha: Float,
    glow: Float,
    back: Boolean,
) {
    if (alpha <= 0.01f) return
    // Il corpo si scalda quando il tratto passa: e' l'unica cosa che lega i due
    // oggetti invece di lasciarli uno accanto all'altro.
    val body = lerp(Color(0xFF24243A), NEON_MID, 0.18f * glow)
    rotate(angle, center) {
        drawPhone(
            x = center.x - wide / 2f,
            y = center.y - len / 2f,
            w = wide,
            h = len,
            back = back,
            body = body.copy(alpha = alpha),
            edge = Color(0xFFB2B2E4).copy(alpha = alpha),
            ink = Color(0xFF12121C).copy(alpha = alpha),
            glass = Color(0xFF0B0B14).copy(alpha = alpha),
        )
    }
}

/** Un punto a meta' strada fra due, che serve solo qui. */
private fun mix(a: Offset, b: Offset, k: Float) = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

/** La fetta di tempo fra due istanti del giro, da zero a uno. */
private fun seg(t: Float, from: Float, to: Float): Float =
    if (t <= from) 0f else if (t >= to) 1f else (t - from) / (to - from)

/** Niente si muove a velocita' costante: rallenta arrivando. */
private fun ease(p: Float): Float {
    val c = min(1f, max(0f, p))
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
