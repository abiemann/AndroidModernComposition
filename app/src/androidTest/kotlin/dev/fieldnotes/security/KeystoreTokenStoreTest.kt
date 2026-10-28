package dev.fieldnotes.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
    @Test fun encryptedTokenRoundTripsAndTamperRequiresSignIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = KeystoreTokenStore(context)
        val token = "local-test-token-never-use-for-production"
        try {
            store.write(token)
            assertEquals(token, KeystoreTokenStore(context).read())
            val file = File(context.noBackupFilesDir, "session.enc")
            assertFalse(file.readText().contains(token))
            val backup = File(context.noBackupFilesDir, "session.enc.bak")
            assertTrue(file.renameTo(backup))
            assertEquals(token, store.read()) // AtomicFile restores interrupted-write backups.
            assertTrue(file.exists())
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertNull(store.read())
            store.write(token); store.write(null)
            assertNull(store.read()); assertFalse(file.exists())
        } finally { store.write(null) }
    }
}
