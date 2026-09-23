package com.clearsign.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Anything secret stored at rest: budget seeds, gift keys, the model's API key. The
 * encryption key lives in the Android Keystore and never appears in this process, so a stolen
 * backup or a pulled file is inert. Not protection against a rooted phone with the screen
 * unlocked, nothing in software is, and the app says so where it matters.
 */
object Secrets {
    private const val KEY_ALIAS = "apex_secrets_aes"

    private fun aesKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray, alias: String = KEY_ALIAS): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey(alias)) }
        return android.util.Base64.encodeToString(c.iv + c.doFinal(plain), android.util.Base64.NO_WRAP)
    }

    fun open(blob: String, alias: String = KEY_ALIAS): ByteArray? = runCatching {
        val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(alias), GCMParameterSpec(128, raw, 0, 12))
        c.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    fun sealText(text: String, alias: String = KEY_ALIAS) = seal(text.toByteArray(), alias)
    fun openText(blob: String, alias: String = KEY_ALIAS) = open(blob, alias)?.let { String(it) }

    // ---- where the model's credentials live ---------------------------------

    private const val PREFS = "apex_brain"

    data class Model(val provider: String, val key: String, val model: String, val baseUrl: String) {
        val anthropic: Boolean get() = provider == "anthropic"
        val ready: Boolean get() = key.isNotBlank()
    }

    const val DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
    const val DEFAULT_OPENAI_BASE = "https://openrouter.ai/api/v1"

    fun model(ctx: Context): Model {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = p.getString("provider", "anthropic") ?: "anthropic"
        val key = p.getString("key", null)?.let { openText(it) } ?: ""
        val model = p.getString("model", null)
            ?: if (provider == "anthropic") DEFAULT_ANTHROPIC_MODEL else "openai/gpt-4o-mini"
        return Model(provider, key, model, p.getString("base", DEFAULT_OPENAI_BASE) ?: DEFAULT_OPENAI_BASE)
    }

    fun setModel(ctx: Context, m: Model) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", m.provider)
            .putString("key", if (m.key.isBlank()) null else sealText(m.key))
            .putString("model", m.model)
            .putString("base", m.baseUrl)
            .apply()
    }
}
