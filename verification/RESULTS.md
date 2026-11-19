# Verification record

## Audit feedback fixes (November 18, 2026)

Validation on Temurin JDK 21.0.13 passed **90 JVM tests** (domain 11, data 25, feature 39, legacy 2, app 13), both debug APK builds, and all three non-UI test APK assemblies. Both debug lint tasks passed in a fresh process after the original Gradle JVM crashed in native `zip.dll` during lint. The merged manifests for both flavors disable backup and retain the Android 12+ data extraction rules. The Python suites passed **24 server tests** and **4 report tests**, and workflow syntax, documentation links, and whitespace checks passed.

On the isolated API 36 emulator, **48 non-UI Android checks** passed (41 data and 7 app), plus **one live Android-to-Python HTTP test**. Regression coverage includes read-only DataStore access after ID initialization, a 100-mutation backlog with one snapshot, snapshot cadence, a slow batch that continues without hitting its hard deadline, a WorkManager handoff that completes on its first attempt, and local recovery that preserves unrelated queue entries and rejects stale drafts. No functional UI tests were added or run.

## Follow-up engineering audit (November 18, 2026)

The final Gradle build on Temurin JDK 21.0.13 passed **87 JVM tests** (domain 11, data 24, feature 37, legacy 2, app 13), both debug APK builds, both debug lint tasks, and all three non-UI test APK assemblies. The profile task graphs resolved in a Gradle dry run. The Python suites passed **24 server tests** and **4 report tests**. Workflow syntax, documentation links, and whitespace checks passed.

Android's instrumentation runner passed **41 non-UI checks** (34 data and 7 app) on the isolated API 36 Google APIs emulator. A separate **live Android-to-Python HTTP test** passed against a temporary server database. No functional UI tests were added or run.

Regression coverage includes the WorkManager handoff during a running drain, retry exhaustion, outbox watermarks and cursor fairness, exact replay after a lost acknowledgment, Room 2-to-3 migration, unrecognized persisted states, decoded HTTP byte and row limits, text and selection restoration, cancellable search, and foreground sync deadlines. Full snapshot performance and user interaction on physical devices remain separate measurements.

## Audit fixes (November 18, 2026)

Validation on Temurin JDK 21.0.13 passed **62 JVM tests** (domain 11, data 17, feature 26, legacy 2, app 6), demo debug lint, both debug APK builds, and all three non-UI test APK builds. The Python suites passed **23 server tests** and **4 test-report tests**. Workflow syntax, local documentation links, and whitespace checks passed.

On the isolated API 36 Google APIs emulator, **28 non-UI Android checks** passed (24 data and 4 app), along with **one live Android-to-Python HTTP check** using a temporary SQLite database. Gradle's connected test launcher raised `UnsupportedOperationException` before running tests on this machine, so these counts come from invoking the same built APKs directly with Android's instrumentation runner on the isolated emulator. No functional UI tests were added or run.

The regression cases cover stale edits after a pulled update or deletion, rejected writes followed by successful unrelated sync, replay after a lost acknowledgment and a truncated retry, draft restoration and recovery, diagnostics opt-out during an exposure read, preferences recovery while a collector stays active, and terminal worker outcomes that allow queued follow-up work to run.

## Interface improvements (November 1, 2026)

The interface changes retain the method-level testing policy below. **43 JVM tests passed** (domain 11, data 10, feature 16, legacy 2, app 4), with zero failures/errors/skips. Feature method tests were rerun after the final large-text adjustment. Demo debug lint and both demo/local debug APK builds passed with Temurin JDK 21.0.13. Whitespace checks and 73 local documentation targets pass. No automated functional UI tests were added or run, and no device/server integration suite was rerun for this change.

One intermediate copy-only build completed both APKs but its lint process hit a native `zip.dll` access violation in the Java runtime. The retry passed, as did the subsequent final feature-test/lint/build check with one Gradle worker. No application test assertion was disabled to obtain a pass.

Manual rendered inspection used an isolated Pixel 6/API 36 Google APIs emulator with synthetic note data. It covered the empty and populated journal, full/compact cards, a project filter with no matches, the editor, Settings with collapsed/expanded experiment details, and scrolling enlarged Settings. Phone review included light/dark mode and 100%/200% system font; narrow review used a 320 dp window, and wide review a 1280 × 800 dp window with the navigation rail. The large-font review found clipping in the disclosure button's wrapped label; using the small Material shape instead of a capsule fixed it, and the updated render was inspected again.

The local flavor's **Server access** section with no saved token was also inspected at normal font size on API 36. No credential was entered and no live-server request was validated. The isolated emulator's display settings were restored and the emulator was closed after review.

Representative screenshots: [empty journal](ui-empty-light.png), [empty journal at 200% text](ui-empty-large-font.png), [populated journal](ui-journal-light.png), [expanded dark Settings](ui-settings-dark.png), [Settings at 200% text](ui-settings-large-font.png), and [wide dark journal](ui-tablet-dark.png). These are rendered review evidence, not a functional UI regression suite.

A one-off sRGB contrast calculation checked the declared foreground/container pairs and normal text across the theme surfaces. All checked normal-text pairs exceed 4.5:1; the lowest is light secondary text on `surfaceDim` at 4.688:1. The normal `outline` role exceeds 3:1 across the surfaces checked. `outlineVariant` is intentionally subtler and does not meet 3:1; it is not evidence of an independently sufficient control boundary. Labels, icons, switches, and selected fills must carry the meaning. These palette checks do not cover composited disabled states or certify full accessibility.

Full TalkBack traversal, every screen/state at large fonts, RTL, foldables, physical-device performance, and Android 17 permission-dialog review remain separate acceptance work. See [TODO](../TODO.md).

## Current test policy (October 30, 2026)

Routine verification uses local JVM tests that call domain, repository, ViewModel, worker-policy, telemetry, and Java formatter methods. There are no functional UI tests: the five Compose tests and the single whole-app navigation journey have been removed, along with their UI-test dependencies and the CI emulator job. New direct ViewModel checks cover edit identity, deletion outcomes, and draft restoration without launching an Activity.

Non-UI database, platform, and HTTP integration suites remain available as optional manual checks. The benchmark module remains a separate manual performance exercise. Neither is part of routine CI. The report summarizer defaults to host reports so old device results cannot be mistaken for current unit-test results; `--scope device` selects the optional data/app device reports.

Local validation passed on October 30 using Temurin JDK 21.0.13: **43 JVM tests** (domain 11, data 10, feature 16, legacy 2, app 4), zero failures/errors/skips, demo debug lint, and both demo/local debug APK builds. Data and app demo/local non-UI integration test APKs also compile; no emulator or server integration suite was run for this change. Workflow syntax, the optional Bash helper's syntax, whitespace checks, and 64 local documentation targets pass. The revised workflow has not yet been run on GitHub.

An initial Java 25 run passed the method tests but the Java runtime crashed in a native garbage-collector thread during APK packaging. The complete validation rerun succeeded with the recommended Java 21 runtime used by CI. This was not an application test failure.

The historical evidence below describes the previous test policy and source revision, not the current routine suite.

## Historical verification (October 27–28, 2026)

Initial app verification executed October 27, 2026, America/Los_Angeles. The CI investigation continued October 28. UTC timestamps in the initial generated JUnit reports fall on October 28. These results apply to source through commit `4e2b2b2`, before the UI-test removal.

## Results

| Check | Evidence |
| --- | --- |
| JVM tests | 40 passed: domain 11, data 10, feature 13, legacy 2, app 4. |
| Android tests | 25 distinct cases passed on both Android 16/API 36.1 (Medium Phone) and Android 17/API 37 (Pixel 9 Pro XL). Data 14, Compose 5, app 5, real HTTP 1. |
| Python server | 18 passed with Python 3.13.7, actual loopback HTTP requests, and SQLite. |
| APKs | demoDebug and localDebug assembled with Gradle 9.5.0, AGP 9.3.2, JDK 25.0.2, Kotlin/Compose compiler 2.2.10, compile/target SDK 37. |
| Lint | app demoDebug/localDebug, data debug, feature debug, and legacy debug passed. No lint errors; remaining warnings suggest newer dependency/toolchain versions. |
| Documentation | Relative Markdown file/directory links validated. External URL availability and heading anchors are outside the checker. |
| Benchmark configuration | Both benchmark/profile compilation task graphs resolve in --dry-run. No release-derived artifact, app-specific baseline profile, or timing result produced. |

**83 distinct tests passed.** The device cases were repeated across Android versions; repeats are not added to the distinct-case count. Hosted CI passed 82 of these cases (the real Android-to-Python journey remains a separate local check). API 26, physical-device performance, signed releases, and Play deployment remain unverified.

## Hosted CI investigation

The [initial hosted run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40638966296) failed during Android SDK setup because `setup-android@v3` requested the retired `tools` package. No app tests ran in that attempt. The workflows now use v4.

The [next main run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40639357512) passed 40 JVM tests, 18 server tests, lint, and both debug assemblies. Its emulator could not start: the data partition needed 7.2 GB but only 5.7 GB was free. The configured smaller partition did not reduce the image's actual requirement, so the device job was changed to free the hosted runner's unused preinstalled NDK toolchains instead.

A [parallel update run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40639050266) reached all 24 device tests and exposed one failure at the Compose-to-Views Activity handoff. The end-to-end test was updated to await the intended resumed, focused window and explicitly select its decor before Espresso assertions and Back, then await MainActivity focus on return. All five app device tests passed locally on API 36.1 and 37, and all 24 device tests passed in CI order on a clean Pixel 6/API 36 Google APIs revision 7 image, with animations disabled.

The [captured hosted failure](https://github.com/abiemann/AndroidModernComposition/actions/runs/40648093705) identified the focus owner: a **System UI ANR dialog**, above the app before any journal interaction. WindowManager and InputDispatcher agreed, and the app's input connection was responsive. The [screenshot](ci-system-ui-anr.png) records the evidence. Compose semantic actions can run behind another window, which explained why the real-window preconditions and Espresso checks exposed that failure. A deliberately sleeping local test device also verified that the diagnostic path preserves failure, reports the device state, and captures its screenshot; the normal suite passed again after waking it.

That CI revision built the test APKs before starting the emulator, limited Gradle memory/workers, and used an in-process Kotlin compiler with a single-use Gradle process. Both the [resource-bounded run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40651241577) and the [pinned emulator 37.1.11 comparison](https://github.com/abiemann/AndroidModernComposition/actions/runs/40653933578) still captured the System UI ANR. The latter retrieved system traces: KeyguardService exceeded its 20-second startup deadline, with System UI's main thread accumulating 15.389 seconds of runnable wait against 0.620 seconds of CPU time. Several Google services also timed out during boot, before the app started. This supports boot scheduling pressure, not an app ANR or an established emulator-version regression.

The hosted functional suite then selected the official API 36 AOSP Automated Test Device image, which omits System UI and unrelated preinstalled apps/services and reduces rendering work. It verifies app behavior, not system-UI integration. The app has no Google Play services dependency. All 24 test cases and window-focus assertions were enabled at that revision. ATD does not replace the full-image visual checks or physical-device performance acceptance; see [Android's ATD guidance](https://developer.android.com/studio/test/managed-devices#use-atd). Failure collection then retained screenshots, the last ANR report, and system traces with bounded diagnostic commands.

**[Hosted run 40655721754 passed](https://github.com/abiemann/AndroidModernComposition/actions/runs/40655721754)** at source commit `510d530`: 40 JVM tests, 18 Python server tests, 24 Android tests (14 data, 5 Compose, 5 app), lint, and both development APK assemblies. The JUnit summaries report zero failures, errors, and skipped cases. No test was removed or assertion disabled to obtain the pass.

The separate AGP 9.4.1 dependency PR also required a companion Gradle 9.6.0 wrapper upgrade, verified against the [AGP compatibility requirements](https://developer.android.com/build/releases/agp-9-4-0-release-notes). That repair stays on the dependency PR; main retains its tested AGP 9.3.2 / Gradle 9.5.0 pair.

## Historical reproduction

Use [README](../README.md) for current commands. At historical revision `4e2b2b2`, the full debug check used the following command, which includes the now-removed UI suites:

```text
./gradlew :domain:test :data:testDebugUnitTest :feature:testDebugUnitTest :legacy:testDebugUnitTest :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleLocalDebug :app:lintDemoDebug :app:lintLocalDebug :data:lintDebug :feature:lintDebug :legacy:lintDebug :data:connectedDebugAndroidTest :feature:connectedDebugAndroidTest :app:connectedDemoDebugAndroidTest --no-parallel
python -m unittest discover -s server -v
python scripts/summarize-tests.py --require-results
python scripts/verify-doc-links.py
```

With one emulator running, scripts/verify-local-server.ps1 separately starts an owned local server with a fresh token and isolated database and runs the localDebug real-HTTP test. It grants the local test app's Android 17 local-network permission for the transport journey; separate tests exercise denied permission without accessing the network. The normal app requires an explicit Settings action.

Generated reports remain under each module's build/reports, build/test-results, and build/outputs/androidTest-results. api36-results.txt records the passing aggregate before switching emulator versions. Build/report files are regenerable and ignored; a previous failed run does not count as a pass. Final reports have zero failures, errors, or skips.

## Defects exposed by validation

- A newer Espresso test dependency was needed for Android 16's input APIs.
- An interrupted atomic credential-file replacement required reading through AtomicFile's backup recovery.
- Pending deletion tombstones had to remain observable for correct synchronization counts.
- Server text limits needed to match Kotlin's UTF-16 units, including emoji.
- The Python HTTP/1.0 server needed an explicit Connection: close response to prevent OkHttp reusing a closed socket when connection retries are intentionally disabled. Real Android-to-server testing caught this after isolated tests passed.
- Cross-toolkit UI tests needed to await the intended resumed, focused Activity in both directions and explicitly select the legacy window for Espresso. Compose idleness and Espresso actions do not guarantee that another Activity's window transition has completed.
- Android 17's local-network permission required an explicit UI flow and a transport guard, retaining queued edits while access is denied.

These corrections had regression coverage at that historical revision. The [study guide](../docs/STUDY_GUIDE.md) connects the cases to the implementation.

## Visual review

The journal was inspected on the emulator in normal phone layout, at 200% font size, and in a tablet-sized dark window. The empty state now offers one creation control, avoiding overlapping duplicate actions at large text sizes. Images in this directory are screenshots, not performance or accessibility certification.

Full TalkBack traversal, all editor/settings states at large font sizes, RTL, foldables, physical device/battery profiling, and production operations remain in [TODO](../TODO.md).
