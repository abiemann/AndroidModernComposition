"""HTTP contract tests run against real sockets and an isolated SQLite database."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest

from fieldnotes_server import FieldNotesServer, Store

TOKEN = "test-only-token-do-not-use-for-deployment"


def mutation(operation="op-1", version=0, **changes):
    result = dict(operationId=operation, baseVersion=version, projectId="field",
                  title="River survey", body="Two herons", updatedAt=1_700_000_000_000, deleted=False)
    result.update(changes)
    return result


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "notes.db"
        self.server = FieldNotesServer(("127.0.0.1", 0), Store(self.path), TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def request(self, method="GET", path="/v1/notes", data=None, token=TOKEN, raw=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        request_headers = {"Content-Type": "application/json"}
        if token is not None:
            request_headers["Authorization"] = "Bearer " + token
        request_headers.update(headers or {})
        body = raw if raw is not None else json.dumps(data).encode() if data is not None else None
        try:
            connection.request(method, path, body, request_headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def put(self, data, note_id="note-1"):
        return self.request("PUT", "/v1/notes/" + note_id, data)

    def test_empty_snapshot_has_stable_projects(self):
        status, result = self.request()
        self.assertEqual(200, status)
        self.assertEqual(["field", "study"], [project["id"] for project in result["projects"]])
        self.assertEqual([], result["notes"])

    def test_responses_explicitly_close_connections_for_connection_pools(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
        try:
            for method, path, body in (
                ("GET", "/v1/notes", None),
                ("PUT", "/v1/notes/note-1", json.dumps(mutation()).encode()),
                ("GET", "/v1/notes", None),
            ):
                connection.request(method, path, body, headers)
                response = connection.getresponse()
                self.assertEqual(200, response.status)
                self.assertEqual("close", response.getheader("Connection"))
                self.assertTrue(response.will_close)
                payload = response.read()
                self.assertEqual(len(payload), int(response.getheader("Content-Length")))
                json.loads(payload)
        finally:
            connection.close()

    def test_missing_and_invalid_auth_cannot_read_or_write(self):
        for token in (None, "incorrect-token"):
            self.assertEqual(401, self.request(token=token)[0])
            self.assertEqual(401, self.request("PUT", "/v1/notes/n", mutation(), token=token)[0])
        self.assertEqual([], self.request()[1]["notes"])

    def test_create_edit_delete_and_tombstone_snapshot(self):
        status, created = self.put(mutation())
        self.assertEqual(200, status)
        self.assertEqual(1, created["version"])
        status, edited = self.put(mutation("op-2", 1, title="Evening survey"))
        self.assertEqual((200, 2, "Evening survey"), (status, edited["version"], edited["title"]))
        status, deleted = self.put(mutation("op-3", 2, title="Evening survey", deleted=True))
        self.assertEqual(200, status)
        self.assertTrue(deleted["deleted"])
        self.assertEqual([deleted], self.request()[1]["notes"])

    def test_retry_after_lost_response_is_idempotent(self):
        first = self.put(mutation())
        self.put(mutation("op-2", 1, title="Later edit"))
        replay = self.put(mutation())
        self.assertEqual(first, replay)
        self.assertEqual(2, self.request()[1]["notes"][0]["version"])

    def test_idempotency_survives_store_reopen(self):
        first = self.put(mutation())
        reopened = Store(self.path)
        self.assertEqual(first, reopened.mutate("note-1", mutation()))
        self.assertEqual(1, reopened.snapshot()["notes"][0]["version"])

    def test_mutation_id_cannot_change_payload_or_note(self):
        self.put(mutation())
        self.assertEqual(400, self.put(mutation(title="Altered"))[0])
        self.assertEqual(400, self.put(mutation(), "note-2")[0])

    def test_stale_write_returns_current_record_without_overwrite(self):
        _, original = self.put(mutation())
        status, conflict = self.put(mutation("op-2", 0, title="Offline edit"))
        self.assertEqual(409, status)
        self.assertEqual({"current": original}, conflict)
        self.assertEqual([original], self.request()[1]["notes"])

    def test_conflict_can_be_explicitly_resolved_using_new_operation(self):
        self.put(mutation())
        self.assertEqual(409, self.put(mutation("stale", 0))[0])
        status, result = self.put(mutation("resolved", 1, title="Chosen local edit"))
        self.assertEqual((200, 2, "Chosen local edit"), (status, result["version"], result["title"]))

    def test_concurrent_same_base_writes_have_exactly_one_winner(self):
        self.put(mutation())
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(self.put, [mutation("a", 1, title="A"), mutation("b", 1, title="B")]))
        self.assertEqual([200, 409], sorted(status for status, _ in results))
        self.assertEqual(2, self.request()[1]["notes"][0]["version"])

    def test_concurrent_same_operation_returns_same_result(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(self.put, [mutation(), mutation()]))
        self.assertEqual(results[0], results[1])
        self.assertEqual(1, self.request()[1]["notes"][0]["version"])

    def test_missing_note_with_nonzero_base_is_not_recreated(self):
        self.assertEqual(404, self.put(mutation(version=5))[0])
        self.assertEqual([], self.request()[1]["notes"])

    def test_invalid_schema_and_limits_fail_without_mutation(self):
        invalid = [
            [], {}, mutation(extra="value"), mutation(title=" "),
            mutation(title="a" * 121), mutation(body="b" * 10_001),
            mutation(projectId="absent"), mutation(baseVersion=True),
            mutation(baseVersion=-1), mutation(baseVersion=2**63),
            mutation(updatedAt=-1), mutation(deleted="false"),
            mutation(operationId="../escape"), mutation(title="\ud800"),
        ]
        for value in invalid:
            with self.subTest(value=str(value)[:80]):
                self.assertEqual(400, self.put(value)[0])
        self.assertEqual([], self.request()[1]["notes"])

    def test_malformed_json_is_rejected(self):
        for body in (b"{", b"\xff", b'{"value": NaN}'):
            self.assertEqual(400, self.request("PUT", "/v1/notes/n", raw=body)[0])

    def test_unicode_limits_match_android_utf16_code_units(self):
        self.assertEqual(200, self.put(mutation(title="🌿" * 60, body="🌿" * 5_000))[0])
        self.assertEqual(400, self.put(mutation("too-long-title", title="🌿" * 61), "note-2")[0])
        self.assertEqual(400, self.put(mutation("too-long-body", body="🌿" * 5_001), "note-2")[0])
        self.assertEqual(400, self.put(mutation("padded-title", title=" " * 120 + "x"), "note-2")[0])

    def test_oversize_body_and_wrong_media_type_are_rejected(self):
        # Reject from headers before allocating or reading an oversized payload.
        self.assertEqual(413, self.request("PUT", "/v1/notes/n", raw=b"",
                                         headers={"Content-Length": "65537"})[0])
        self.assertEqual(415, self.request("PUT", "/v1/notes/n", mutation(),
                                         headers={"Content-Type": "text/plain"})[0])

    def test_paths_are_strict_and_cannot_be_injected(self):
        self.assertEqual(404, self.request(path="/v1/notes?unexpected=1")[0])
        self.assertEqual(400, self.put(mutation(), "bad%2Fid")[0])
        self.assertEqual(400, self.put(mutation(), "x';DROP%20TABLE%20notes;--")[0])
        self.assertEqual(200, self.request()[0])

    def test_server_refuses_empty_or_weak_configuration_token(self):
        for token in ("", "short", "contains whitespace " * 3, "🌿" * 24, "x" * 4097):
            with self.assertRaises(ValueError):
                FieldNotesServer(("127.0.0.1", 0), Store(self.path), token)


if __name__ == "__main__":
    unittest.main()
