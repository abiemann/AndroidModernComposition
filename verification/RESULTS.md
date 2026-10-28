# Verification record

Executed October 27, 2026, America/Los_Angeles. UTC timestamps in generated JUnit reports fall on October 28.

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
- Cross-toolkit UI tests needed to await Compose's return after Android 17 back navigation, rather than assuming Espresso's back action completed the Activity transition synchronously.
- Android 17's local-network permission required an explicit UI flow and a transport guard, retaining queued edits while access is denied.

These corrections have regression coverage. The [study guide](../docs/STUDY_GUIDE.md) connects the cases to the implementation.

## Visual review

The journal was inspected on the emulator in normal phone layout, at 200% font size, and in a tablet-sized dark window. The empty state now offers one creation control, avoiding overlapping duplicate actions at large text sizes. Images in this directory are screenshots, not performance or accessibility certification.

Full TalkBack traversal, all editor/settings states at large font sizes, RTL, foldables, physical device/battery profiling, hosted CI, and production operations remain in [TODO](../TODO.md).
