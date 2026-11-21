from contextlib import closing
from datetime import date
from http.client import HTTPConnection
from io import BytesIO
import json
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from threading import Thread
import unittest

from server import BadRequest, STREAMS, Server, UsbError, period_windows


IMPORT = "/v1/usb"
COMPLETE = b'{"complete":true}\n'
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
        for path in ("/", "/index.html", "/assets/ui-test.css", "/assets/ui-test.js", "/v1/overview", "/v1/series", "/v1/sleep"):
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

    def test_only_dashboard_origin_can_trigger_a_bodyless_usb_read(self):
        origin = f"http://{self.server.authority}"
        for headers in ({}, {"Origin": "https://example.net"}, {"Origin": "null"},
                        {"Host": "example.net", "Origin": origin}):
            self.assertEqual(self.request("POST", IMPORT, headers=headers)[0], 403)
        self.assertEqual(self.request("POST", IMPORT, body="{}", headers={"Origin": origin})[0], 400)
        self.assertEqual(self.request("POST", "/v1/sync/batches", body="{}", headers={"Origin": origin})[0], 404)

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


class CalendarTest(unittest.TestCase):

    def test_period_bounds_are_inclusive_and_calendar_based(self):
        end = date(2024, 2, 29)
        self.assertEqual(period_windows("week", end), (("2024-02-23", "2024-02-29"), ("2024-02-16", "2024-02-22")))
        self.assertEqual(period_windows("month", end), (("2024-02-01", "2024-02-29"), ("2024-01-01", "2024-01-31")))
        self.assertEqual(period_windows("year", end), (("2023-03-01", "2024-02-29"), ("2022-03-01", "2023-02-28")))


if __name__ == "__main__":
    unittest.main()
