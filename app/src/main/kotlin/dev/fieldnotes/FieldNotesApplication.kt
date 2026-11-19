package dev.fieldnotes

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.work.Configuration
import dev.fieldnotes.data.DemoRemoteNotes
import dev.fieldnotes.data.FieldNotesDatabase
import dev.fieldnotes.data.OfflineFirstNoteRepository
import dev.fieldnotes.data.PreferenceDataStoreRepository
import dev.fieldnotes.data.ReplayReceiptCleanup
import dev.fieldnotes.data.RemoteNotes
import dev.fieldnotes.data.RetrofitRemoteNotes
import dev.fieldnotes.data.PermissionAwareRemoteNotes
import dev.fieldnotes.domain.*
import dev.fieldnotes.feature.EditorViewModel
import dev.fieldnotes.feature.JournalViewModel
import dev.fieldnotes.feature.SettingsViewModel
import dev.fieldnotes.monitoring.LocalTelemetry
import dev.fieldnotes.security.KeystoreTokenStore
import dev.fieldnotes.security.LocalSessionRepository
import dev.fieldnotes.sync.SyncWorkerFactory
import dev.fieldnotes.sync.WorkSyncScheduler
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class FieldNotesApplication : Application(), Configuration.Provider {
    private lateinit var dependencies: Koin
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        dependencies = startKoin {
            androidContext(this@FieldNotesApplication)
            modules(module {
                single<Clock> { Clock(System::currentTimeMillis) }
                single<IdGenerator> { IdGenerator { UUID.randomUUID().toString() } }
                single { FieldNotesDatabase.create(get()) }
                single<TokenStore> { KeystoreTokenStore(get()) }
                single<RemoteNotes> {
                    if (BuildConfig.DEMO) DemoRemoteNotes(get()) else PermissionAwareRemoteNotes(
                        get(), RetrofitRemoteNotes.create(BuildConfig.API_BASE_URL, get()))
                }
                single<NoteRepository> {
                    val remote = get<RemoteNotes>()
                    OfflineFirstNoteRepository(get(), remote, get(), get(), replayReceipts = remote as? ReplayReceiptCleanup)
                }
                single<PreferencesRepository> { PreferenceDataStoreRepository(get<android.content.Context>()) }
                single { WorkSyncScheduler(get(), BuildConfig.DEMO) }
                single<SyncScheduler> { get<WorkSyncScheduler>() }
                single<SessionRepository> { LocalSessionRepository(get(), get(), applicationScope) }
                single { LocalTelemetry(get(), get(), applicationScope) }
                single<Telemetry> { get<LocalTelemetry>() }
                factory { SaveNote(get(), get(), get()) }
                viewModel { JournalViewModel(get(), get(), get(), get(), get()) }
                viewModel { EditorViewModel(get(), get(), get()) }
                viewModel { SettingsViewModel(get(), get()) }
            })
        }.koin
        launchStartupTask("diagnostics") {
            dependencies.get<LocalTelemetry>().installCrashHandler()
        }
        launchStartupTask("sync scheduling") {
            dependencies.get<WorkSyncScheduler>().initialize()
        }
    }

    private fun launchStartupTask(name: String, task: suspend () -> Unit) {
        applicationScope.launch(Dispatchers.IO) {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("FieldNotesStartup", "Could not initialize $name: ${error.javaClass.simpleName}")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(SyncWorkerFactory(
            repository = { dependencies.get() }, preferences = { dependencies.get() }, telemetry = { dependencies.get() },
            scheduler = { dependencies.get() },
        )).build()
}
