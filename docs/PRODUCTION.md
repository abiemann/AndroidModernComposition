# Production readiness and operating exercises

Field Notes implements a teaching app and local service. It has **not** established a Play release, signed production distribution, public service, active user base, production uptime, crash-free rate, experiment result, or measured performance improvement. This document describes how to obtain those capabilities and evidence without pretending they already exist.

## Security and privacy boundaries

| Boundary | Current behavior | Required decision before production |
| --- | --- | --- |
| Identity | One manually configured opaque bearer token for a shared local server workspace. | Use a vetted identity provider, server-side per-user authorization, short-lived tokens, refresh/revocation, logout semantics, and secure account switching. |
| Credential storage | AES-GCM ciphertext in a no-backup atomic file; key held by Android Keystore. | Validate device/key-invalidation recovery and decide whether user authentication should protect key use. Keystore does not protect an already compromised app process from using a key. |
| Notebook | Room files in app-private storage, with application backup disabled. | Classify data and decide database/field encryption, retention, export, deletion, backup/restore, and key recovery. App-private storage is not equivalent to a separately encrypted database. |
| Transport | Default cleartext prohibition; only `localDebug` allows `10.0.2.2` for the lab. | Supply an actual HTTPS endpoint and certificate lifecycle. Do not broaden a debug exception into release configuration. |
| Component access | Launcher exported; legacy Activity private; immutable notification intent. | Review every added activity/provider/service/receiver and intent input. |
| Permissions | Internet/network state and opt-in notification permission; only the local flavor declares Android 17 local-network access, requested explicitly in Settings and checked before HTTP. | Add permissions only for a real user feature; design denial/revocation recovery and data minimization first. |
| Logging/diagnostics | Optional local counters and exception type; no token/note-body events or HTTP logging. | Verify third-party SDK payloads, consent, deletion, retention, regional requirements, and access control. |
| Local backend | Token checks, strict JSON/size validation, parameterized SQL, conditional versions and replay history. | HTTPS, account isolation, bounded concurrency/rate limits, migrations, backups, monitoring, and scalable sync/retention are still required. |

Signing out removes the credential; it does **not** erase local observations. The sample has no account switching. A future multi-account app must namespace or clear local data safely and address pending writes before attaching another account's token. Do not add an “erase” button without a deliberate user-visible data-loss policy.

The demo remote resides on the same device as the notebook. Device loss, app-data clearing, or uninstall removes both; demo synchronization is not backup. The local Python database is also only a file until actual backup and restore processes exist.

## Before creating or rebuilding any release

1. Compare the final code and intended artifact scope with README, TODO, DESIGN, requirements, API, study guide, and this runbook. Correct implemented behavior, executed results, and outstanding work.
2. Run the link checker and relevant host/device checks. Investigate failures rather than relabeling them as “expected.” Record any acceptance test not run.
3. Review permissions, merged manifests, network security configuration, dependency changes, migration paths, version code/name, shrinker behavior, and sensitive logging.
4. Commit the documentation and source together in the intended release revision. Verify the working tree and revision before creating a tag or building an artifact.
5. Only then run release artifact tasks. The supplied manual workflow requires documentation-review acknowledgment, rechecks docs/tests/lint, and produces an **unsigned demo AAB** with R8 mapping output. It does not create a tag, sign, upload, or publish.

The demo and local flavors are teaching environments. A real distribution needs its own configured HTTPS service and account model. Do not ship the emulator address as a production endpoint or label a local simulator as cloud storage.

## CI and supply chain

[CI](../.github/workflows/ci.yml) checks Python HTTP behavior, local documentation targets, JVM tests, Android lint/assemblies, and an API 36 emulator suite. It runs on pushes to `main`, pull requests, and manual dispatch; branch pushes do not duplicate pull request runs. Android setup uses v4 because v3's default installation requests the retired SDK `tools` package. Test summaries run only when the corresponding test step was attempted, so an SDK setup failure does not also produce a misleading missing-report failure. Configure branch protection, required checks, and contribution policy before claiming protected delivery. Hosted execution evidence is recorded in [verification results](../verification/RESULTS.md).

Use protected secrets/environments for signing and deployment. Never echo credentials, put them in Gradle source, or pass them in reusable command lines. Review and pin third-party action revisions in the organization's release policy; Dependabot is configured for Gradle and GitHub Actions updates. Review dependency/plugin updates as a compatible set, then rerun behavior and migration checks. The wrapper pins a Gradle distribution version; validate its checksum when changing it.

The CI device job removes the hosted runner's preinstalled Android NDK toolchains, which this Kotlin/Java project does not use, to make room for the SDK image and emulator data partition. Reducing the configured partition size did not reduce this image's actual disk requirement. If native compilation is introduced, revise that cleanup and budget runner disk explicitly. The [device wrapper](../scripts/verify-device.sh) assembles test APKs before booting the emulator, caps Gradle at two workers and a 2 GB heap, and runs Kotlin in that process; the single-use build process exits before emulator startup. This avoids competing compiler processes while the emulator initializes. On a window-readiness failure, the end-to-end test records device/window state and captures a screenshot; the wrapper collects the screenshot before shutdown while preserving the failing test exit code.

The local Maven publications teach JAR/AAR packaging without any external account. An actual private registry needs authenticated publishing, consumer access, immutable version policy, source/license metadata, and credential rotation.

## Google Play and staged delivery

For a real app: choose a permanent application ID, provision a protected upload key and Play App Signing, configure CI access with least privilege, complete store/content/privacy/data-safety declarations from actual behavior, and review current target/API policy. Test a signed release bundle and migration from the previous public version. Use internal testing before a staged rollout; observe crashes, ANRs, core-flow success, and backend errors before increasing distribution. Check current Play documentation when performing the release because requirements change.

An Android versionCode cannot move backward. A rollback often means halting a rollout and publishing a corrected build with a greater versionCode, while preserving database/network compatibility. Server and client deploys need a compatibility window so a previously installed version remains usable. Write a recovery plan before changing persistent schemas or synchronization semantics.

## Monitoring and incident response

The current telemetry boundary supports typed, content-free events. A production provider must document consent, payload scrubbing, transport, retention, access, and deletion. Capture build/version/environment context, upload R8 mappings under controlled access, and verify a synthetic nonfatal event end to end before relying on alerts. The local crash-type handler is not remote crash reporting, symbolication, or ANR monitoring.

Define actionable signals: durable-save failure rate, synchronization backlog age, auth rejection rate, conflict rate, network failure rate, crash/ANR rate, and measured startup/jank distributions. Avoid putting note text, bearer tokens, raw database dumps, or user identifiers into incident tickets by default. Define ownership and escalation for a failed release or prolonged sync outage.

Exercise an incident with synthetic data: stop the local server, observe retries and retained work, restore it, and verify convergence. Repeat with an incompatible response and an invalid token to prove they are distinguished from transient outages. For data corruption, preserve a private diagnostic copy under an explicit policy and prefer a migration/repair path to silent destructive reset.

## Feature flags and experiments

The compact-card lab implements stable local A/B allocation: a randomly generated installation ID is persisted in DataStore and hashed with a versioned experiment ID into equal control/compact buckets. The identifier stays on-device. An explicit layout override is excluded from arm-level measurements, and resetting it restores the assigned arm. Opt-in local exposure and post-exposure note-save counters demonstrate instrumentation without an external analytics account.

This lab has no remote configuration/kill switch, aggregate collection, statistical conclusion, or measured improvement. A production experiment additionally needs governed eligibility, exposure definition, primary metric, guardrail metrics, duration/sample-size plan, consent/retention policy, and analysis that handles missing data and multiple comparisons. Local assignment correctness does not prove unbiased product conclusions or a successful rollout.

A warehouse such as Snowflake and SQL analysis are infrastructure choices, not prerequisites for a journal. Start with a defined product question and synthetic event analysis. Do not report product improvement until a real, correctly analyzed experiment supports it.

## Evidence of maintenance

Keep release notes, migration decisions, verified compatibility, incident reviews, support outcomes, and measured follow-up changes. Cite real evidence with dates and limitations. Those records can eventually demonstrate production experience; source code and a case-study checklist alone cannot.
