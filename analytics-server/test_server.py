from base64 import urlsafe_b64decode
from contextlib import closing
from datetime import date, datetime, time, timedelta
from hashlib import sha256
from http.client import HTTPConnection
from io import BytesIO
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, quote, urlsplit

import aw_calendar
from server import BadRequest, STREAMS, Server, Store, UsbError, _daemon_version, period_windows


IMPORT = "/v1/usb"
START = IMPORT + "/start"
COMPLETE = b'{"complete":true}\n'
OMITTED = object()
PHONE_DAY = {"workouts": 1, "topics": 2, "tasks": 3, "reviews": 4, "pages": 5, "showerDurationSec": 60, "steps": None}


def phone_calendar(days, *, source="abc", complete=True):
    return json.dumps({"source": source, "days": days, "complete": complete}).encode()


def pc_snapshot(days):
    return days, {day: {group: [{"name": group + ".exe", "seconds": seconds}] if seconds else []
                        for group, seconds in groups.items()} for day, groups in days.items()}


def sample(record_id, day, value=OMITTED, **fields):
    record = {"id": record_id, "date": day, **fields}
    if value is not OMITTED:
        record["value"] = value
    return record


def stage(kind, start, end):
    return {"start": start, "end": end, "stage": kind}


class AnalyticsIntegrationTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.database = root / "private.sqlite"
        (root / "assets").mkdir()
        for name, content in {
            "index.html": "<h1>Private analytics</h1>", "assets/ui-test.css": "body {}",
            "assets/ui-test.js": "'use strict';",
        }.items():
            (root / name).write_text(content, encoding="utf-8")
        self.server = Server(self.database, root, port=0)
        self.batches = {}
        self.addCleanup(self.server.server_close)
        self.worker = Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.worker.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.worker.join()

    def request(self, method, path, *, body=None, headers=None):
        headers = dict(headers or {})
        with closing(HTTPConnection(*self.server.server_address, timeout=5)) as client:
            client.request(method, path, body=body, headers=headers)
            with client.getresponse() as response:
                return response.status, {key.lower(): value for key, value in response.getheaders()}, response.read()

    def snapshot(self, batches, *, source="abc", trailer=COMPLETE):
        lines = [{"version": 1, "source": source}, *batches.values()]
        body = b"".join(json.dumps(line).encode() + b"\n" for line in lines)
        return self.server.store.import_snapshot(BytesIO(body + trailer))

    def save_batch(self, stream, month, records):
        key = stream, month
        batches = dict(self.batches)
        if records:
            batches[key] = {"stream": stream, "month": month, "records": records}
        else:
            batches.pop(key, None)
        self.snapshot(batches)
        self.batches = batches

    def get(self, path, *, expected_status=200):
        status, headers, body = self.request("GET", path)
        result = json.loads(body)
        self.assertEqual(status, expected_status, result)
        self.assertEqual(headers["cache-control"], "no-store")
        return result

    def test_local_dashboard_checks_host_and_serves_no_private_files(self):
        for path in ("/", "/index.html", "/assets/ui-test.css", "/assets/ui-test.js", "/v1/overview", "/v1/series",
                     "/v1/sleep", "/v1/calendar?period=2026"):
            with self.subTest(path=path):
                self.assertEqual(self.request("GET", path, headers={"Host": "example.net"})[0], 403)
        for path, mime in (("/", "text/html"), ("/assets/ui-test.css", "text/css"), ("/assets/ui-test.js", "text/javascript")):
            status, headers, body = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertTrue(headers["content-type"].startswith(mime))
            self.assertGreater(len(body), 0)
            status, head, empty = self.request("HEAD", path)
            self.assertEqual((status, head["content-length"], empty), (200, str(len(body)), b""))
        for path in ("/private.sqlite", "/server.py", "/assets/../private.sqlite", "/assets/ui-test.js.map"):
            self.assertEqual(self.request("GET", path)[0], 404)

    def mock_usb(self, *versions):
        # Fake only the OS boundaries; HTTP guards, locking, validation and SQLite are real.
        def replace(name, **options):
            patcher = patch("server." + name, **options)
            self.addCleanup(patcher.stop)
            return patcher.start()

        self.usb_output = b'{"version":1,"source":"abc"}\n' + COMPLETE
        self.usb_stderr = b""
        self.usb_returncode = 0
        self.calendar_output = phone_calendar({})
        self.phone_pc = {}
        self.call_payloads = []
        self.bad_ack_call = None
        self.call_source = "abc"
        self.collect = replace("aw_calendar.collect_detailed", return_value=({}, {}))

        def run(command, **options):
            self.assertTrue(self.server.import_lock.locked())
            if command == ["adb", "version"]:
                return subprocess.CompletedProcess(command, 0, b"Android Debug Bridge version 1.0.41\r\nVersion 35.0.2\r\n")
            self.assertEqual(command[:6], ["adb", "-H", "127.0.0.1", "-P", "5037", "-d"])
            args = command[6:]
            provider = "content://ru.begunok.analytics"
            if args[:3] == ["exec-out", "content", "read"]:
                self.assertEqual(args[:4], ["exec-out", "content", "read", "--uri"])
                if args[4] == provider + "/snapshot":
                    options["stdout"].write(self.usb_output)
                    return subprocess.CompletedProcess(command, self.usb_returncode, stderr=self.usb_stderr)
                self.assertEqual(args[4], provider + "/calendar")
                options["stdout"].write(self.calendar_output)
                return subprocess.CompletedProcess(command, 0, stderr=b"")
            self.assertEqual(args[:-1], ["shell", "content", "call", "--uri", provider, "--method", "calendar-pc", "--arg"])
            self.assertLess(len(" ".join(command)), 4000)
            self.assertRegex(args[-1], r"^[A-Za-z0-9_-]+$")
            raw = urlsafe_b64decode(args[-1] + "===")
            payload = json.loads(raw)
            self.assertEqual(payload.keys(), {"source", "days"})
            self.call_payloads.append(payload)
            if payload["source"] != self.call_source:
                return subprocess.CompletedProcess(command, 0, b"Error: wrong device private-identifier")
            for day, groups in payload["days"].items():
                if groups:
                    self.phone_pc[day] = groups
                else:
                    self.phone_pc.pop(day, None)
            # A lost/mismatched acknowledgment can happen even when the phone committed.
            ack = sha256(b"wrong" if len(self.call_payloads) == self.bad_ack_call else raw).hexdigest()
            return subprocess.CompletedProcess(command, 0, f"Result: Bundle[{{ack={ack}}}]\r\n".encode())

        self.run_adb = replace("subprocess.run", side_effect=run)
        self.spawn = replace("subprocess.Popen")
        self.child = self.spawn.return_value
        self.child.poll.return_value = None
        self.probe = replace("_daemon_version", side_effect=versions)

    def usb_post(self, path, expected_status=200):
        status, _, body = self.request("POST", path, headers={"Origin": f"http://{self.server.authority}"})
        result = json.loads(body)
        self.assertEqual(status, expected_status, result)
        return result

    def test_only_dashboard_origin_can_trigger_bodyless_usb_operations(self):
        self.mock_usb()
        origin = f"http://{self.server.authority}"
        for path in (START, IMPORT):
            for headers in ({}, {"Origin": "https://example.net"}, {"Origin": "null"},
                            {"Host": "example.net", "Origin": origin}):
                self.assertEqual(self.request("POST", path, headers=headers)[0], 403)
            self.assertEqual(self.request("POST", path, body="{}", headers={"Origin": origin})[0], 400)
            self.assertEqual(self.request("POST", path, headers={"Origin": origin, "Transfer-Encoding": "chunked"})[0], 400)
            self.assertEqual(self.request("POST", path + "?x=1", headers={"Origin": origin})[0], 400)
            with self.server.import_lock:
                self.assertIn("уже выполняется", self.usb_post(path, 409)["error"])
        self.run_adb.assert_not_called()
        self.probe.assert_not_called()
        self.spawn.assert_not_called()
        self.assertEqual(self.request("POST", "/v1/sync/batches", body="{}", headers={"Origin": origin})[0], 404)

    def test_owned_daemon_stops_after_commit_and_import_can_start_it_again(self):
        self.mock_usb(None, 41, 41, 41, None, 41)
        self.usb_post(START)
        self.usb_post(START)
        self.assertEqual(self.spawn.call_args.args[0], ["adb", "-L", "tcp:localhost:5037", "server", "nodaemon"])
        self.assertEqual(self.spawn.call_count, 1)
        self.child.terminate.assert_not_called()
        self.usb_output = (b'{"version":1,"source":"abc"}\n'
                           b'{"stream":"pulse","month":"2026-11","records":[{"id":"one","date":"2026-11-05","value":80}]}\n'
                           + COMPLETE)

        def check_committed():
            self.assertTrue(self.server.import_lock.locked())
            with closing(sqlite3.connect(self.database)) as connection:
                self.assertEqual(connection.execute("SELECT id FROM records").fetchall(), [("one",)])
        self.child.terminate.side_effect = check_committed
        self.assertEqual(self.usb_post(IMPORT), {"count": 1})
        self.assertEqual(self.usb_post(IMPORT), {"count": 1})
        self.assertEqual(self.spawn.call_count, 2)
        self.assertEqual(self.child.terminate.call_count, 2)

    def test_preexisting_and_replacement_daemons_are_never_stopped(self):
        self.mock_usb(41, 41, None, 41, 41)
        self.usb_post(START)
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.spawn.assert_not_called()
        self.usb_post(START)
        self.child.poll.return_value = 0  # The local listener now belongs to a different process.
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.child.terminate.assert_not_called()
        self.assertEqual(self.spawn.call_count, 1)

    def test_start_error_is_safe_persisted_and_cleared_by_import_retry(self):
        self.mock_usb(None, 41)
        with patch("server.subprocess.run", side_effect=FileNotFoundError("private executable path")):
            error = self.usb_post(START, 409)["error"]
        self.assertIn("ADB не найден", error)
        self.assertNotIn("private", error)
        self.assertEqual(self.get(IMPORT), {"app": "MyLifeAnalyticsUSB", "error": error})
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.assertEqual(self.get(IMPORT), {"app": "MyLifeAnalyticsUSB"})

    def test_incompatible_external_daemon_is_left_untouched(self):
        self.mock_usb(40)
        self.assertIn("различаются", self.usb_post(IMPORT, 409)["error"])
        self.assertEqual(self.run_adb.call_count, 1)
        self.spawn.assert_not_called()

    def test_invalid_snapshot_rolls_back_without_cleanup_and_cleanup_failure_only_warns(self):
        self.mock_usb(None, 41, 41)
        self.usb_output = b'{"version":1,"source":"abc"}\n'
        self.assertIn("не полностью", self.usb_post(IMPORT, 409)["error"])
        self.child.terminate.assert_not_called()
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("SELECT * FROM usb_source").fetchall(), [])
        self.usb_output += COMPLETE
        self.child.wait.side_effect = subprocess.TimeoutExpired("adb", 5)
        result = self.usb_post(IMPORT)
        self.assertEqual(result["count"], 0)
        self.assertIn("Данные сохранены", result["warning"])
        self.assertEqual(self.spawn.call_count, 1)

    def test_usb_errors_explain_device_and_provider_failures_without_reflecting_output(self):
        cases = [(1, b"device unauthorized.", "подтвердите запрос"),
                 (1, b"device offline", "offline"),
                 (1, b"more than one device/emulator", "несколько"),
                 (1, b"no devices/emulators found", "не видит телефон"),
                 (1, b"cannot connect to daemon", "Соединение"),
                 (1, b"unrecognized adb failure", "код 1"),
                 (0, b"Error while accessing provider: java.lang.SecurityException", "запретил"),
                 (0, b"java.lang.IllegalStateException: Could not find provider", "не найден источник")]
        self.mock_usb(None, *([41] * len(cases)))
        for code, message, explanation in cases:
            with self.subTest(explanation=explanation):
                self.usb_returncode = code
                diagnostic = message + b" private-device-info"
                self.usb_stderr = diagnostic if code else b""
                self.usb_output = b"" if code else diagnostic
                error = self.usb_post(IMPORT, 409)["error"]
                self.assertIn(explanation, error)
                self.assertNotIn("private-device-info", error)
        self.child.terminate.assert_not_called()
        self.assertEqual(self.spawn.call_count, 1)

    def test_database_write_error_is_not_a_successful_import(self):
        self.mock_usb(None, 41)
        with patch.object(self.server.store, "connect", side_effect=sqlite3.OperationalError("private path")), \
                self.assertLogs("server", level="ERROR"):
            error = self.usb_post(IMPORT, 503)["error"]
        self.assertIn("База данных", error)
        self.assertNotIn("private path", error)
        self.child.terminate.assert_not_called()

    def test_calendar_period_reads_union_without_conflating_unknown_with_zero(self):
        self.mock_usb(41, 41)
        self.calendar_output = phone_calendar({"2024-02-29": PHONE_DAY, "2024-03-01": dict(PHONE_DAY, steps=0)})
        days, details = pc_snapshot({"2024-02-29": {"ai": 300}, "2024-12-31": {"other": 0}, "2025-01-01": {"study": 10}})
        self.collect.return_value = days, details
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.assertEqual(self.get("/v1/calendar?period=2024-02"), {"days": {
            "2024-02-29": {"phone": PHONE_DAY, "pc": {"ai": 300}, "details": details["2024-02-29"]},
        }})
        self.assertEqual(self.get("/v1/calendar?period=2024")["days"], {
            "2024-02-29": {"phone": PHONE_DAY, "pc": {"ai": 300}, "details": details["2024-02-29"]},
            "2024-03-01": {"phone": dict(PHONE_DAY, steps=0), "pc": None, "details": None},
            "2024-12-31": {"phone": None, "pc": {"other": 0}, "details": {"other": []}},
        })
        self.calendar_output = phone_calendar({})
        days, details = pc_snapshot({"2024-02-29": {"ai": 200}})
        self.collect.return_value = days, details
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.assertEqual(self.get("/v1/calendar?period=2024")["days"], {
            "2024-02-29": {"phone": None, "pc": {"ai": 200}, "details": details["2024-02-29"]}})
        self.assertEqual(self.phone_pc, days)
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("SELECT payload, details FROM calendar_pc WHERE date = '2024-12-31'")
                             .fetchone(), (None, None))
        self.assertEqual(self.call_payloads[-1]["days"]["2025-01-01"], {})  # remote deletion
        for query in ("", "period=", "period=0000", "period=2024-0", "period=2024-00", "period=2024-13",
                      "period=2024-02-29", "period=２０２４", "period=2024&period=2025", "period=2024&end=2024-01-01"):
            with self.subTest(query=query):
                self.get("/v1/calendar?" + quote(query, safe="=&-"), expected_status=400)

    def test_workout_kinds_replay_and_stretch_removal(self):
        self.mock_usb(41, 41, 41)
        day, stretch_only, legacy = "2026-03-04", "2026-03-02", "2026-02-01"
        self.collect.return_value = pc_snapshot({stretch_only: {"study": 90}})
        kinds = {"run": 2, "strength_upper": 1, "strength_lower": 1, "strength": 1, "stretching": 1, "other": 1}
        combined = dict(PHONE_DAY, workouts=sum(kinds.values()), workoutKinds=kinds)
        self.calendar_output = phone_calendar({
            day: combined, stretch_only: dict(PHONE_DAY, workoutKinds={"stretching": 1}), legacy: PHONE_DAY,
        })
        for _ in range(2):
            self.assertEqual(self.usb_post(IMPORT), {"count": 0})
            rows = self.get("/v1/calendar?period=2026")["days"]
            self.assertEqual(rows[day]["phone"], combined)
            self.assertEqual(rows[stretch_only]["phone"]["workoutKinds"], {"stretching": 1})
            self.assertEqual(rows[legacy]["phone"], PHONE_DAY)

        del kinds["stretching"]
        combined["workouts"] -= 1
        self.calendar_output = phone_calendar({day: combined, legacy: PHONE_DAY})
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        rows = self.get("/v1/calendar?period=2026")["days"]
        self.assertEqual(rows[day]["phone"], combined)
        self.assertEqual(rows[legacy]["phone"], PHONE_DAY)
        self.assertIsNone(rows[stretch_only]["phone"])
        self.assertEqual(rows[stretch_only]["pc"], {"study": 90})

    def test_usb_sends_only_totals_while_local_calendar_keeps_safe_item_names(self):
        collect = aw_calendar.collect_detailed
        self.mock_usb(41)
        self.collect.side_effect = collect
        day = date.today() - timedelta(days=1)
        noon = datetime.combine(day, time(12)).astimezone()
        day_key = day.isoformat()
        self.calendar_output = phone_calendar({day_key: PHONE_DAY})

        def event(seconds, duration, **data):
            return {"id": seconds, "timestamp": (noon + timedelta(seconds=seconds)).isoformat(),
                    "duration": duration, "data": {"title": "private window title", **data}}

        buckets = {"window": {"type": "currentwindow", "hostname": "PC"},
                   "aw-watcher-web-chrome_PC": {"type": "web.tab.current", "hostname": "PC"},
                   "afk": {"type": "afkstatus", "hostname": "PC"}}
        events = {"afk": [event(0, 60, status="not-afk"), event(50, 10, status="afk")],
                  "window": [event(0, 10, app="Orca.exe"), event(10, 20, app="chrome.exe"),
                             event(30, 30, app="code.exe")],
                  "aw-watcher-web-chrome_PC": [event(0, 60, url="https://chatgpt.com/c/private-session", incognito=False)]}

        def aw_get(path):
            if path == "info":
                return {"hostname": "PC"}
            if path == "buckets/":
                return buckets
            target = urlsplit(path)
            params = parse_qs(target.query)
            return events[target.path.split("/")[1]] if datetime.fromisoformat(params["end"][0]) > noon else []

        with patch.object(aw_calendar, "_get", side_effect=aw_get):
            self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        groups = {"ai": 30, "development": 20}
        self.assertEqual(self.call_payloads, [{"source": "abc", "days": {day_key: groups}}])
        self.assertEqual(self.get("/v1/calendar?period=" + day_key[:7]), {"days": {
            day_key: {"phone": PHONE_DAY, "pc": groups, "details": {
                "ai": [{"name": "chatgpt.com", "seconds": 20}, {"name": "orca.exe", "seconds": 10}],
                "development": [{"name": "code.exe", "seconds": 20}],
            }}}})

    def test_unavailable_activitywatch_with_no_saved_history_does_not_claim_a_transfer(self):
        self.mock_usb(41)
        self.collect.side_effect = aw_calendar.ActivityUnavailable("private reason")
        result = self.usb_post(IMPORT)
        self.assertTrue(result["partial"])
        self.assertIn("для передачи на телефон нет", result["warning"])
        self.assertNotIn("private", result["warning"])
        self.assertEqual(self.call_payloads, [])

    def test_calendar_read_failure_preserves_previous_rows_and_never_pushes_to_another_phone(self):
        initial = {"2024-02-29": PHONE_DAY}
        failures = [phone_calendar({}, source="def"), phone_calendar(initial)[:-1], phone_calendar({}, complete=False),
                    phone_calendar({}, complete=1), b'{"days":{},"days":{},"source":"abc","complete":true}',
                    phone_calendar(dict(initial, **{"2023-02-29": PHONE_DAY})),
                    phone_calendar({"2024-01-01": dict(PHONE_DAY, steps=False)}),
                    phone_calendar({"2024-01-01": dict(PHONE_DAY, pages=-1)}),
                    phone_calendar({"2024-01-01": dict(PHONE_DAY, title="private-title")}),
                    phone_calendar({"2024-01-01": {"steps": 3}})]
        failures.extend(phone_calendar({"2024-01-01": dict(PHONE_DAY, workoutKinds=kinds)})
                        for kinds in (None, [], {}, {"run": 2}, {"unknown": 1}, {"run": True},
                                      {"strength": -1, "stretching": 2}))
        self.mock_usb(None, *([41] * (len(failures) + 1)))
        self.calendar_output = phone_calendar(initial)
        self.collect.return_value = pc_snapshot({"2024-02-29": {"ai": 90}})
        self.usb_post(IMPORT)
        saved = self.get("/v1/calendar?period=2024")
        self.usb_output = (b'{"version":1,"source":"abc"}\n'
                           b'{"stream":"pulse","month":"2024-02","records":[{"id":"ok","date":"2024-02-01","value":70}]}\n'
                           + COMPLETE)
        for invalid in failures:
            with self.subTest(snapshot=invalid):
                self.calendar_output = invalid
                result = self.usb_post(IMPORT)
                self.assertEqual((result["count"], result["partial"]), (1, True))
                self.assertIn("Календарь телефона", result["warning"])
                self.assertNotIn("private-title", result["warning"])
                self.assertEqual(self.get("/v1/calendar?period=2024"), saved)
        self.assertEqual(self.collect.call_count, 1)
        self.assertEqual(len(self.call_payloads), 1)
        self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"][0]["id"], "ok")

    def test_sync_replays_all_stored_days_after_lost_ack_even_if_activitywatch_offline(self):
        self.mock_usb(None, 41, 41, 41)
        self.calendar_output = phone_calendar({"2024-02-29": PHONE_DAY})
        days = {(date(2024, 1, 1) + timedelta(days=i)).isoformat(): {"ai": i, "games": 10, "leisure": 20}
                for i in range(240)}
        self.collect.return_value = pc_snapshot(days)
        self.bad_ack_call = 2
        result = self.usb_post(IMPORT)
        self.assertTrue(result["partial"])
        self.assertIn(f"из {len(days)}", result["warning"])
        self.assertEqual(len(self.call_payloads), 2)
        self.child.terminate.assert_not_called()
        self.assertEqual(self.get("/v1/calendar?period=2024")["days"]["2024-02-29"]["phone"], PHONE_DAY)
        self.collect.side_effect = aw_calendar.ActivityUnavailable("private-url")
        result = self.usb_post(IMPORT)
        self.assertTrue(result["partial"])
        self.assertIn("ActivityWatch", result["warning"])
        self.assertNotIn("private-url", result["warning"])
        self.assertEqual(self.phone_pc, days)
        self.assertEqual(self.get("/v1/calendar?period=2024-02")["days"]["2024-02-29"]["details"],
                         self.collect.return_value[1]["2024-02-29"])
        self.assertEqual(self.call_payloads[0], self.call_payloads[2])  # retry from the first day
        self.collect.side_effect = None
        self.assertEqual(self.usb_post(IMPORT), {"count": 0})
        self.assertEqual(self.phone_pc, days)
        self.child.terminate.assert_called_once()

    def test_device_swap_after_calendar_read_never_confirms_write(self):
        self.mock_usb(41)
        self.call_source = "def"
        self.collect.return_value = pc_snapshot({"2024-01-01": {"development": 20}})
        result = self.usb_post(IMPORT)
        self.assertEqual((result["partial"], result["count"]), (True, 0))
        self.assertIn("0 из 1", result["warning"])
        self.assertNotIn("private-identifier", result["warning"])
        self.assertEqual(self.phone_pc, {})
        self.assertEqual(self.call_payloads[0]["source"], "abc")

    def test_invalid_pc_rows_rollback_before_sending_saved_data(self):
        store = self.server.store
        old = {"2024-02-29": {"development": 5}}
        _, old_details = pc_snapshot(old)
        store.replace_pc_calendar(old, old_details)
        for day, row in [("2024-02-30", {"study": 1}), ("2024-2-01", {"study": 1}), ("2024-02-01", {"private-url": 1}),
                         ("2024-02-01", {"study": True}), ("2024-02-01", {"ai": 1.5}), ("2024-02-01", {"ai": -1}),
                         ("2024-02-01", {"ai": 172800, "other": 1})]:
            with self.subTest(day=day, row=row), self.assertRaises(BadRequest):
                store.replace_pc_calendar(*pc_snapshot({"2024-02-29": {"games": 30}, day: row}))
            self.assertEqual(store.calendar("2024")["days"], {
                "2024-02-29": {"phone": None, "pc": old["2024-02-29"], "details": old_details["2024-02-29"]}})

    def test_invalid_pc_details_roll_back_totals_and_items_together(self):
        day, next_day = "2024-02-01", "2024-02-02"
        store = self.server.store
        old, old_details = pc_snapshot({day: {"study": 7}})
        store.replace_pc_calendar(old, old_details)
        saved = store.calendar("2024")
        days, details = pc_snapshot({day: {"study": 12}, next_day: {"development": 30}})
        invalid = [
            {day: details[day]}, {**details, "2024-02-03": {}}, {**details, next_day: {"ai": []}},
            *({**details, next_day: {"development": items}} for items in [
                None, [], [{"name": "app.exe", "seconds": True}], [{"name": "app.exe", "seconds": 31}],
                [{"name": "app.exe", "seconds": 15}] * 2, [{"name": "", "seconds": 30}],
                [{"name": "app.exe", "seconds": 30, "title": "not permitted"}],
                [{"name": "https://example.test/path", "seconds": 30}],
                [{"name": r"C:\Apps\app.exe", "seconds": 30}],
            ]),
        ]
        for malformed in invalid:
            with self.subTest(details=malformed), self.assertRaises(BadRequest):
                store.replace_pc_calendar(days, malformed)
            self.assertEqual(store.calendar("2024"), saved)

    def test_old_totals_migrate_without_guessing_items_and_refresh_with_unchanged_totals(self):
        path = self.database.with_name("legacy.sqlite")
        day = "2024-02-29"
        with closing(sqlite3.connect(path)) as connection, connection:
            connection.execute("CREATE TABLE calendar_pc (date TEXT PRIMARY KEY, payload TEXT) WITHOUT ROWID")
            connection.execute("INSERT INTO calendar_pc VALUES (?, ?)", (day, '{"ai":10}'))
            connection.execute("INSERT INTO calendar_pc VALUES (?, NULL)", ("2024-02-28",))
        store = Store(path)
        self.assertEqual(store.calendar("2024")["days"], {day: {"phone": None, "pc": {"ai": 10}, "details": None}})
        days, details = pc_snapshot({day: {"ai": 10}})
        store.replace_pc_calendar(days, details)
        details[day]["ai"][0]["name"] = "changed.exe"
        store.replace_pc_calendar(days, details)
        self.assertEqual(Store(path).calendar("2024")["days"], {day: {"phone": None, "pc": days[day], "details": details[day]}})
        self.assertEqual(store.pc_calendar_rows(), [("2024-02-28", None), (day, '{"ai":10}')])

    def test_replace_replay_clear_and_month_isolation(self):
        old = sample("same", "2026-11-05", 81)
        new = sample("other", "2026-11-05", 70, time="2026-11-05T16:04:00+02:00")
        previous = sample("same", "2026-10-01", 74)
        oxygen = sample("same", "2026-11-05", 99)
        self.save_batch("pulse", "2026-11", [old])
        self.save_batch("pulse", "2026-10", [previous])
        self.save_batch("oxygen", "2026-11", [oxygen])
        self.save_batch("pulse", "2026-11", [new])
        self.save_batch("pulse", "2026-11", [new])
        # The complete snapshot has committed when the import returns.
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute(
                "SELECT id, value FROM records WHERE stream = 'pulse' AND month = '2026-11'"
            ).fetchall(), [("other", 70)])
        view = self.get("/v1/series?period=all")["series"]
        self.assertEqual(set(view), set(STREAMS))
        self.assertEqual(view["pulse"]["current"]["records"], [previous, new])
        self.assertEqual(view["oxygen"]["current"]["records"], [oxygen])
        self.save_batch("pulse", "2026-11", [])
        view = self.get("/v1/series?period=all")["series"]
        self.assertEqual(view["pulse"]["current"]["records"], [previous])
        self.assertEqual(view["oxygen"]["current"]["records"], [oxygen])

    def test_keeps_source_offsets_while_sorting_by_precise_instant(self):
        magnitude = 10 ** 50
        older = sample("z", "2026-11-05", magnitude, time="2026-11-05T12:00:00.000000001+01:00")
        newer = sample("a", "2026-11-05", 70, time="2026-11-05T06:00:00.000000002-05:00")
        self.save_batch("pulse", "2026-11", [newer, older])
        data = self.get("/v1/series?stream=pulse&period=all")
        self.assertEqual(data["current"]["records"], [older, newer])
        self.assertEqual(data["all_time"]["max"], float(magnitude))

    def test_failed_batch_after_valid_row_leaves_last_commit_intact(self):
        old = sample("original", "2026-11-05", 81)
        first = sample("first", "2026-11-06", 70)
        self.save_batch("pulse", "2026-11", [old])
        for invalid in (
            sample("first", "2026-11-07", 70),
            sample("wrong-month", "2026-10-01", 70),
            sample("invalid-date", "2026-11-31", 70),
            sample("invalid-time", "2026-11-01", 70, time="yesterday"),
            sample("boolean", "2026-11-01", True),
            sample("non-finite", "2026-11-01", float("inf")),
            sample("raw-answer", "2026-11-01", 3, answers=[3]),
        ):
            with self.subTest(record=invalid["id"]), self.assertRaises(BadRequest):
                self.save_batch("pulse", "2026-11", [first, invalid])
            with self.subTest(record=invalid["id"]):
                self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"], [old])
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.executescript("""
                CREATE TRIGGER stop_second_insert BEFORE INSERT ON records WHEN NEW.id = 'blocked'
                BEGIN SELECT RAISE(ABORT, 'test constraint'); END;
            """)
        with self.assertRaises(sqlite3.IntegrityError):
            self.save_batch("pulse", "2026-11", [first, sample("blocked", "2026-11-08", 70)])
        self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"], [old])

    def test_invalid_envelopes_and_filters_rejected(self):
        for stream, month in (("notes", "2026-11"), ("pulse", "2026-13"), ("pulse", "2026-1")):
            with self.subTest(stream=stream, month=month), self.assertRaises(BadRequest):
                self.save_batch(stream, month, [sample("bad", "2026-11-01", 70)])
        for malformed in (b"\xff", b"{", b'{"stream":"pulse","stream":"steps","month":"2026-11","records":[]}'):
            with self.subTest(malformed=malformed), self.assertRaises(BadRequest):
                self.server.store.import_snapshot(BytesIO(b'{"version":1,"source":"abc"}\n' + malformed + b"\n" + COMPLETE))
        for query in ("period=day", "stream=notes", "period=all&period=year", "end=2026-02-29", "unknown=1"):
            self.get("/v1/series?" + query, expected_status=400)

    def test_truncated_snapshot_and_other_phone_preserve_previous_history(self):
        old = sample("old", "2026-11-05", 81)
        self.save_batch("pulse", "2026-11", [old])
        replacement = {"stream": "pulse", "month": "2026-11", "records": [sample("new", "2026-11-06", 70)]}
        batches = {("pulse", "2026-11"): replacement}
        for options, message in (({"trailer": b""}, "не полностью"), ({"source": "def"}, "другим телефоном")):
            with self.subTest(options=options), self.assertRaisesRegex(UsbError, message):
                self.snapshot(batches, **options)
            self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"], [old])

    def test_quarter_comparisons_and_all_time_include_correct_records(self):
        for month, records in {
            "2026-05": [sample("older", "2026-05-31", 100)],
            "2026-06": [sample("prev-start", "2026-06-01", 12)],
            "2026-08": [sample("prev-end", "2026-08-31", 8)],
            "2026-09": [sample("cur-start", "2026-09-01", 20)],
            "2026-11": [sample("cur-end", "2026-11-30", 30), sample("event", "2026-11-15")],
        }.items():
            self.save_batch("weight", month, records)
        view = self.get("/v1/series?stream=weight&period=quarter&end=2026-11-30")
        current, previous = view["current"], view["previous"]
        self.assertEqual((current["start"], current["end"]), ("2026-09-01", "2026-11-30"))
        self.assertEqual((previous["start"], previous["end"]), ("2026-06-01", "2026-08-31"))
        self.assertEqual([record["id"] for record in current["records"]], ["cur-start", "event", "cur-end"])
        self.assertEqual(current["summary"], {"count": 2, "min": 20, "max": 30, "mean": 25, "total": 50})
        self.assertEqual(previous["summary"]["mean"], 10)
        self.assertEqual(view["all_time"], {"count": 5, "min": 8, "max": 100, "mean": 34, "total": 170, "daily_mean": 34})
        all_time = self.get("/v1/series?stream=weight&period=all")
        self.assertIsNone(all_time["previous"])
        self.assertEqual(all_time["current"]["start"], "2026-05-31")
        self.assertEqual(all_time["current"]["summary"], all_time["all_time"])
        empty = self.get("/v1/series?stream=oxygen&period=all")
        self.assertEqual(empty["current"], {
            "start": None, "end": None, "records": [],
            "summary": {"count": 0, "min": None, "max": None, "mean": None, "total": 0, "daily_mean": None},
        })

    def test_reads_never_see_an_uncommitted_replacement(self):
        old = sample("old", "2026-11-05", 81)
        self.save_batch("pulse", "2026-11", [old])
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute("DELETE FROM records WHERE stream = 'pulse'")
            visible = self.get("/v1/series?stream=pulse&period=all")
            self.assertEqual(visible["current"]["records"], [old])
            self.assertEqual(visible["all_time"]["count"], 1)
            connection.rollback()

    def test_sleep_uses_wake_date_and_compares_clipped_stages(self):
        stages = [
            stage("awake", "2026-09-30T20:45:00Z", "2026-09-30T21:15:00Z"),
            stage("out_of_bed", "2026-09-30T21:10:00Z", "2026-09-30T21:20:00Z"),
            stage("deep", "2026-09-30T22:00:00Z", "2026-09-30T23:00:00Z"),
            stage("deep", "2026-09-30T22:30:00Z", "2026-09-30T23:30:00Z"),
            stage("rem", "2026-10-01T04:00:00Z", "2026-10-01T06:00:00Z"),
        ]
        night = sample("night", "2026-10-01", 27600,
                       start="2026-09-30T21:00:00Z", end="2026-10-01T06:00:00+01:00", stages=stages)
        previous = sample("earlier", "2026-09-01",
                          start="2026-09-01T00:00:00Z", end="2026-09-01T05:00:00Z", stages=[])
        self.save_batch("sleep", "2026-10", [night])
        self.save_batch("sleep", "2026-09", [previous])
        for invalid in (dict(night, end=night["start"]), dict(night, stages=[dict(stages[0], stage="invalid")])):
            with self.assertRaises(BadRequest):
                self.save_batch("sleep", "2026-10", [invalid])
        detail = self.get("/v1/sleep?date=2026-10-01")
        self.assertEqual(detail["records"], [night])
        self.assertEqual(detail["summary"], {
            "count": 1, "mean_minutes": 460, "total_minutes": 460,
            "stage_minutes": {"awake": 15, "out_of_bed": 10, "deep": 90, "rem": 60},
        })
        self.assertEqual(detail["comparison"], {
            "minutes": 460, "previous_date": "2026-09-01",
            "previous_minutes": 300, "earlier_mean_minutes": 300,
        })
        self.assertEqual(self.get("/v1/sleep?date=2026-09-30")["records"], [])
        self.get("/v1/sleep?date=2026-10-01&period=all", expected_status=400)
        query = "period=month&end=2026-10-31"
        comparison = self.get("/v1/sleep?" + query)
        self.assertEqual(comparison["current"]["records"], [night])
        self.assertEqual(comparison["current"]["summary"], detail["summary"])
        self.assertEqual(comparison["previous"]["summary"]["mean_minutes"], 300)
        self.assertEqual(comparison["all_time"]["mean_minutes"], 380)
        compact = self.get("/v1/series?stream=sleep&" + query)
        self.assertEqual(compact["current"]["records"], [sample("night", "2026-10-01", 27600)])

    def test_overview_keeps_old_latest_and_aggregates_recent_days(self):
        today = date.today().isoformat()
        self.save_batch("weight", "2020-03", [sample("old", "2020-03-01", 72)])
        self.save_batch("stress", today[:7], [sample("a", today, 12), sample("b", today, 30)])
        result = self.get("/v1/overview")["series"]
        self.assertEqual(result["weight"], {"latest": {"date": "2020-03-01", "value": 72}, "days": []})
        self.assertEqual(result["stress"]["days"], [{"date": today, "value": 21, "count": 2}])
        self.assertEqual(result["oxygen"], {"latest": None, "days": []})

    def test_opening_legacy_database_removes_retired_streams_only(self):
        self.save_batch("stress", "2026-11", [sample("keep", "2026-11-01", 31)])
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("INSERT INTO records SELECT 'who5', month, id, date, sort_time, value, payload, stage_seconds FROM records")
            connection.execute("INSERT INTO usb_partitions SELECT 'who5', month, digest FROM usb_partitions")
        type(self.server.store)(self.database)
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("SELECT stream FROM records").fetchall(), [("stress",)])
            self.assertEqual(connection.execute("SELECT stream FROM usb_partitions").fetchall(), [("stress",)])


class AdbProbeTest(unittest.TestCase):
    def test_reads_exact_version_and_rejects_unrelated_listener(self):
        for response, valid in ((b"OKAY00040029", True), (b"HTTP/1.1 400", False), (b"OKAY0004oops", False)):
            client, peer = socket.socketpair()
            with self.subTest(response=response), client, peer:
                peer.sendall(response)
                peer.shutdown(socket.SHUT_WR)
                with patch("server.socket.create_connection", return_value=client) as connect:
                    if valid:
                        self.assertEqual(_daemon_version(), 41)
                    else:
                        with self.assertRaisesRegex(UsbError, "Порт 5037 занят"):
                            _daemon_version()
                connect.assert_called_once_with(("127.0.0.1", 5037), timeout=3)
                self.assertEqual(peer.recv(64), b"000chost:version")


class CalendarTest(unittest.TestCase):

    def test_period_bounds_are_inclusive_and_calendar_based(self):
        end = date(2024, 2, 29)
        self.assertEqual(period_windows("week", end), (("2024-02-23", "2024-02-29"), ("2024-02-16", "2024-02-22")))
        self.assertEqual(period_windows("month", end), (("2024-02-01", "2024-02-29"), ("2024-01-01", "2024-01-31")))
        self.assertEqual(period_windows("year", end), (("2023-03-01", "2024-02-29"), ("2022-03-01", "2023-02-28")))


if __name__ == "__main__":
    unittest.main()
