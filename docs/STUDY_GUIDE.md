# Study guide

Work through observable behavior first, then trace the code and change one boundary at a time. The screen interactions below are manual study exercises. Automated testing focuses on method calls and resulting state, with no functional UI tests. Use synthetic observations; this app has explicit [privacy and production limits](PRODUCTION.md).

## 1. Trace one saved observation

Run `demoDebug`. Create “River survey” in Field observations, write a short body, save it, and reopen it. Search by a word from its body and change the project filter. In Settings, expand **About the layout experiment** to inspect the locally assigned control/compact-card arm. Change **Compact note cards** and restart the app; the choice should persist. Expand the experiment details and choose **Reset to assigned layout** to return to the stable assigned arm. The local installation identifier is never sent to a service.

Read [`EditorViewModel`](../feature/src/main/kotlin/dev/fieldnotes/feature/EditorViewModel.kt), [`SaveNote`](../domain/src/main/kotlin/dev/fieldnotes/domain/SaveNote.kt), [`OfflineFirstNoteRepository`](../data/src/main/kotlin/dev/fieldnotes/data/OfflineFirstNoteRepository.kt), and [`FieldNotesDatabase`](../data/src/main/kotlin/dev/fieldnotes/data/FieldNotesDatabase.kt), in that order. Identify which layer validates text, commits the transaction, schedules transfer, and converts durable data into displayed state. Explain why the composable has no DAO or Retrofit client.

Try an empty title and a title longer than 120 characters. The write policy should reject invalid data and leave the editor usable. Add a business rule in the domain layer and a meaningful failing test before changing its implementation. Avoid testing a private function simply because it exists; test a requirement at an observable boundary.

## 2. Lifecycle and state

Type a draft, rotate the emulator, and resize its window. Compare saved Room state with the draft in `SavedStateHandle`. Background the app and use an OS process-recreation experiment without clearing app data; inspect what Android restores. Force-stop and clearing app data have different guarantees and must not be confused with ordinary process recreation.

Open Study → the legacy lifecycle lab. Inspect the Java Fragment's `onCreate`, `onViewCreated`, `onResume`, `onSaveInstanceState`, and `onDestroyView`. The Fragment can outlive its View: explain why holding the TextView after `onDestroyView` would leak or target stale UI. The small bundled XML parser illustrates an existing data contract; real file/network parsing should live behind a data boundary and avoid main-thread I/O.

## 3. Offline operation and eventual transfer

Follow [API setup](API.md) and run `localDebug`. Save a note with the server unavailable. It must remain readable after restarting the app. Start the server, configure its token in Settings, and request sync. The pending mutation should be acknowledged without creating a second observation.

On Android 17/API 37+, also use Settings → **Allow local network access** for the local flavor. First deny access and verify that saved notes and pending edits remain intact and synchronization reports a permission requirement. Then grant access and request sync again. Revoke permission in Android app settings and return to Field Notes; the screen rechecks the current grant on resume, and the transport checks before each HTTP operation. The demo does not need this permission. The automated real-server integration test grants it for its isolated test on API 37+; that is not evidence that the manual permission dialog has been reviewed on every device.

In the local flavor, **Server access** in Settings reports whether a credential has been stored locally. The next authenticated request determines whether the server accepts it. Demo mode has no access-token controls because its sync stays on-device. Stop the server to observe retryable failure; use an incorrect token to observe authentication-required handling. Neither condition should erase the local note. Restore the token and explicitly schedule synchronization.

The optional Windows [`verify-local-server.ps1`](../scripts/verify-local-server.ps1) helper runs a real-HTTP repository integration check with an isolated Room database, an isolated server database, and a fresh short-lived token. It does not drive screens or run in routine CI. Start an emulator, then run it with `-PythonExecutable` pointing to a real Python executable. It checks both process ownership of the listening port and an authenticated empty snapshot before invoking only `dev.fieldnotes.LocalServerIntegrationTest`. It never attaches to an existing service. This is additional evidence beyond a MockWebServer contract test; it does not imply a deployed backend.

WorkManager may defer execution because of constraints, backoff, Doze, or system scheduling. “Enqueued” is not “finished.” Inspect work with Android Studio's Background Task Inspector. Explain why the UI's Save result reports a durable local write rather than promising immediate remote delivery.

## 4. Replay, concurrent edits, and conflicts

Use two emulators configured with the same local server token. Sync a shared note to both. Disconnect one client, edit on both, then reconnect and sync. The server rejects a stale base version. The local client preserves its edit and exposes a conflict choice. Keep-local must create a new mutation based on the latest server version; use-server must discard the local pending change intentionally.

Repeat with a deletion on one device. A deletion conflict must remain visible and resolvable. A tombstone prevents a disconnected client from treating absence in a snapshot as permission to recreate old data.

Read the tests for **edit during upload** and **lost response after commit**. They are different races. In the second, the server may already have committed, so changing the operation ID on retry can duplicate a write. Trace why immutable outbox payloads and transactional acknowledgments are necessary.

The server's own concurrency tests issue simultaneous requests against real sockets and SQLite. Exactly one write based on the same version should win; replaying the same successful operation should return the same original response even after later edits.

## 5. UI and accessibility

Try portrait/landscape, a narrow split-screen window, a tablet-width window, light/dark system appearance, and enlarged system font. The layout switches between bottom navigation and a rail and bounds long-form content. Inspect headings, status labels, touch targets, and text-field errors with TalkBack. Status must be understandable without color.

Enable notification feedback, deny permission, then grant it through the supported user flow. Revoke permission in system settings and sync again: the worker must recheck permission and avoid crashing. Permission state and the user's preference are separate facts. A stored preference does not grant a platform permission.

Inspect both Compose screens and the Java/XML Activity manually. The automated suite tests ViewModel actions and state through methods; it does not navigate screens or assert UI elements. Review clipping, contrast, touch reachability, and screen-reader order on representative devices.

## Test ladder

| Level | What the test should establish | Where / execution |
| --- | --- | --- |
| Domain unit | Invalid notes never reach persistence; normalization is consistent; successful save requests sync. | `domain/src/test`; routine CI |
| ViewModel unit | Method calls produce expected state, editor setters preserve restorable drafts, edits retain identity, delete outcomes are handled, cancellation is preserved. | `feature/src/test`; routine CI |
| HTTP and preference boundaries | Correct authorization/mutation payloads, status/error mapping, invalid responses/redirects rejected, stable experiment assignment and preference persistence. Uses MockWebServer and DataStore rather than entirely isolated fakes. | `data/src/test`; routine CI |
| Java formatter unit | Existing Java formatting behavior is callable and testable independently of the Activity. | `legacy/src/test`; routine CI |
| Platform policy and telemetry | Worker outcome policy and Robolectric consent/local-metric behavior. | `app/src/test`; routine CI |
| Database/platform integration | Atomic note/outbox writes, conflicts, races, tombstones, migration preservation, preferences and permission gating. | `data/src/androidTest`; optional manual device run |
| Credential/worker integration | Keystore tamper/atomic recovery and injected WorkManager outcomes. | `app/src/androidTest`; optional manual device run |
| Android-to-server integration | Repository create/edit/delete and replay through Retrofit, Python, and SQLite without screen interaction. | `app/src/androidTestLocal`; optional manual helper |
| Server integration | Actual request parsing, authentication, SQLite atomicity, persistence and replay. | `server/test_server.py`; manual command or explicitly dispatched release workflow |

Run commands from [README](../README.md). Routine CI requires no emulator or Python server. Optional device checks run sequentially with `--no-parallel`. Test execution evidence belongs in [TODO](../TODO.md) or CI reports. Fakes should model meaningful behavior; mocks are useful for narrow interactions but can hide transaction/race defects if every call is scripted to succeed.

## Performance exercise

This is an optional manual performance exercise, separate from functional testing and routine CI. Use a representative **physical** device with a consistent thermal/battery state. The `benchmark` variant is minified, release-like, profileable, and debug-signed for local timing. The separate `profile` variant is unminified so generated rules refer to unobfuscated classes and methods. Before building a release-derived variant, reconcile and commit the documentation as described in the release procedure.

```text
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fieldnotes.benchmark.StartupBenchmark
./gradlew :benchmark:connectedProfileAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fieldnotes.benchmark.BaselineProfileGenerator
```

The first records ten cold-start iterations with no ahead-of-time compilation mode applied by the test. The second generates profile text from a launch journey; it does not automatically install a committed production profile. Inspect the connected-test output for the generated files, review the journey's coverage, and integrate a validated profile as a separate measured change. Merely depending on ProfileInstaller does not mean the app ships an app-specific profile.

Use Android Studio CPU/memory profiling and Perfetto to investigate a reproducible interaction. Inspect long main-thread tasks, allocations, recomposition, and database work. For a larger synthetic notebook, compare current in-memory filtering against an indexed database query, measure both, and preserve behavior tests. Record device/build/dataset conditions and distributions rather than a single best timing. No performance outcome is claimed by the supplied benchmark source alone.

## Delivery and production exercise

Read [PRODUCTION](PRODUCTION.md), review the CI and manual artifact workflows, and perform a release rehearsal without store publication. Explain where signing keys, account access, mapping files, consent, support ownership, and rollback decisions belong. Select one alternative from the [requirements map](REQUIREMENTS.md) only after defining what behavior it must preserve.

Finish by writing a short design review: which correctness invariants are proven by tests, which depend on platform/manual review, and which require real deployment evidence. That distinction is part of modern engineering practice.
