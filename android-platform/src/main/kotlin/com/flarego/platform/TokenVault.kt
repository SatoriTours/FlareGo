package com.flarego.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore holds the non-exportable AES key. Preferences contain ciphertext only. */
class TokenVault(context: Context) {
    private val preferences =
        context.getSharedPreferences("cloud-credentials", Context.MODE_PRIVATE)
    private val alias = "flarego-cloud-tokens-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun put(id: String, token: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(preferences.edit().putString(id, payload).commit())
    }

    fun get(id: String): String? {
        val payload = preferences.getString(id, null) ?: return null
        val parts = payload.split('.')
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
                )
            }
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    fun remove(id: String) {
        check(preferences.edit().remove(id).commit())
    }
}
