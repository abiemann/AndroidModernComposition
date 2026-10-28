package dev.fieldnotes.data

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionAwareRemoteNotesTest {
    @Test fun grantedPermissionDelegatesToRemote() = runBlocking {
        val delegate = RecordingRemote()
        val remote = PermissionAwareRemoteNotes(permissionContext(PackageManager.PERMISSION_GRANTED), delegate)
        remote.snapshot()
        remote.push("id", mutation)
        assertEquals(2, delegate.calls)
    }

    @Test fun deniedPermissionIsRequiredOnlyOnAndroidSeventeenAndLater() = runBlocking {
        val delegate = RecordingRemote()
        val remote = PermissionAwareRemoteNotes(permissionContext(PackageManager.PERMISSION_DENIED), delegate)
        if (Build.VERSION.SDK_INT >= 37) {
            try { remote.snapshot(); fail("Permission must guard reads") } catch (_: NetworkPermissionFailure) { }
            try { remote.push("id", mutation); fail("Permission must guard writes") } catch (_: NetworkPermissionFailure) { }
            assertEquals(0, delegate.calls)
        } else {
            remote.snapshot()
            remote.push("id", mutation)
            assertEquals(2, delegate.calls)
        }
    }

    private fun permissionContext(result: Int): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int {
            assertEquals(PermissionAwareRemoteNotes.ACCESS_LOCAL_NETWORK, permission)
            return result
        }
    }

    private class RecordingRemote : RemoteNotes {
        var calls = 0
        override suspend fun snapshot(): Snapshot { calls++; return Snapshot(emptyList(), emptyList()) }
        override suspend fun push(id: String, mutation: Mutation): PushResult {
            calls++
            return PushResult.Accepted(RemoteNote(id, mutation.projectId, mutation.title, mutation.body,
                mutation.updatedAt, 1, mutation.deleted))
        }
    }

    private val mutation = Mutation("op", 0, "field", "Oak", "", 100, false)
}
