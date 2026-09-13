@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where the model's credentials live, and the one paragraph that says what
 * leaves the phone. The key is written masked and stored sealed by [Secrets];
 * it is never logged and never sent anywhere except the provider it belongs to.
 */
@Composable
internal fun BrainFields(compact: Boolean = false) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Secrets.model(ctx)) }
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.mint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                HaloIcon(HIcon.PIGEON, Halo.mint, 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.brain_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                Text(stringResource(R.string.brain_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            }
            if (cfg.ready) HaloIcon(HIcon.CHECK, Halo.mint, 16.dp)
        }

        // You should not have to go and find out which providers are free, nor type
        // a base URL by hand. These two cost nothing and issue a key without a card,
        // and both answer tool calls — which matters, because the agent uses five.
        Text(stringResource(R.string.brain_free_hdr), style = HaloType.label, color = Halo.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingChip("OpenRouter", cfg.baseUrl.contains("openrouter"), Modifier.weight(1f)) {
                cfg = cfg.copy(provider = "openai", baseUrl = "https://openrouter.ai/api/v1", model = "nex-agi/nex-n2.5-mini:free")
            }
            SettingChip("Groq", cfg.baseUrl.contains("groq"), Modifier.weight(1f)) {
                // llama-3.3-70b-versatile was deprecated in June 2026; this is Groq's own
                // recommended replacement, and it answers tool calls.
                cfg = cfg.copy(provider = "openai", baseUrl = "https://api.groq.com/openai/v1", model = "openai/gpt-oss-120b")
            }
        }
        Text(stringResource(R.string.brain_free_note), style = HaloType.small, color = Halo.muted)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingChip("Anthropic", cfg.provider == "anthropic", Modifier.weight(1f)) {
                cfg = cfg.copy(provider = "anthropic", model = Secrets.DEFAULT_ANTHROPIC_MODEL)
            }
            SettingChip("OpenAI-compatible", cfg.provider != "anthropic", Modifier.weight(1f)) {
                cfg = cfg.copy(provider = "openai", model = "openai/gpt-4o-mini")
            }
        }

        if (cfg.provider == "anthropic") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip("Haiku 4.5", cfg.model == Secrets.DEFAULT_ANTHROPIC_MODEL, Modifier.weight(1f)) { cfg = cfg.copy(model = Secrets.DEFAULT_ANTHROPIC_MODEL) }
                SettingChip("Sonnet 5", cfg.model == "claude-sonnet-5", Modifier.weight(1f)) { cfg = cfg.copy(model = "claude-sonnet-5") }
            }
        } else {
            BrainField(stringResource(R.string.brain_base), cfg.baseUrl) { cfg = cfg.copy(baseUrl = it) }
            BrainField(stringResource(R.string.brain_model), cfg.model) { cfg = cfg.copy(model = it) }
        }

        BrainField(
            stringResource(R.string.brain_key),
            if (key.isNotEmpty()) key else if (cfg.ready) "••••••••" + cfg.key.takeLast(4) else "",
            hint = stringResource(R.string.brain_key_hint),
            password = true,
        ) { key = it }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (saved) stringResource(R.string.brain_saved) else stringResource(R.string.save), danger = false, icon = HIcon.CHECK) {
                val next = cfg.copy(key = key.trim().ifBlank { cfg.key })
                Secrets.setModel(ctx, next)
                cfg = Secrets.model(ctx); key = ""; saved = true; Haptics.tick(ctx)
            }
            if (cfg.ready) {
                GhostButton(stringResource(R.string.brain_clear), Modifier, HIcon.TRASH, tint = Halo.red) {
                    Secrets.setModel(ctx, cfg.copy(key = ""))
                    cfg = Secrets.model(ctx); key = ""; saved = false
                }
            }
        }

        // Until now the first sign that a key was wrong was a failed conversation.
        if (cfg.ready) {
            GhostButton(
                if (testing) stringResource(R.string.brain_testing) else stringResource(R.string.brain_test),
                Modifier.fillMaxWidth(), HIcon.SPARK, tint = Halo.cyan,
            ) {
                testing = true; testResult = null
                scope.launch { testResult = Brain.test(ctx) ?: ""; testing = false }
            }
            testResult?.let { r ->
                if (r.isEmpty()) Banner(stringResource(R.string.brain_test_ok), Halo.mint, HIcon.CHECK)
                else Banner(r, Halo.red, HIcon.WARNING)
            }
        }

        Text(stringResource(R.string.brain_truth), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 16.sp)
    }
}

@Composable
internal fun SettingChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(rs(12)).background(if (on) Halo.mint.copy(alpha = 0.16f) else Halo.cardSoft)
            .border(1.dp, if (on) Halo.mint else Halo.stroke, rs(12))
            .clickable { onClick() }.padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = if (on) Halo.mint else Halo.muted, maxLines = 1)
    }
}

@Composable
private fun BrainField(label: String, value: String, hint: String = "", password: Boolean = false, onChange: (String) -> Unit) {
    Column {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
        androidx.compose.material3.OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(hint, fontFamily = Inter, fontSize = 12.sp, color = Halo.muted) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Halo.ink),
            visualTransformation = if (password && !value.startsWith("•")) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
            ),
            shape = rs(12),
        )
    }
}

/** The same fields as a card, for the Settings list. */
@Composable
internal fun BrainCard() {
    GlassCard { BrainFields() }
}

/**
 * The same fields as a sheet, reachable from the Agent tab.
 *
 * The key belongs where the agent is. Burying it in Settings meant people could
 * not find the one thing the chat needs before it can say anything.
 */
@Composable
internal fun BrainSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrainFields()
            GhostButton(stringResource(R.string.close), Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}

/**
 * What "link an agent" actually involves, said before the camera opens.
 *
 * It is the advanced path: it needs a bridge running on your own computer. The
 * camera appearing with no explanation was the reason nobody could tell what
 * this button did.
 */
@Composable
internal fun LinkHelpSheet(onScan: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.link_help_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
            Text(stringResource(R.string.link_help_body), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted, lineHeight = 19.sp)
            Column(
                Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(12)).padding(12.dp),
            ) {
                Text(stringResource(R.string.link_help_cmd), fontFamily = Mono, fontSize = 11.sp, color = Halo.ink, lineHeight = 17.sp)
            }
            Text(stringResource(R.string.link_help_then), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted, lineHeight = 19.sp)
            PrimaryButton(stringResource(R.string.link_help_scan), danger = false, icon = HIcon.SCAN) { onScan() }
            GhostButton(stringResource(R.string.cancel), Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}
