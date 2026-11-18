# Local synchronization protocol

The included Python standard-library server is a **single-account teaching service**. It demonstrates a real authenticated REST boundary, relational storage, optimistic concurrency, deletion tombstones, and replay-safe writes. It is not a public production service or a substitute for an identity provider.

## Run the server

From the repository root, use Python 3.11 or later:

```powershell
$env:FIELDNOTES_TOKEN = python -c "import secrets; print(secrets.token_urlsafe(32))"
python server/fieldnotes_server.py
```

The token stays in the current shell environment. Enter that same token in the app's sign-in screen. Do not put it in a build file, source control, screenshot, or log. The server accepts 24–4096 printable ASCII characters without whitespace. This shared lab token demonstrates bearer authentication; account registration, token issuance, expiration, refresh, revocation, and authorization per user require a real identity service before public deployment.

Defaults: bind `127.0.0.1:8080`, persist to `server/fieldnotes.sqlite3`. Override with `--host`, `--port`, or `--database`. An Android emulator reaches the host loopback through `http://10.0.2.2:8080/`. For a USB device, `adb reverse tcp:8080 tcp:8080` forwards the device loopback to the host. A physical device over Wi-Fi requires an explicitly selected bind address and firewall rule; do not expose this lab server to an untrusted network.

The supplied `localDebug` configuration targets the emulator alias `10.0.2.2`. To use USB forwarding, first change `API_BASE_URL` to `http://127.0.0.1:8080/` and change the matching `localDebug` network-security domain; forwarding alone does not change the app's endpoint. A Wi-Fi device similarly needs an explicitly configured host and development-only domain exception. Hosted services must use HTTPS. The server itself does not terminate TLS.

On Android 17/API 37+, the `local` flavor declares `ACCESS_LOCAL_NETWORK` and provides an explicit permission action in Settings. Grant it before synchronizing with the development server. Permission is checked at the local transport boundary before HTTP; denial or later revocation preserves the local notebook and queued writes and reports that access is required. Granting permission does not start the server or validate the bearer token. The on-device demo has no local-network permission requirement. See the platform's [local-network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission).

Run its tests:

```text
python -m unittest discover -s server -v
```

## Contract

All endpoints require `Authorization: Bearer <token>`. Responses use JSON and `Cache-Control: no-store`. Writes require `Content-Type: application/json` and a single `Content-Length`; chunked request bodies are not supported. The maximum body is 65,536 bytes. Requests have a 10-second socket timeout. Unknown fields are rejected to make contract drift visible in this study project.

This small HTTP/1.0 server uses one request per connection and sends explicit `Connection: close` plus `Content-Length` on every response. This prevents a pooled client from reusing an already closed socket when automatic HTTP retries are disabled; durable write replay remains the repository's responsibility.

`GET /v1/notes` returns a consistent complete snapshot. The study server admits up to 10,000 note IDs, including retained deletion records. At capacity, edits, deletes and exact replays for existing IDs still work. The server rejects a new ID before writing it.

An older database already above this limit requires a data migration or a paginated client/server protocol before it can sync with the bounded snapshot client.

```json
{
  "projects": [
    {"id": "field", "name": "Field observations"},
    {"id": "study", "name": "Android study"}
  ],
  "notes": [
    {
      "id": "note-1", "projectId": "field", "title": "River survey",
      "body": "Two herons", "updatedAt": 1700000000000,
      "version": 1, "deleted": false
    }
  ]
}
```

`PUT /v1/notes/{id}` creates or changes a note. IDs and operation IDs contain 1–100 ASCII letters, numbers, underscores, or hyphens. A new note uses `baseVersion: 0`. Every distinct local mutation needs a new globally unique `operationId`; retries preserve the operation ID **and exact logical payload**.

```json
{
  "operationId": "a-distinct-uuid", "baseVersion": 0,
  "projectId": "field", "title": "River survey", "body": "Two herons",
  "updatedAt": 1700000000000, "deleted": false
}
```

`updatedAt` is client epoch milliseconds for presentation, not a conflict-resolution clock. Versions are server-issued monotonic integers per note. The server accepts a write only when `baseVersion` equals the stored version, then increments it by one. Title must be nonblank and at most 120 UTF-16 code units; body is at most 10,000 UTF-16 code units. These match Kotlin `String.length`: an emoji outside the basic multilingual plane counts as two units. Unpaired surrogates are rejected. `projectId` must reference an existing project. Integer fields must be nonnegative and below signed 64-bit maximum; booleans are not integers.

| Status | Meaning and client action |
| --- | --- |
| `200` | Body is the canonical note. Record the acknowledgment transactionally; do not overwrite a newer local edit. |
| `400` | Invalid fields, unknown project, invalid ID, reused operation ID with a different payload, or a new ID beyond capacity. Fix the request or resolve the capacity condition before retrying. |
| `401` | Missing or invalid token. Keep pending work, ask the user to sign in, then schedule sync again. |
| `404` | Unknown endpoint, or a nonzero base version for a missing note. The error code indicates whether the server could attribute the rejection to a specific mutation. |
| `409` | Body is `{"current": <canonical note>}`. Preserve the local change and present a conflict choice. A chosen local version is a new operation based on the returned current version. |
| `411` / `413` / `415` | Missing length, empty/oversized body, or wrong media type. Fix the request. |
| `503` | Temporary SQLite failure. Retain pending data and retry with backoff. |

For a definitive mutation rejection, the server returns `400` or `404` with `{"error":"…","code":"mutation_rejected","operationId":"…"}`. Android pauses a note only if the `operationId` matches its queued write, so other notes and snapshot downloads can continue. Authentication, routing, request framing and transient failures omit this marker because they do not establish the outcome of the logical operation. A client preserves the exact queued operation if the response is ambiguous, including an unreadable acknowledgment.

A successful operation and its serialized acknowledgment are stored in the **same SQLite transaction** as the note. Repeating the same operation returns that original acknowledgment even if another edit has since advanced the note. This handles a connection lost after the server commits but before the client receives the response. The client must replay an uncertain write before rebasing later edits. A different payload with the same operation ID fails rather than silently duplicating or changing a write. Conflicts are not recorded as successful operations.

Deletion is an ordinary mutation with `deleted: true`. Tombstones stay in snapshots so another client cannot resurrect a deleted note just because it was offline. Hard deletion and retention policy are deliberately absent from this small lab. SQLite `BEGIN IMMEDIATE` makes version checks and writes atomic across concurrent requests; `WAL` permits snapshot reads while a writer commits. Parameterized SQL and foreign keys protect the relational boundary.

## Production extension exercise

Before offering a hosted service: add per-user isolation and authorization, an external identity provider with short-lived tokens, HTTPS termination, rate limits, bounded worker concurrency, metrics without note content, backups and recovery tests, schema migrations, deletion/retention policy, and cursor-based incremental pagination. The full snapshot and indefinitely retained operation history here make the correctness model easy to inspect, but do not scale to a substantial user base. The app's production runbook explains release and monitoring work separately from these executable lab features.
