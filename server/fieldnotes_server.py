"""Local teaching server for the Field Notes Android application.

No third-party dependencies. This is a single-account lab server, not a hosted
identity provider. See docs/API.md for the protocol and production boundaries.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import re
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import BoundedSemaphore
from typing import Iterator

MAX_BODY_BYTES = 65_536
MAX_NOTES = 10_000  # Includes retained tombstones; matches Android's snapshot limit.
MAX_INTEGER = 2**63 - 1
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
MUTATION_FIELDS = {
    "operationId", "baseVersion", "projectId", "title", "body", "updatedAt", "deleted"
}


class ProtocolError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


class MutationRejected(ProtocolError):
    """This attempted mutation was rejected before any change was committed."""

    def __init__(self, status: int, message: str, operation_id: str):
        super().__init__(status, message)
        self.operation_id = operation_id


def encode_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def reject_nonfinite_json(_value: str) -> None:
    raise ValueError("Non-finite JSON numbers are unsupported")


def validate_mutation(value: object) -> dict:
    if not isinstance(value, dict) or set(value) != MUTATION_FIELDS:
        raise ProtocolError(400, "Expected the documented mutation fields")
    for name in ("operationId", "projectId"):
        if not isinstance(value[name], str) or not IDENTIFIER.fullmatch(value[name]):
            raise ProtocolError(400, f"Invalid {name}")
    for name in ("baseVersion", "updatedAt"):
        # bool subclasses int in Python; accepting true as a version is a bug.
        if type(value[name]) is not int or not 0 <= value[name] < MAX_INTEGER:
            raise ProtocolError(400, f"Invalid {name}")
    if type(value["deleted"]) is not bool:
        raise ProtocolError(400, "deleted must be a boolean")
    if not isinstance(value["title"], str) or not value["title"].strip():
        raise ProtocolError(400, "title must be nonblank")
    if not isinstance(value["body"], str):
        raise ProtocolError(400, "body must be a string")
    # Refuse lone surrogate code points rather than failing during UTF-8 output.
    try:
        encode_json(value)
    except (UnicodeError, ValueError) as exc:
        raise ProtocolError(400, "Invalid Unicode or JSON value") from exc
    # Match Kotlin String.length exactly, including surrogate pairs for emoji.
    # Otherwise a different client could create a server record Android rejects.
    if len(value["title"].encode("utf-16-le")) // 2 > 120:
        raise ProtocolError(400, "title must contain at most 120 UTF-16 code units")
    if len(value["body"].encode("utf-16-le")) // 2 > 10_000:
        raise ProtocolError(400, "body must contain at most 10000 UTF-16 code units")
    return value


class Store:
    """One short SQLite transaction per request; versions and replay are atomic."""

    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS notes (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL REFERENCES projects(id),
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0),
                    deleted INTEGER NOT NULL CHECK(deleted IN (0, 1))
                );
                CREATE INDEX IF NOT EXISTS notes_project ON notes(project_id);
                CREATE TABLE IF NOT EXISTS operations (
                    operation_id TEXT PRIMARY KEY,
                    request_hash TEXT NOT NULL,
                    response_json TEXT NOT NULL
                );
                INSERT OR IGNORE INTO projects VALUES ('field', 'Field observations');
                INSERT OR IGNORE INTO projects VALUES ('study', 'Android study');
                PRAGMA user_version=1;
            """)

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA foreign_keys=ON")
            with connection:
                yield connection
        finally:
            connection.close()

    @staticmethod
    def note(row: sqlite3.Row) -> dict:
        return {
            "id": row["id"], "projectId": row["project_id"],
            "title": row["title"], "body": row["body"],
            "updatedAt": row["updated_at"], "version": row["version"],
            "deleted": bool(row["deleted"]),
        }

    def snapshot(self) -> dict:
        with self.connect() as connection:
            # A read transaction gives both collections a consistent snapshot.
            connection.execute("BEGIN")
            projects = [dict(row) for row in connection.execute("SELECT id, name FROM projects ORDER BY id")]
            notes = [self.note(row) for row in connection.execute("SELECT * FROM notes ORDER BY id")]
            return {"projects": projects, "notes": notes}

    def mutate(self, note_id: str, raw: object) -> tuple[int, dict]:
        if not IDENTIFIER.fullmatch(note_id):
            raise ProtocolError(400, "Invalid note identifier")
        operation_id = raw.get("operationId") if isinstance(raw, dict) else None
        if not isinstance(operation_id, str) or not IDENTIFIER.fullmatch(operation_id):
            raise ProtocolError(400, "Invalid operationId")
        try:
            canonical = json.dumps([note_id, raw], sort_keys=True, ensure_ascii=False,
                                   separators=(",", ":"), allow_nan=False).encode("utf-8")
        except (UnicodeError, ValueError, RecursionError) as exc:
            raise MutationRejected(400, "Invalid Unicode or JSON value", operation_id) from exc
        request_hash = hashlib.sha256(canonical).hexdigest()
        with self.connect() as connection:
            # Serialize compare-and-write. Two clients with the same base version
            # must never both succeed. SQLite's context manager commits on return.
            connection.execute("BEGIN IMMEDIATE")
            replay = connection.execute(
                "SELECT request_hash, response_json FROM operations WHERE operation_id=?",
                (operation_id,),
            ).fetchone()
            if replay:
                if not hmac.compare_digest(replay["request_hash"], request_hash):
                    raise MutationRejected(400, "operationId was already used for a different request", operation_id)
                return 200, json.loads(replay["response_json"])
            # Honor an exact committed replay before applying current validation
            # or admission policy. A lost acknowledgment must stay recoverable.
            try:
                mutation = validate_mutation(raw)
            except ProtocolError as exc:
                raise MutationRejected(exc.status, exc.message, operation_id) from exc
            project = connection.execute("SELECT 1 FROM projects WHERE id=?", (mutation["projectId"],)).fetchone()
            if project is None:
                raise MutationRejected(400, "Unknown projectId", operation_id)
            current = connection.execute("SELECT * FROM notes WHERE id=?", (note_id,)).fetchone()
            current_version = current["version"] if current else 0
            if mutation["baseVersion"] != current_version:
                if current is None:
                    raise MutationRejected(404, "Note does not exist at the requested baseVersion", operation_id)
                return 409, {"current": self.note(current)}
            # The cap is checked only for a new ID and inside the same write
            # transaction. Replays, edits, and tombstones remain usable at capacity.
            if current is None and connection.execute("SELECT COUNT(*) FROM notes").fetchone()[0] >= MAX_NOTES:
                raise MutationRejected(400, "Notebook capacity reached, including retained deletions", operation_id)
            result = {
                "id": note_id,
                "projectId": mutation["projectId"],
                "title": mutation["title"],
                "body": mutation["body"],
                "updatedAt": mutation["updatedAt"],
                "version": current_version + 1,
                "deleted": mutation["deleted"],
            }
            connection.execute("""
                INSERT INTO notes VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    project_id=excluded.project_id, title=excluded.title, body=excluded.body,
                    updated_at=excluded.updated_at, version=excluded.version, deleted=excluded.deleted
            """, (note_id, result["projectId"], result["title"], result["body"],
                  result["updatedAt"], result["version"], int(result["deleted"])))
            connection.execute("INSERT INTO operations VALUES (?, ?, ?)",
                               (mutation["operationId"], request_hash, encode_json(result).decode("utf-8")))
            return 200, result


class FieldNotesServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], store: Store, token: str, *, max_active_requests: int = 16):
        if not 24 <= len(token) <= 4096 or not all(33 <= ord(character) <= 126 for character in token):
            raise ValueError("FIELDNOTES_TOKEN must contain 24 to 4096 printable ASCII characters without spaces")
        self.store = store
        self.token = token.encode("utf-8")
        if max_active_requests < 1:
            raise ValueError("max_active_requests must be positive")
        self._request_slots = BoundedSemaphore(max_active_requests)
        super().__init__(address, Handler)

    def process_request(self, request, client_address) -> None:
        # Reject excess connections before creating a thread or reading a body.
        # Closure has an unknown outcome to clients, so they retain replay IDs.
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


class Handler(BaseHTTPRequestHandler):
    server: FieldNotesServer
    server_version = "FieldNotesLab"
    sys_version = ""
    timeout = 10

    def log_message(self, _format: str, *args: object) -> None:
        # Request URLs, authorization values and note contents never enter logs.
        pass

    def respond(self, status: int, value: object) -> None:
        body = encode_json(value)
        # This HTTP/1.0 lab server handles one request per connection. Advertise
        # closure explicitly so clients such as OkHttp never pool a half-closed
        # socket when automatic connection retries are deliberately disabled.
        self.close_connection = True
        self.send_response(status)
        self.send_header("Connection", "close")
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if status == 401:
            self.send_header("WWW-Authenticate", 'Bearer realm="Field Notes lab"')
        self.end_headers()
        self.wfile.write(body)

    def authenticate(self) -> None:
        authorization = self.headers.get("Authorization", "")
        prefix = "Bearer "
        candidate = authorization[len(prefix):].encode("utf-8") if authorization.startswith(prefix) else b""
        if not hmac.compare_digest(candidate, self.server.token):
            raise ProtocolError(401, "Authentication required")

    def read_json(self) -> object:
        if self.headers.get("Transfer-Encoding") is not None:
            raise ProtocolError(400, "Transfer-Encoding is unsupported; send Content-Length")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1:
            raise ProtocolError(411, "A single Content-Length is required")
        try:
            size = int(lengths[0])
        except ValueError as exc:
            raise ProtocolError(400, "Invalid Content-Length") from exc
        if not 0 < size <= MAX_BODY_BYTES:
            raise ProtocolError(413, "Request body is empty or too large")
        if self.headers.get_content_type() != "application/json":
            raise ProtocolError(415, "Expected application/json")
        self.body_read = True
        data = self.rfile.read(size)
        if len(data) != size:
            raise ProtocolError(400, "Incomplete request body")
        try:
            return json.loads(data.decode("utf-8"), parse_constant=reject_nonfinite_json)
        except (UnicodeError, ValueError, RecursionError) as exc:
            raise ProtocolError(400, "Malformed JSON") from exc

    def discard_unread_body(self) -> None:
        # Closing a socket with a small unread request body can reset the
        # connection before the client receives a 401/415 response on Windows.
        # Drain only a bounded, explicitly sized body; never parse it or wait
        # indefinitely for an unauthenticated client.
        if self.body_read or self.headers.get("Transfer-Encoding") is not None:
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if 0 < size <= MAX_BODY_BYTES:
                self.connection.settimeout(1)
                self.rfile.read(size)
        except (ValueError, OSError):
            pass

    def dispatch(self) -> None:
        self.body_read = False
        try:
            self.authenticate()
            if self.command == "GET" and self.path == "/v1/notes":
                self.respond(200, self.server.store.snapshot())
            elif self.command == "PUT" and self.path.startswith("/v1/notes/"):
                note_id = self.path[len("/v1/notes/"):]
                # Framing/JSON errors cannot identify a complete logical retry;
                # an earlier attempt could already have committed.
                status, body = self.server.store.mutate(note_id, self.read_json())
                self.respond(status, body)
            else:
                self.respond(404, {"error": "Unknown endpoint"})
        except ProtocolError as exc:
            self.discard_unread_body()
            body = {"error": exc.message}
            if isinstance(exc, MutationRejected):
                body["code"] = "mutation_rejected"
                body["operationId"] = exc.operation_id
            self.respond(exc.status, body)
        except sqlite3.Error:
            self.respond(503, {"error": "Storage temporarily unavailable"})

    do_GET = dispatch
    do_PUT = dispatch


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Bind address; local machine by default")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--database", type=Path, default=Path("server/fieldnotes.sqlite3"))
    arguments = parser.parse_args()
    token = os.environ.get("FIELDNOTES_TOKEN", "")
    try:
        server = FieldNotesServer((arguments.host, arguments.port), Store(arguments.database), token)
    except ValueError as exc:
        parser.error(str(exc))
    print(f"Field Notes lab listening on http://{arguments.host}:{server.server_port}; Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
