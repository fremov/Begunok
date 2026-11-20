import base64
from contextlib import closing
from datetime import date
from http.client import HTTPConnection
import json
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from threading import Thread
import unittest

from server import Config, MAX_BODY, STREAMS, Server, period_windows


READER = "Basic " + base64.b64encode("reader:password".encode()).decode()
WRITER = "Bearer writer-token"
INGEST = "/v1/sync/batches"
OMITTED = object()


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
        for name, content in {
            "index.html": "<h1>Private analytics</h1>", "dashboard.css": "body {}", "dashboard.js": "'use strict';",
        }.items():
            (root / name).write_text(content, encoding="utf-8")
        self.server = Server(Config(self.database, "reader", "password", "writer-token", port=0), root)
        self.addCleanup(self.server.server_close)
        self.worker = Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.worker.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.worker.join()

    def request(self, method, path, *, auth=None, body=None, headers=None):
        headers = dict(headers or {})
        if auth is not None:
            headers["Authorization"] = auth
        with closing(HTTPConnection(*self.server.server_address, timeout=5)) as client:
            client.request(method, path, body=body, headers=headers)
            with client.getresponse() as response:
                return response.status, {key.lower(): value for key, value in response.getheaders()}, response.read()

    def post(self, stream, month, records, *, expected_status=200):
        payload = {"stream": stream, "month": month, "records": records}
        status, _headers, body = self.request(
            "POST", INGEST, auth=WRITER, body=json.dumps(payload), headers={"Content-Type": "application/json"},
        )
        result = json.loads(body)
        self.assertEqual(status, expected_status, result)
        if status == 200:
            self.assertEqual(result, {"stream": stream, "month": month, "count": len(records)})
        return result

    def get(self, path, *, expected_status=200):
        status, headers, body = self.request("GET", path, auth=READER)
        result = json.loads(body)
        self.assertEqual(status, expected_status, result)
        self.assertEqual(headers["cache-control"], "no-store")
        return result

    def test_auth_is_role_specific_and_every_asset_is_private(self):
        for path in ("/", "/index.html", "/dashboard.css", "/dashboard.js", "/v1/series", "/v1/sleep"):
            with self.subTest(path=path):
                for auth in (None, WRITER, "Basic !!!!"):
                    status, headers, body = self.request("GET", path, auth=auth)
                    self.assertEqual(status, 401)
                    self.assertTrue(headers["www-authenticate"].startswith("Basic "))
                    self.assertEqual(headers["cache-control"], "no-store")
                    self.assertEqual(json.loads(body), {"error": "authentication required"})
        for path, mime in (("/", "text/html"), ("/dashboard.css", "text/css"), ("/dashboard.js", "text/javascript")):
            status, headers, body = self.request("GET", path, auth=READER)
            self.assertEqual(status, 200)
            self.assertTrue(headers["content-type"].startswith(mime))
            self.assertGreater(len(body), 0)
            status, head, empty = self.request("HEAD", path, auth=READER)
            self.assertEqual((status, head["content-length"], empty), (200, str(len(body)), b""))
        self.assertEqual(self.request("HEAD", "/dashboard.css")[0], 401)
        for path in ("/private.sqlite", "/server.py", "/test_server.py", "/../private.sqlite"):
            self.assertEqual(self.request("GET", path, auth=READER)[0], 404)
        for auth in (None, READER, "Bearer wrong-token"):
            status, headers, _body = self.request("POST", INGEST, auth=auth)
            self.assertEqual(status, 401)
            self.assertTrue(headers["www-authenticate"].startswith("Bearer "))

    def test_usb_listener_can_ingest_but_never_serve_the_site(self):
        config = Config(self.database, "reader", "password", "writer-token", port=0)
        with Server(config, writer_only=True, store=self.server.store) as usb:
            worker = Thread(target=usb.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
            worker.start()
            try:
                for method, path in (("GET", "/"), ("HEAD", "/dashboard.js"), ("GET", "/v1/series")):
                    with closing(HTTPConnection(*usb.server_address, timeout=5)) as client:
                        client.request(method, path, headers={"Authorization": READER})
                        with client.getresponse() as response:
                            self.assertEqual(response.status, 404)
                payload = {"stream": "steps", "month": "2026-11", "records": [sample("day", "2026-11-20", 123)]}
                with closing(HTTPConnection(*usb.server_address, timeout=5)) as client:
                    client.request("POST", INGEST, json.dumps(payload), {"Authorization": WRITER, "Content-Type": "application/json"})
                    with client.getresponse() as response:
                        self.assertEqual(response.status, 200)
            finally:
                usb.shutdown()
                worker.join()
        self.assertEqual(self.get("/v1/series?stream=steps&period=all")["current"]["records"], payload["records"])

    def test_replace_replay_clear_and_month_isolation(self):
        old = sample("same", "2026-11-05", 81)
        new = sample("other", "2026-11-05", 70, time="2026-11-05T16:04:00+02:00")
        previous = sample("same", "2026-10-01", 74)
        oxygen = sample("same", "2026-11-05", 99)
        self.post("pulse", "2026-11", [old])
        self.post("pulse", "2026-10", [previous])
        self.post("oxygen", "2026-11", [oxygen])
        self.post("pulse", "2026-11", [new])
        self.post("pulse", "2026-11", [new])
        # A separate SQLite connection must already see the replacement when the HTTP ACK arrives.
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute(
                "SELECT id, value FROM records WHERE stream = 'pulse' AND month = '2026-11'"
            ).fetchall(), [("other", 70)])
        view = self.get("/v1/series?period=all")["series"]
        self.assertEqual(set(view), set(STREAMS))
        self.assertEqual(view["pulse"]["current"]["records"], [previous, new])
        self.assertEqual(view["oxygen"]["current"]["records"], [oxygen])
        self.post("pulse", "2026-11", [])
        view = self.get("/v1/series?period=all")["series"]
        self.assertEqual(view["pulse"]["current"]["records"], [previous])
        self.assertEqual(view["oxygen"]["current"]["records"], [oxygen])

    def test_keeps_source_offsets_while_sorting_by_precise_instant(self):
        magnitude = 10 ** 50
        older = sample("z", "2026-11-05", magnitude, time="2026-11-05T12:00:00.000000001+01:00")
        newer = sample("a", "2026-11-05", 70, time="2026-11-05T06:00:00.000000002-05:00")
        self.post("pulse", "2026-11", [newer, older])
        data = self.get("/v1/series?stream=pulse&period=all")
        self.assertEqual(data["current"]["records"], [older, newer])
        self.assertEqual(data["all_time"]["max"], float(magnitude))

    def test_failed_batch_after_valid_row_leaves_last_commit_intact(self):
        old = sample("original", "2026-11-05", 81)
        first = sample("first", "2026-11-06", 70)
        self.post("pulse", "2026-11", [old])
        for invalid in (
            sample("first", "2026-11-07", 70),
            sample("wrong-month", "2026-10-01", 70),
            sample("invalid-date", "2026-11-31", 70),
            sample("invalid-time", "2026-11-01", 70, time="yesterday"),
            sample("boolean", "2026-11-01", True),
            sample("non-finite", "2026-11-01", float("inf")),
            sample("raw-answer", "2026-11-01", 3, answers=[3]),
        ):
            with self.subTest(record=invalid["id"]):
                self.post("pulse", "2026-11", [first, invalid], expected_status=400)
                self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"], [old])
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.executescript("""
                CREATE TRIGGER stop_second_insert BEFORE INSERT ON records WHEN NEW.id = 'blocked'
                BEGIN SELECT RAISE(ABORT, 'test constraint'); END;
            """)
        with self.assertLogs("server", level="ERROR") as logs:
            self.post("pulse", "2026-11", [first, sample("blocked", "2026-11-08", 70)], expected_status=500)
        self.assertNotIn("test constraint", " ".join(logs.output))
        self.assertEqual(self.get("/v1/series?stream=pulse&period=all")["current"]["records"], [old])

    def test_invalid_envelopes_and_transport_rejected(self):
        self.post("notes", "2026-11", [], expected_status=400)
        self.post("pulse", "2026-13", [], expected_status=400)
        self.post("pulse", "2026-1", [], expected_status=400)
        for malformed in (b"\xff", b"{", b'{"stream":"pulse","stream":"steps","month":"2026-11","records":[]}'):
            status, _headers, _body = self.request(
                "POST", INGEST, auth=WRITER, body=malformed, headers={"Content-Type": "application/json"},
            )
            self.assertEqual(status, 400)
        self.assertEqual(self.request("POST", INGEST, auth=WRITER, body="{}")[0], 415)
        status, _headers, _body = self.request("POST", INGEST, auth=WRITER, headers={
            "Content-Type": "application/json", "Content-Length": str(MAX_BODY + 1),
        })
        self.assertEqual(status, 413)
        for query in ("period=day", "stream=notes", "period=all&period=year", "end=2026-02-29", "unknown=1"):
            self.get("/v1/series?" + query, expected_status=400)

    def test_quarter_comparisons_and_all_time_include_correct_records(self):
        for month, records in {
            "2026-05": [sample("older", "2026-05-31", 100)],
            "2026-06": [sample("prev-start", "2026-06-01", 12)],
            "2026-08": [sample("prev-end", "2026-08-31", 8)],
            "2026-09": [sample("cur-start", "2026-09-01", 20)],
            "2026-11": [sample("cur-end", "2026-11-30", 30), sample("event", "2026-11-15")],
        }.items():
            self.post("weight", month, records)
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
        empty = self.get("/v1/series?stream=phq9&period=all")
        self.assertEqual(empty["current"], {
            "start": None, "end": None, "records": [],
            "summary": {"count": 0, "min": None, "max": None, "mean": None, "total": 0, "daily_mean": None},
        })

    def test_reads_never_see_an_uncommitted_replacement(self):
        old = sample("old", "2026-11-05", 81)
        self.post("pulse", "2026-11", [old])
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
        self.post("sleep", "2026-10", [night])
        self.post("sleep", "2026-09", [previous])
        for invalid in (dict(night, end=night["start"]), dict(night, stages=[dict(stages[0], stage="invalid")])):
            self.post("sleep", "2026-10", [invalid], expected_status=400)
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


class ConfigurationAndCalendarTest(unittest.TestCase):
    def test_required_credentials_and_private_bind(self):
        prefix = "MYLIFE_ANALYTICS_"
        env = {prefix + key: value for key, value in {
            "DB": "analytics.sqlite", "READER_USER": "reader", "READER_PASSWORD": "password", "WRITER_TOKEN": "token",
        }.items()}
        self.assertEqual((Config.from_env(env).host, Config.from_env(env).port), ("127.0.0.1", 8786))
        for key, value in (("HOST", "0.0.0.0"), ("HOST", "192.168.1.15"), ("PORT", "0"),
                           ("WRITER_TOKEN", "password"), ("READER_USER", "user:extra"), ("TIMEZONE", "Invalid/Zone")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                Config.from_env({**env, prefix + key: value})
        for required in env:
            with self.subTest(required=required), self.assertRaises(ValueError):
                Config.from_env({key: value for key, value in env.items() if key != required})

    def test_period_bounds_are_inclusive_and_calendar_based(self):
        end = date(2024, 2, 29)
        self.assertEqual(period_windows("week", end), (("2024-02-23", "2024-02-29"), ("2024-02-16", "2024-02-22")))
        self.assertEqual(period_windows("month", end), (("2024-02-01", "2024-02-29"), ("2024-01-01", "2024-01-31")))
        self.assertEqual(period_windows("year", end), (("2023-03-01", "2024-02-29"), ("2022-03-01", "2023-02-28")))


if __name__ == "__main__":
    unittest.main()
