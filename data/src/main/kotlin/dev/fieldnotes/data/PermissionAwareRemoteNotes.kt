package dev.fieldnotes.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Local-server transport only. Public HTTPS endpoints and the on-device demo do not need this gate. */
class PermissionAwareRemoteNotes(context: Context, private val delegate: RemoteNotes) : RemoteNotes {
    private val context = context.applicationContext

    override suspend fun snapshot(): Snapshot {
        checkPermission()
        return delegate.snapshot()
    }

    override suspend fun push(id: String, mutation: Mutation): PushResult {
        checkPermission()
        return delegate.push(id, mutation)
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            throw NetworkPermissionFailure()
        }
    }

    companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
