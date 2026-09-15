package com.clearsign.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The agent's voice.
 *
 * Android's own synthesiser: nothing to install, nothing to pay, no network, and
 * no new permission — `RECORD_AUDIO` would be for listening, and we never listen.
 * It speaks in the language the app is set to, not the phone's, so an app forced
 * to Italian does not greet you in English.
 *
 * One engine for the process, started on first use and released when the app
 * goes away. Speaking is best-effort by design: a device with no voice data
 * installed simply stays quiet, and the chat works exactly the same.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** True once the engine has come up and has a voice for [locale]. */
    val available: Boolean get() = ready

    fun warm(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) { ready = false; return@TextToSpeech }
            val want = appLocale()
            val res = runCatching { tts?.setLanguage(want) }.getOrNull()
            // MISSING_DATA / NOT_SUPPORTED are negative; fall back rather than fail.
            if (res == null || res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { tts?.setLanguage(Locale.ENGLISH) }
            }
            ready = true
        }
    }

    /** Say [text] once, interrupting anything still being said. Silent on failure. */
    fun say(ctx: Context, text: String) {
        warm(ctx)
        val engine = tts ?: return
        if (!ready) return
        runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "apex") }
    }

    /** Say [text] after whatever is being said, for a screen that narrates a stream of lines. */
    fun add(ctx: Context, text: String) {
        warm(ctx)
        val engine = tts ?: return
        if (!ready) return
        val t = text.trim().take(200)
        if (t.isEmpty()) return
        runCatching { engine.speak(t, TextToSpeech.QUEUE_ADD, null, "apex" + t.hashCode()) }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun appLocale(): Locale =
        androidx.core.os.LocaleListCompat.getAdjustedDefault()[0] ?: Locale.getDefault()
}
