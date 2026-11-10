package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-backed secret storage. Values never enter exported config or logs. */
class AndroidKeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("ecosystem_secrets", Context.MODE_PRIVATE)
    private val keyAlias = "operit.ecosystem.aes"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized private fun key(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    override fun put(ref: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(ref.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString(ref, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) { "secret persistence failed" }
    }

    override fun get(ref: String): String? = prefs.getString(ref, null)?.let { encoded ->
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 12) { "invalid secret payload" }
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            updateAAD(ref.toByteArray(StandardCharsets.UTF_8))
            String(doFinal(payload.copyOfRange(12, payload.size)), StandardCharsets.UTF_8)
        }
    }

    override fun remove(ref: String) { check(prefs.edit().remove(ref).commit()) { "secret removal failed" } }
}
