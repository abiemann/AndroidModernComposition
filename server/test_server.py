"""HTTP contract tests run against real sockets and an isolated SQLite database."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from pathlib import Path
import socket
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from fieldnotes_server import FieldNotesServer, ProtocolError, Store

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
        self.server = FieldNotesServer(("127.0.0.1", 0), Store(self.path), TOKEN, max_active_requests=2)
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

    def assert_mutation_rejected(self, response, expected_status=400, operation_id="op-1"):
        status, body = response
        self.assertEqual(expected_status, status)
        self.assertEqual("mutation_rejected", body.get("code"))
        self.assertEqual(operation_id, body.get("operationId"))
        self.assertIsInstance(body.get("error"), str)

    def test_empty_snapshot_has_stable_projects(self):
        status, result = self.request()
        self.assertEqual(200, status)
        self.assertEqual(["field", "study"], [project["id"] for project in result["projects"]])
        self.assertEqual([], result["notes"])

    def test_active_request_limit_closes_excess_connections_and_recovers(self):
        entered = threading.Barrier(3)
        release = threading.Event()
        calls = []
        original_snapshot = self.server.store.snapshot

        def held_snapshot():
            calls.append(True)
            entered.wait(timeout=3)
            if not release.wait(timeout=3):
                raise TimeoutError("test did not release held snapshot")
            return original_snapshot()

        with ThreadPoolExecutor(max_workers=2) as clients:
            with patch.object(self.server.store, "snapshot", side_effect=held_snapshot):
                active = [clients.submit(self.request) for _ in range(2)]
                try:
                    entered.wait(timeout=3)
                    with self.assertRaises((http.client.RemoteDisconnected, ConnectionResetError, ConnectionAbortedError)):
                        self.request()
                    self.assertEqual(2, len(calls))
                finally:
                    release.set()
                self.assertEqual([200, 200], [future.result()[0] for future in active])
        # Response delivery can precede worker-thread cleanup by a scheduling turn.
        deadline = time.monotonic() + 3
        while True:
            try:
                self.assertEqual(200, self.request()[0])
                break
            except (http.client.RemoteDisconnected, ConnectionResetError, ConnectionAbortedError):
                if time.monotonic() >= deadline:
                    self.fail("Request slots were not released after completed requests")
                time.sleep(0.01)

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
            for response in (self.request(token=token),
                             self.request("PUT", "/v1/notes/n", mutation(), token=token)):
                self.assertEqual(401, response[0])
                self.assertNotIn("code", response[1])
        self.assertEqual([], self.request()[1]["notes"])

    def test_general_and_transient_failures_are_not_mutation_rejections(self):
        for response in (self.request(path="/unknown"), self.request("PUT", "/unknown")):
            self.assertEqual(404, response[0])
            self.assertNotIn("code", response[1])
        with patch.object(self.server.store, "mutate", side_effect=sqlite3.OperationalError("test storage failure")):
            status, body = self.put(mutation())
        self.assertEqual(503, status)
        self.assertNotIn("code", body)
        self.assertEqual([], self.request()[1]["notes"])
        self.assertEqual(200, self.put(mutation())[0])

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

    def test_committed_replay_precedes_current_semantic_validation(self):
        first = self.put(mutation())
        # Tightened validation must not erase the acknowledgment of an earlier write.
        with patch("fieldnotes_server.validate_mutation", side_effect=ProtocolError(400, "Changed policy")):
            self.assertEqual(first, self.put(mutation()))

    def test_truncated_retry_does_not_claim_the_original_mutation_was_rejected(self):
        first = self.put(mutation())
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        body = json.dumps(mutation()).encode()
        try:
            connection.request("PUT", "/v1/notes/note-1", body=body[:-5], headers={
                "Authorization": "Bearer " + TOKEN,
                "Content-Type": "application/json",
                "Content-Length": str(len(body)),
            })
            connection.sock.shutdown(socket.SHUT_WR)
            response = connection.getresponse()
            result = json.loads(response.read())
            self.assertEqual(400, response.status)
            self.assertEqual("Incomplete request body", result["error"])
            self.assertNotIn("code", result)
            self.assertNotIn("operationId", result)
        finally:
            connection.close()
        self.assertEqual(first, self.put(mutation()))
        self.assertEqual([first[1]], self.request()[1]["notes"])

    def test_idempotency_survives_store_reopen(self):
        first = self.put(mutation())
        reopened = Store(self.path)
        self.assertEqual(first, reopened.mutate("note-1", mutation()))
        self.assertEqual(1, reopened.snapshot()["notes"][0]["version"])

    def test_mutation_id_cannot_change_payload_or_note(self):
        accepted = self.put(mutation())
        self.assert_mutation_rejected(self.put(mutation(title="Altered")))
        self.assert_mutation_rejected(self.put(mutation(), "note-2"))
        # The attempted different payload is rejected; the original acknowledgment survives.
        self.assertEqual(accepted, self.put(mutation()))
        self.assertEqual([accepted[1]], self.request()[1]["notes"])

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
        self.assert_mutation_rejected(self.put(mutation(version=5)), 404)
        self.assertEqual([], self.request()[1]["notes"])

    @patch("fieldnotes_server.MAX_NOTES", 2)
    def test_capacity_counts_tombstones_but_preserves_updates_and_replays(self):
        first = self.put(mutation("first"), "one")
        self.assertEqual(200, first[0])
        self.assertEqual(200, self.put(mutation("second"), "two")[0])
        self.assert_mutation_rejected(self.put(mutation("third"), "three"), operation_id="third")
        with self.server.store.connect() as connection:
            self.assertEqual(2, connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

        self.assertEqual(200, self.put(mutation("edit-one", 1, title="Edited"), "one")[0])
        status, tombstone = self.put(mutation("delete-two", 1, deleted=True), "two")
        self.assertEqual(200, status)
        self.assertTrue(tombstone["deleted"])
        self.assertEqual(first, self.put(mutation("first"), "one"))
        self.assert_mutation_rejected(self.put(mutation("third"), "three"), operation_id="third")
        # A rejected creation does not obstruct an unrelated valid operation.
        self.assertEqual(200, self.put(mutation("later-edit", 2, title="Later edit"), "one")[0])
        notes = self.request()[1]["notes"]
        self.assertEqual(["one", "two"], [note["id"] for note in notes])
        self.assertEqual(3, notes[0]["version"])
        self.assertTrue(notes[1]["deleted"])

    @patch("fieldnotes_server.MAX_NOTES", 1)
    def test_concurrent_creations_cannot_exceed_capacity(self):
        ready = threading.Barrier(2)

        def create(note_id):
            ready.wait(timeout=3)
            return self.put(mutation(note_id), note_id)

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(create, ("one", "two")))
        self.assertEqual([200, 400], sorted(status for status, _ in results))
        rejected = next(result for result in results if result[0] == 400)
        rejected_id = rejected[1]["operationId"]
        self.assertIn(rejected_id, ("one", "two"))
        self.assert_mutation_rejected(rejected, operation_id=rejected_id)
        self.assertEqual(1, len(self.request()[1]["notes"]))

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
                response = self.put(value)
                self.assertEqual(400, response[0])
                if isinstance(value, dict) and value.get("operationId") == "op-1":
                    self.assert_mutation_rejected(response)
                else:
                    self.assertNotIn("code", response[1])
                    self.assertNotIn("operationId", response[1])
        self.assertEqual([], self.request()[1]["notes"])

    def test_malformed_json_is_rejected(self):
        for body in (b"{", b"\xff", b'{"value": NaN}'):
            status, result = self.request("PUT", "/v1/notes/n", raw=body)
            self.assertEqual(400, status)
            self.assertNotIn("code", result)
            self.assertNotIn("operationId", result)

    def test_unicode_limits_match_android_utf16_code_units(self):
        self.assertEqual(200, self.put(mutation(title="🌿" * 60, body="🌿" * 5_000))[0])
        self.assertEqual(400, self.put(mutation("too-long-title", title="🌿" * 61), "note-2")[0])
        self.assertEqual(400, self.put(mutation("too-long-body", body="🌿" * 5_001), "note-2")[0])
        self.assertEqual(400, self.put(mutation("padded-title", title=" " * 120 + "x"), "note-2")[0])

    def test_oversize_body_and_wrong_media_type_are_rejected(self):
        # Reject from headers before allocating or reading an oversized payload.
        oversized = self.request("PUT", "/v1/notes/n", raw=b"", headers={"Content-Length": "65537"})
        wrong_media = self.request("PUT", "/v1/notes/n", mutation(), headers={"Content-Type": "text/plain"})
        for response, expected_status in ((oversized, 413), (wrong_media, 415)):
            self.assertEqual(expected_status, response[0])
            self.assertNotIn("code", response[1])
            self.assertNotIn("operationId", response[1])

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
