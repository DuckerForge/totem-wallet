package com.clearsign.app

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * La lingua comune dell'interfaccia: quello che dice «questo si tocca», quello
 * che dice «questo si legge e basta», e i pezzi che ogni pagina usa uguali.
 *
 * Qui dentro niente rete e niente stato dell'app: solo forma, colore e
 * movimento, nei toni di `HaloTheme`. Ogni animazione si legge in draw o in
 * `graphicsLayer`, mai in composizione: un bordo che respira non deve far
 * ricomporre la scheda che orla.
 */

/**
 * Una fase sola per tutti i bordi vivi.
 *
 * Prima ogni scheda aveva la sua animazione infinita, letta in composizione:
 * sulle palette con `livingStroke` ogni `GlassCard` dell'app si ricomponeva a
 * sessanta fotogrammi al secondo e allocava un pennello a fotogramma, per
 * sempre. Adesso `HaloRoot` muove un numero solo, una volta a fotogramma, e i
 * bordi lo leggono dentro il loro draw.
 */
object LivingStroke {
    val phase = mutableFloatStateOf(0f)
    const val PERIOD_MS = 5200L
}

private val LIVING_COLORS = listOf(Color(0xFF9945FF), Color(0xFF14F195), Color(0xFF9945FF))

/**
 * L'orlo di una scheda: una riga sottile nel colore `stroke`, o il gradiente
 * che scorre sulle palette che lo prevedono. Disegnato dentro il bordo, come
 * fa `border`, e letto in draw: un cambio di palette o di fase ridisegna e
 * basta, non ricompone.
 */
fun Modifier.haloBorder(shape: Shape, width: Dp = 1.dp): Modifier = this.then(
    Modifier.drawWithCache {
        val w = width.toPx()
        val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
        val outline = shape.createOutline(inner, layoutDirection, this)
        // Il gradiente ripete lungo il suo asse: per girare senza cucitura la
        // fase deve percorrere esattamente un vettore d'asse per ciclo.
        val dx = 520f
        val dy = 520f * 0.7f
        onDrawWithContent {
            drawContent()
            translate(w / 2f, w / 2f) {
                if (!Halo.palette.livingStroke) {
                    drawOutline(outline, Halo.stroke, style = Stroke(w))
                } else {
                    val t = LivingStroke.phase.floatValue
                    drawOutline(
                        outline,
                        Brush.linearGradient(LIVING_COLORS, start = Offset(t * dx, t * dy), end = Offset(t * dx + dx, t * dy + dy), tileMode = TileMode.Repeated),
                        style = Stroke(w + 0.2.dp.toPx()),
                    )
                }
            }
        }
    },
)
