package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The receipt takes the whole screen, always. Every sheet used to grow its receipt at the
 * bottom of its own form, below the fold, and people scroll past what appears under what
 * they were doing: the one screen the product is built on was the easiest to miss. The
 * form decides the numbers; this shows what they do, alone, over everything. Wrap the
 * receipt in a bare `Column`: it carries its own spacing, and this arrangement would double
 * every gap and push the hold below the fold again. Pass the title, the line under it, and
 * what goes below the receipt (hold, progress, error). Back means back to the form.
 */
@Composable
internal fun PayOverlay(
    title: String,
    hint: String? = null,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Halo.ground2).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
            hint?.let { Text(it, style = HaloType.small, color = Halo.muted, lineHeight = 17.sp) }
            content()
            GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { onBack() }
        }
    }
}
