# Field Notes

An educational Android app for studying modern application development through a complete offline journal workflow. Create observations, organize them into projects, search and edit them, resolve synchronization conflicts, and inspect the boundaries that make those behaviors testable.

The primary stack is **Kotlin, Jetpack Compose, Material 3, MVVM with unidirectional state, Koin, coroutines/Flow, Room, DataStore, Retrofit/OkHttp, and WorkManager**. A Java/XML Fragment lab demonstrates existing Android code alongside Compose. This is a study application, not evidence of a public launch or a substantial user base.

## Start here

1. Open the repository root in Android Studio with support for Android Gradle Plugin 9.4.1.
2. Select JDK 21 as the Gradle runtime. Gradle 9.6 also supports the locally used JDK 25; Java/Kotlin bytecode targets Java 17.
3. Install Android SDK Platform 37 (`platforms;android-37.0`) and Build Tools 36.0.0 or the compatible version selected by AGP. Set the SDK location through Android Studio or an untracked `local.properties` file.
4. Select the `demoDebug` build variant and run on an emulator or device with Android 8.0/API 26 or later.

The **demo** flavor needs no account, server, or internet connection. Its simulated remote service persists separately from the journal tables in the same local database, so it can exercise the synchronization protocol across app restarts. A demo sync means “stored by the local simulator,” not “backed up to a cloud service.”

Use **localDebug** for the real HTTP path. Start the included [Python server](docs/API.md), enter its bearer token in Settings, and synchronize through the emulator's `10.0.2.2:8080` host alias. `localDebug` alone permits cleartext to that development host. Release variants do not permit HTTP. This local service is a single shared study workspace, not a multi-user identity system.

On Android 17/API 37 or later, the local flavor also needs `ACCESS_LOCAL_NETWORK`. Settings explains the reason and requests access only after **Allow local network access** is pressed; denial offers an Android app-settings recovery path. The local transport checks permission before making an HTTP request. Without it, saved observations remain available and new edits stay pending on-device. The demo flavor does not request this permission.

## What to explore

- **Journal:** create, search, filter by project, edit, delete, and inspect pending or conflict states.
- **Synchronization:** durable outbox, stable operation IDs, optimistic server versions, retry/backoff, and explicit conflict choices.
- **Settings:** stable local control/compact-card experiment assignment with a resettable override, optional local diagnostics, notification permission, and encrypted bearer-token storage for the local service.
- **Study:** architecture notes and a Java Activity/Fragment/XML interoperability example.
- **Tests:** domain policy, ViewModel state, HTTP contracts, Room transactions/migration, Compose interaction, worker policy, and whole-app journeys.

Read the [requirements map](docs/REQUIREMENTS.md), then follow the [study guide](docs/STUDY_GUIDE.md). The [design](DESIGN.md) explains decisions and invariants. The [production runbook](docs/PRODUCTION.md) separates implemented safeguards from deployment and operations work that still needs a real organization and service.

## Project structure

| Module | Responsibility |
| --- | --- |
| [`app`](app/build.gradle.kts) | Composition root, application/activity lifecycle, worker factory, security, diagnostics, build flavors. |
| [`domain`](domain/build.gradle.kts) | Android-free models, repository contracts, validation/use case; publishable JVM JAR. |
| [`data`](data/build.gradle.kts) | Room source of truth, durable outbox/conflicts, preferences, demo and HTTP remotes. |
| [`feature`](feature/build.gradle.kts) | Compose screens, navigation, themes, immutable UI state, ViewModels. |
| [`legacy`](legacy/build.gradle.kts) | Java/XML Activity and Fragment with view-lifecycle cleanup; publishable Android AAR. |
| [`benchmark`](benchmark/build.gradle.kts) | Cold-start macrobenchmark and a baseline-profile generation journey. |
| [`server`](server/fieldnotes_server.py) | Local authenticated REST server backed by SQLite, with HTTP tests. |

## Build and validate

Use `./gradlew` on macOS/Linux and `./gradlew.bat` in Windows PowerShell. The wrapper pins Gradle 9.6.0. AGP 9.4.1 supplies built-in Kotlin 2.2.10 for Android modules; the JVM and Compose compiler plugins use the matching version. Do not add the old Kotlin Android plugin to these modules. The version catalog centralizes an intentionally compatible dependency set; lint may report newer versions. Evaluate upgrades together and rerun validation rather than treating these pins as the newest available releases.

```text
./gradlew :domain:test :data:testDebugUnitTest :feature:testDebugUnitTest :legacy:testDebugUnitTest :app:testDemoDebugUnitTest
./gradlew :app:lintDemoDebug :app:assembleDemoDebug :app:assembleLocalDebug
python -m unittest discover -s server -v
python scripts/verify-doc-links.py
```

With an API 26+ device attached:

```text
./gradlew --no-parallel :data:connectedDebugAndroidTest :feature:connectedDebugAndroidTest :app:connectedDemoDebugAndroidTest
```

Device suites run sequentially so separate test apps do not compete for the same foreground window.

For an actual Android → HTTP → Python → SQLite integration test, boot an emulator and run the Windows helper. It creates a private temporary database and random token, refuses an occupied port, runs only the local-server integration class, then stops its own server and removes its temporary data:

```powershell
./scripts/verify-local-server.ps1 -PythonExecutable 'C:\path\to\python.exe'
```

Use a real Python executable, not the Windows Store alias. The optional `-Port 8081` selects another port. The generated credential is passed through the Gradle environment and is never printed; test reports may contain the synthetic observations. See the [real HTTP exercise](docs/STUDY_GUIDE.md#3-offline-operation-and-eventual-transfer) for the manual workflow.

JUnit XML and HTML reports appear under each module's `build/test-results` and `build/reports`; device results appear under `build/outputs/androidTest-results`. [CI](.github/workflows/ci.yml) runs the host checks and an API 36 emulator suite. A workflow definition is not a claim that hosted CI has run.

Verification completed on October 27, 2026 (Pacific): **83 distinct tests passed**: 40 JVM tests, 25 Android/device tests, and 18 Python server tests. The Android suite, including real Android-to-server synchronization, passed on Android 16/API 36.1 and Android 17/API 37 emulators. Both debug APK variants assemble, and all selected Android lint checks pass. See the [verification record](verification/RESULTS.md) for commands, evidence, warnings, and scope, and [TODO](TODO.md) for remaining acceptance work.

For baseline profiles and timing, see the [performance exercise](docs/STUDY_GUIDE.md#performance-exercise). No startup time, frame-rate improvement, or memory/battery claim is made without a measured result.

## Releases and library packaging

Before creating, rebuilding, or publishing any release: review README, TODO, DESIGN, requirements, and production documentation against the final implementation; update them; validate links and applicable checks; and commit those updates in the exact source revision used for the release. Only then create tags or build release artifacts. The [manual release workflow](.github/workflows/release.yml) requires an explicit documentation-review acknowledgment and builds an unsigned demo bundle. It neither signs nor publishes to Google Play.

The domain JAR and legacy AAR have Maven publications targeting their own local `build/study-maven` directories. After the same documentation review, `:domain:publishDomainPublicationToStudyRepository` and `:legacy:publishLegacyPublicationToStudyRepository` exercise packaging without sending anything to an external registry. A private Maven service requires credentials and access controls outside this repository.

No secrets, signing keys, hosted crash-reporting credentials, or Play credentials belong in source control. The notebook itself is stored in app-private Room storage, **not an encrypted database**. The bearer token uses Android Keystore-backed authenticated encryption. Read the [security limitations](docs/PRODUCTION.md#security-and-privacy-boundaries) before adapting this sample for sensitive real data.
