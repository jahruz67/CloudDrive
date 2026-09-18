package dev.clouddrive.core.data.security

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.model.LoginCredential
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface CredentialStore {
    fun save(credential: LoginCredential)
    fun read(): LoginCredential?
    fun clear()
}

@Singleton
class KeystoreCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) : CredentialStore {
    private val preferences = context.getSharedPreferences("credentials_no_backup", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun save(credential: LoginCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(credential.appPassword.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("server", credential.serverUrl)
            .putString("login", credential.loginName)
            .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    override fun read(): LoginCredential? {
        val server = preferences.getString("server", null) ?: return null
        val login = preferences.getString("login", null) ?: return null
        val encrypted = preferences.getString("password", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val iv = preferences.getString("iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            LoginCredential(server, login, String(cipher.doFinal(encrypted), Charsets.UTF_8))
        }.getOrNull()
    }

    override fun clear() {
        preferences.edit().clear().commit()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clouddrive_account_password_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

