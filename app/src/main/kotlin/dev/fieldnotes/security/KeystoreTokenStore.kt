package dev.fieldnotes.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.fieldnotes.domain.TokenStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM authenticates the ciphertext. The key never leaves Android Keystore.
 * Call on a background thread. The file is excluded from backup with its app. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.enc"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized override fun read(): String? {
        return try {
            val bytes = file.readFully()
            require(bytes.size in 29..8192 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            file.delete(); null // Lost/invalidated key or tampered file: require sign-in again.
        } catch (_: IOException) {
            file.delete(); null
        } catch (_: IllegalArgumentException) {
            file.delete(); null
        }
    }

    @Synchronized override fun write(token: String?) {
        if (token == null) { file.delete(); return }
        require(token.length in 24..4096 && token.all { it.code in 33..126 })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: IOException) { file.failWrite(stream); throw error }
    }

    private companion object { const val KEY_ALIAS = "field-notes-session-v1" }
}
