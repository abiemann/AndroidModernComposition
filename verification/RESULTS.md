# Verification record

Initial app verification executed October 27, 2026, America/Los_Angeles. The CI investigation continued October 28. UTC timestamps in the initial generated JUnit reports fall on October 28.

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

**83 distinct tests passed.** The device cases were repeated across two Android versions; repeats are not added to the distinct-case count. Hosted CI, API 26, physical-device performance, signed releases, and Play deployment remain unverified.

## Hosted CI investigation

The [initial hosted run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40638966296) failed during Android SDK setup because `setup-android@v3` requested the retired `tools` package. No app tests ran in that attempt. The workflows now use v4.

The [next main run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40639357512) passed 40 JVM tests, 18 server tests, lint, and both debug assemblies. Its emulator could not start: the data partition needed 7.2 GB but only 5.7 GB was free. The configured smaller partition did not reduce the image's actual requirement, so the device job now frees the hosted runner's unused preinstalled NDK toolchains instead.

A [parallel update run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40639050266) reached all 24 device tests and exposed one failure at the Compose-to-Views Activity handoff. The end-to-end test now awaits the intended resumed, focused window and explicitly selects its decor before Espresso assertions and Back, then awaits MainActivity focus on return. All five app device tests passed locally on API 36.1 and 37, and all 24 device tests passed in CI order on a clean Pixel 6/API 36 Google APIs revision 7 image, with animations disabled.

The [captured hosted failure](https://github.com/abiemann/AndroidModernComposition/actions/runs/40648093705) identified the focus owner: a **System UI ANR dialog**, above the app before any journal interaction. WindowManager and InputDispatcher agreed, and the app's input connection was responsive. The [screenshot](ci-system-ui-anr.png) records the evidence. Compose semantic actions can run behind another window, which is why the real-window preconditions and Espresso checks remain essential. A deliberately sleeping local test device also verified that the diagnostic path preserves failure, reports the device state, and captures its screenshot; the normal suite passed again after waking it.

CI now builds the test APKs before starting the emulator, limits Gradle memory/workers, and uses an in-process Kotlin compiler with a single-use Gradle process. The [resource-bounded run](https://github.com/abiemann/AndroidModernComposition/actions/runs/40651241577) still captured the System UI ANR, so resource contention is not an established cause. The next controlled comparison pins emulator 37.1.11 (build 15917651), the runtime that passed locally, while retaining API 36 and all assertions. Failure collection also retrieves the last ANR report and system ANR traces when the test emulator permits root access. A passing full hosted run remains pending.

## Reproduce

Use the setup and commands in [README](../README.md). The final full debug check used:

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

These corrections have regression coverage. The [study guide](../docs/STUDY_GUIDE.md) connects the cases to the implementation.

## Visual review

The journal was inspected on the emulator in normal phone layout, at 200% font size, and in a tablet-sized dark window. The empty state now offers one creation control, avoiding overlapping duplicate actions at large text sizes. Images in this directory are screenshots, not performance or accessibility certification.

Full TalkBack traversal, all editor/settings states at large font sizes, RTL, foldables, physical device/battery profiling, hosted CI, and production operations remain in [TODO](../TODO.md).
