# Verification and remaining work

This file separates implementation from evidence. A checked implementation item means source exists; test execution is recorded separately.

## Implemented study scope

- [x] Kotlin/Compose journal with MVVM, unidirectional state, navigation, editor validation, search, project filter, settings, and study screens.
- [x] Modular dependency direction, Koin composition, injectable clocks/IDs/repositories, and Android-free write policy.
- [x] Room relationships, pending mutations, conflict retention, tombstones, and a non-destructive migration.
- [x] Persistent demo remote and real Retrofit/OkHttp JSON boundary with a local SQLite REST service.
- [x] WorkManager scheduling, constraints, retry policy, notification permission flow, and worker injection.
- [x] DataStore preferences, Keystore-backed token encryption, restricted cleartext development host, and backup restriction.
- [x] Java/XML Activity/Fragment lifecycle lab; JVM JAR and Android AAR Maven publication definitions.
- [x] Local opt-in event counters/crash-type capture, stable control/compact-card experiment allocation, and resettable user override excluded from arm-level metrics.
- [x] Unit, HTTP, database/migration, Compose, and application test sources; macrobenchmark/profile-generation sources.
- [x] CI workflow, manual unsigned-bundle workflow, requirements map, study exercises, and production runbook.

## Executed verification

| Check | Result |
| --- | --- |
| Python local server tests | **Passed: 18 tests**, real HTTP sockets + SQLite, Python 3.13 on Windows. Covers auth, UTF-16 validation, stale/concurrent writes, replay/reopen, tombstones, and explicit HTTP connection framing. |
| Android host unit tests | **Passed: 40 tests** across domain, data, feature, legacy, and app (including Robolectric consent/metrics). |
| Android lint and APK assembly | **Passed:** demoDebug/localDebug APKs; app lint for both flavors plus data/feature/legacy debug lint. Remaining lint warnings are dependency/toolchain update suggestions for the pinned compatible set. |
| Room migration/device tests | **Passed: 14 tests** on API 36.1 and 37, including migration, preferences persistence, sync races, pending deletion, and local-network permission/recovery. |
| Compose/device/end-to-end tests | **Passed:** 5 Compose tests + 5 app tests on API 36.1 and 37, including create/recreate/edit/search/legacy/delete, Keystore tamper/atomic recovery, and worker outcomes. |
| Real Android-to-Python HTTP | **Passed: 1 journey** on API 36.1 and 37, covering authenticated snapshot, locally pending create, upload, project/body edit, and deletion through Retrofit and SQLite. The runner cleans its temporary credentials/server/database. |
| Local Markdown target check | **Passed:** local file/directory targets resolve. External URLs and heading anchors are not covered by this script. |
| Hosted GitHub Actions | Host checks passed: 40 JVM tests, 18 server tests, lint, and debug assemblies. A captured System UI ANR dialog explained the hosted focus failure. Runner disk cleanup and compilation before emulator startup with bounded resources are supplied; full hosted rerun pending. All 24 device tests pass locally on the exact API 36 image. See [evidence](verification/RESULTS.md#hosted-ci-investigation). |
| Macrobenchmark/profile generation | Gradle task graph resolves in dry-run. Release-derived artifacts and benchmark sources were not built/run; no profile or physical-device timing generated. |

## Further acceptance work

- [x] Run the device suite on Android 16/API 36.1 and current target Android 17/API 37.
- [ ] Run the device suite on minimum API 26 and the exact API 36 image selected by hosted CI.
- [x] Visually inspect the journal at normal and 200% font size and in a tablet-sized dark window. This is a bounded layout review, not comprehensive accessibility certification.
- [ ] Complete manual TalkBack, all screens at large fonts, foldables, RTL, keyboard/switch access, and notification denial/settings revocation review on real devices.
- [ ] Exercise two local clients against the Python server, including simultaneous edits, disconnected deletion, token invalidation, and process termination during upload.
- [ ] Measure cold startup, jank, memory, and battery on representative physical devices; generate and validate a baseline profile before committing measured performance claims.
- [ ] Test a real hosted GitHub Actions run after the repository has its intended remote and branch rules.

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
