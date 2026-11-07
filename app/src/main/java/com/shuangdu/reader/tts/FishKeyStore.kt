package com.shuangdu.reader.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fish Audio API Key 的加密存储。
 * 用 AndroidKeyStore 里的 AES/GCM 密钥加密后再落 SharedPreferences，
 * 明文只在内存里出现，备份/抓文件也拿不到。
 */
class FishKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("fish_audio_key", Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(load() != null)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    fun isConfigured(): Boolean = load() != null

    fun save(raw: String) {
        val key = raw.trim()
        require(key.isNotEmpty()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        prefs.edit()
            .putString(CIPHER, Base64.encodeToString(cipher.doFinal(key.toByteArray()), Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _configured.value = true
    }

    fun load(): String? {
        val cipherText = prefs.getString(CIPHER, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)))
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun masked(): String {
        val key = load() ?: return ""
        return if (key.length <= 12) "****" else key.take(7) + "…" + key.takeLast(4)
    }

    fun clear() {
        prefs.edit().remove(CIPHER).remove(IV).apply()
        _configured.value = false
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val CIPHER = "key_cipher"
        const val IV = "key_iv"
        const val ALIAS = "shuangdu_fish_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
