package app.cellar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted under a key that never leaves the Android
 * Keystore (hardware-backed where the device has it). Only the
 * ciphertext touches disk, and plaintext exists solely in memory for
 * the moment a command runs.
 *
 * Deliberately not androidx.security-crypto: this is ~40 lines of
 * standard AES/GCM and one less dependency to keep current.
 */
class Vault(context: Context) {

    private val prefs = context.getSharedPreferences("cellar.keys", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray())
        val blob = b64(c.iv) + ":" + b64(ct)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val (ivB64, ctB64) = blob.split(":", limit = 2).let { it[0] to it[1] }
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(ivB64)))
            String(c.doFinal(unb64(ctB64)))
        } catch (e: Exception) {
            null // key invalidated (e.g. device wiped): treat as absent
        }
    }

    fun names(): List<String> = prefs.all.keys.sorted()

    fun remove(name: String) = prefs.edit().remove(name).apply()

    /** Masked form for display — never show a key back in full. */
    fun preview(name: String): String {
        val v = get(name) ?: return "unreadable"
        return if (v.length <= 8) "••••" else v.take(4) + "…" + v.takeLast(4)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "cellar.vault"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
