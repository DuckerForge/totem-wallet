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
 * The receipt takes the whole screen, always.
 *
 * Every sheet in this app used to grow its receipt at the bottom of its own
 * form: fill in the amount, and what you were about to sign appeared under the
 * fields, below the warnings, below the fold. To read it you scrolled, and what
 * people do with a thing that appears below what they were already doing is
 * scroll past it. The one screen the whole product is built on was the easiest
 * one in it to miss.
 *
 * So it stops being part of the form. The form is where you decide the numbers;
 * this is where you are shown what those numbers do, on its own, over
 * everything, with nothing above it to have been reading a second ago. The
 * pattern was already right in one place, the budget card, and the comment
 * there said why. This is that, lifted out so every sheet gets it.
 *
 * Wrap the receipt in a bare `Column` when you pass it in. It carries its own
 * spacing between its parts, and dropping it straight into this one's arrangement
 * adds that gap again to every piece of it: the page comes out airy and twice as
 * long as it needs to be, and the hold ends up below the fold again, which is the
 * problem this was built to fix.
 *
 * Give it the title, the one line under it, and whatever the sheet wants below
 * the receipt: the hold, what it is doing, what went wrong. The way out is
 * always here, at the bottom, and back means back to the form rather than out
 * of the flow.
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
