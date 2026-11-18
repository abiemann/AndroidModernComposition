# Verification and remaining work

This file separates implementation from evidence. A checked implementation item means source exists; test execution is recorded separately.

## Implemented study scope

- [x] Kotlin/Compose journal with MVVM, unidirectional state, navigation, editor validation, search, project filter, settings, and study screens.
- [x] Shared light/dark color roles and typography, focused journal hierarchy, grouped Settings, and accessible experiment disclosure; design choices documented for study.
- [x] Modular dependency direction, Koin composition, injectable clocks/IDs/repositories, and Android-free write policy.
- [x] Room relationships, pending mutations, conflict retention, tombstones, and a non-destructive migration.
- [x] Persistent demo remote and real Retrofit/OkHttp JSON boundary with a local SQLite REST service.
- [x] WorkManager scheduling, constraints, retry policy, notification permission flow, and worker injection.
- [x] DataStore preferences, Keystore-backed token encryption, restricted cleartext development host, and backup restriction.
- [x] Java/XML Activity/Fragment lifecycle lab; JVM JAR and Android AAR Maven publication definitions.
- [x] Local opt-in event counters/crash-type capture, stable control/compact-card experiment allocation, and resettable user override excluded from arm-level metrics.
- [x] JVM method-level tests; optional non-UI HTTP, database/migration, worker, and credential integration tests; manual macrobenchmark/profile-generation sources.
- [x] CI workflow, manual unsigned-bundle workflow, requirements map, study exercises, and production runbook.

## Current testing scope

Routine CI runs JVM method-level tests, lint, development APK builds, documentation link checks, and Python unit tests for test-report handling. Method tests cover domain policy, ViewModel actions and state, Java formatting, worker policy, and local telemetry. HTTP MockWebServer and DataStore boundary checks also run on the JVM.

The non-UI Android checks, the real Android-to-server check, and the Python HTTP suite are optional manual exercises. The explicitly dispatched release workflow also runs the Python HTTP suite. Routine CI runs without an emulator or Python server. Benchmark and profile generation remain separate manual performance exercises.

Verified locally October 30, 2026 with JDK 21.0.13: **43 JVM method tests passed**, with zero failures, errors, or skips. Demo debug lint and both development APK builds passed. The optional non-UI integration test APKs compile; device/server integration tests were not rerun for this scope change. Workflow syntax and 64 local documentation links pass. The revised CI configuration has not yet been run on GitHub. See [current verification](verification/RESULTS.md#current-test-policy-october-30-2026).

The November 1 interface update also passed 43 JVM tests, demo lint, and both debug builds. Manual emulator review covered representative Journal/Settings states, 200% text, a narrow phone window, and a wide dark window. A wrapped disclosure-label clipping issue was fixed and visually rechecked. See [interface verification and screenshots](verification/RESULTS.md#interface-improvements-november-1-2026); functional UI tests remain excluded.

The November 18 audit fixes passed 62 JVM tests, demo lint, both debug builds, 23 Python HTTP tests, and 4 reporting tests. The isolated API 36 emulator passed 28 non-UI Android checks and one Android-to-Python integration check. See [audit verification](verification/RESULTS.md#audit-fixes-november-18-2026) for scope and execution details.

## Historical executed verification (October 27–28, 2026)

These results describe the suite before the testing-scope change. Six UI tests in the recorded totals no longer exist; current checks and counts are recorded separately in [verification results](verification/RESULTS.md).

| Check | Result |
| --- | --- |
| Python local server tests | **Passed: 18 tests**, real HTTP sockets + SQLite, Python 3.13 on Windows. Covers auth, UTF-16 validation, stale/concurrent writes, replay/reopen, tombstones, and explicit HTTP connection framing. |
| Android host method tests | **Passed: 40 tests** across domain, data, feature, legacy, and app (including HTTP/DataStore boundaries and Robolectric consent/metrics). |
| Android lint and APK assembly | **Passed:** demoDebug/localDebug APKs; app lint for both flavors plus data/feature/legacy debug lint. Remaining lint warnings are dependency/toolchain update suggestions for the pinned compatible set. |
| Room migration/device tests | **Passed: 14 tests** on API 36.1 and 37, including migration, preferences persistence, sync races, pending deletion, and local-network permission/recovery. |
| Former UI/device suite | **Passed at the time:** 5 Compose tests + 5 app tests on API 36.1 and 37. The 5 Compose tests and 1 whole-app journey were later removed; the 4 non-UI Keystore/worker tests remain optional. |
| Real Android-to-Python HTTP | **Passed: 1 journey** on API 36.1 and 37, covering authenticated snapshot, locally pending create, upload, project/body edit, and deletion through Retrofit and SQLite. The runner cleans its temporary credentials/server/database. |
| Local Markdown target check | **Passed:** local file/directory targets resolve. External URLs and heading anchors are not covered by this script. |
| Former hosted GitHub Actions scope | **Passed: 40 JVM + 18 server + 24 device tests**, lint, and both debug assemblies. No failures, errors, or skips. Device CI then used API 36 AOSP ATD; the full Google APIs image also passed all 24 tests locally. Routine CI now runs only the host checks described above. See [historical evidence and image scope](verification/RESULTS.md#hosted-ci-investigation). |
| Macrobenchmark/profile generation | Gradle task graph resolves in dry-run. Release-derived artifacts and benchmark sources were not built/run; no profile or physical-device timing generated. |

## Further acceptance work

- [x] Historically ran the former device suite on Android 16/API 36.1 and current target Android 17/API 37.
- [x] Historically ran all 24 former device tests on API 36 Google APIs locally and API 36 AOSP ATD in hosted CI.
- [ ] Run the optional non-UI device suite on minimum API 26.
- [x] Visually inspect the journal at normal and 200% font size and in a tablet-sized dark window. This is a bounded layout review, not comprehensive accessibility certification.
- [ ] Complete manual TalkBack, all screens at large fonts, foldables, RTL, keyboard/switch access, and notification denial/settings revocation review on real devices.
- [ ] Exercise two local clients against the Python server, including simultaneous edits, disconnected deletion, token invalidation, and process termination during upload.
- [ ] Measure cold startup, jank, memory, and battery on representative physical devices; generate and validate a baseline profile before committing measured performance claims.
- [x] Verify a real hosted GitHub Actions run on the intended repository's main branch.
- [ ] Configure and review protected branch rules for the intended contribution/release process.

## Production work deliberately outside the sample

- [ ] Provide a hosted HTTPS service and external identity provider with account isolation, token rotation/expiry/revocation, and secure user switching.
- [ ] Decide notebook encryption, user deletion/export, retention, backup/restore, and privacy policy from a concrete threat model.
- [ ] Replace full snapshots/unbounded operation history with measured scalable sync and retention, preserving replay correctness.
- [ ] Configure a consent-aware crash/analytics provider, release mapping uploads, actionable alerting, dashboards, and support ownership.
- [ ] Extend the local A/B allocation/measurement lab with a governed remote rollout and analysis process before claiming product improvement.
- [ ] Configure release signing, protected CI secrets/environments, Play Console app/content declarations, staged rollout, rollback response, and actual distribution.
- [ ] Connect a private Maven repository only if there is a real package consumer and access policy.
- [ ] Record real usage, incidents, maintenance decisions, and product outcomes. An educational sample cannot manufacture production experience.

Before any release: reconcile this file and all affected documentation with final code and executed checks; validate links; commit the documentation in the source revision used for the artifact; then tag/build/publish in that order.
