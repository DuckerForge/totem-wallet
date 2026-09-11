package com.clearsign.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** First-run: three slides that state the promise, then "connect the Seed Vault". */
@Composable
internal fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides = listOf(
        Triple(HIcon.SEAL, R.string.onb1_t to R.string.onb1_b, Halo.mint),
        Triple(HIcon.SHIELD_LOCK, R.string.onb2_t to R.string.onb2_b, Halo.cyan),
        Triple(HIcon.RECEIPT, R.string.onb3_t to R.string.onb3_b, Halo.mint),
    )
    val pager = rememberPagerState { slides.size }
    val last = pager.currentPage == slides.lastIndex

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground)))
            .background(Brush.radialGradient(listOf(Halo.cyan.copy(alpha = 0.12f), Color.Transparent), radius = 1100f))
            .haloSurface(),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(rs(10)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SEAL, Halo.ground, 20.dp) }
                Spacer(Modifier.width(10.dp))
                Text("ClearSign", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Halo.ink)
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
                val (icon, text, accent) = slides[i]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(120.dp).clip(rs(28)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { HaloIcon(icon, accent, 60.dp) }
                    Spacer(Modifier.height(28.dp))
                    Text(stringResource(text.first), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(text.second), fontFamily = Inter, fontSize = 14.5.sp, color = Halo.muted, textAlign = TextAlign.Center, lineHeight = 21.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(slides.size) { i ->
                    val on = i == pager.currentPage
                    val w by animateFloatAsState(if (on) 22f else 7f, tween(250), label = "dot")
                    Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w.dp).clip(CircleShape).background(if (on) Halo.mint else Halo.stroke))
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                if (last) stringResource(R.string.onb_start) else stringResource(R.string.onb_next),
                danger = false, icon = if (last) HIcon.FINGERPRINT else HIcon.CHEVRON_RIGHT,
            ) {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
            if (!last) {
                Spacer(Modifier.height(8.dp))
                GhostButton(stringResource(R.string.onb_skip)) { onDone() }
            }
        }
    }
}
