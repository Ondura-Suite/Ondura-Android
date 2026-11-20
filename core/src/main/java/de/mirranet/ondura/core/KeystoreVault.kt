package de.mirranet.ondura.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords and refresh tokens of the accounts, encrypted with an AES-GCM key
 * that lives in the Android Keystore (hardware-backed where the device has
 * it) and can't be exported. Only the ciphertext is kept in the app's private
 * preferences, which are excluded from backups.
 */
class KeystoreVault(context: Context) : SecretVault {
    private val prefs = context.getSharedPreferences("ondura_secrets", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun get(key: String): String? = synchronized(lock) {
        val stored = prefs.getString(key, null) ?: return null
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "could not decrypt secret", e)
            null
        }
    }

    override fun set(key: String, value: String): Boolean = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val sealed = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            Log.e(TAG, "could not store secret", e)
            false
        }
    }

    override fun delete(key: String) {
        synchronized(lock) { prefs.edit().remove(key).commit() }
    }

    private companion object {
        const val TAG = "KeystoreVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ondura-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
