"""Local MyLife dashboard and USB snapshot importer (Python standard library)."""

from base64 import urlsafe_b64encode
import calendar
from collections import defaultdict
from contextlib import closing
from hashlib import sha256
from datetime import date, datetime, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
from tempfile import TemporaryFile
from threading import Lock
import time
from urllib.parse import parse_qs, urlsplit

import aw_calendar


STREAMS = (
    "pulse", "oxygen", "stress", "steps", "active_kcal", "distance", "sleep",
    "shower", "weight",
)
STAGES = {"unknown", "awake", "sleeping", "out_of_bed", "light", "deep", "rem"}
INDEX = ("index.html", "text/html; charset=utf-8")
BUNDLE_ASSET = re.compile(r"/assets/[A-Za-z0-9_.-]+\.(js|css)")
BUNDLE_MIME = {"js": "text/javascript; charset=utf-8", "css": "text/css; charset=utf-8"}
RETIRED_STREAMS = ("tension", "who5", "pss10", "gad7", "phq9")
TOTAL_STREAMS = {"steps", "active_kcal", "distance", "sleep", "shower"}
MAX_BATCH = 64 * 1024 * 1024
MAX_RECORDS = 300_000
# Bound magnitudes well beyond any supported measurement so SQLite's SUM stays finite.
MAX_VALUE = 1e100
ONE_DAY = timedelta(days=1)
NANOS_PER_SECOND = 1_000_000_000
MONTHS_PER_PERIOD = {"month": 1, "quarter": 3, "year": 12}
TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.([0-9]{1,9}))?(?:Z|[+-](?:[01][0-9]|2[0-3]):?[0-5][0-9])?"
)
LOGGER = logging.getLogger(__name__)
ADB_ENDPOINT = ("127.0.0.1", 5037)
PROVIDER = "content://ru.begunok.analytics"
PHONE_FIELDS = {"workouts", "topics", "tasks", "reviews", "pages", "showerDurationSec", "steps"}
PC_GROUPS = {"ai", "study", "development", "games", "leisure", "other"}
# Base64 expands this to at most 3200 ASCII bytes, below adb shell's 4 KiB service limit.
PC_CHUNK_BYTES = 2400


class BadRequest(ValueError):
    pass


class UsbError(ValueError):
    pass


def _adb_options():
    overrides = {"ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS", "ANDROID_ADB_SERVER_PORT"}
    return {"env": {k: v for k, v in os.environ.items() if k not in overrides},
            "stdin": subprocess.DEVNULL, "creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}


def _adb_launch_error(error):
    if isinstance(error, FileNotFoundError):
        return UsbError("ADB не найден. Установите Android Platform Tools или проверьте путь в adb-path.txt.")
    if isinstance(error, PermissionError):
        return UsbError("Нет разрешения на запуск ADB. Проверьте права на файл adb.")
    return UsbError("Не удалось запустить программу adb. Проверьте путь в adb-path.txt и установку Android Platform Tools.")


def _daemon_version():
    # A normal adb command is not a harmless probe: it may start or kill a daemon.
    try:
        sock = socket.create_connection(ADB_ENDPOINT, timeout=3)
    except ConnectionRefusedError:
        return None
    with sock, sock.makefile("rb") as reply:
        sock.sendall(b"000chost:version")
        if reply.read(8) == b"OKAY0004":
            version = reply.read(4)
            if re.fullmatch(b"[0-9a-fA-F]{4}", version):
                return int(version, 16)
    raise UsbError("Порт 5037 занят службой, которая не отвечает как ADB. Проверьте, какая программа использует порт.")


class LocalAdb:
    def __init__(self, executable):
        self.executable = executable
        self.owned = None
        self.start_error = None

    def start(self):
        try:
            self._ensure_running()
        except UsbError as error:
            self.start_error = str(error)
            raise
        self.start_error = None

    def _ensure_running(self):
        try:
            result = subprocess.run([self.executable, "version"], capture_output=True, timeout=5, **_adb_options())
        except OSError as error:
            raise _adb_launch_error(error) from None
        except subprocess.TimeoutExpired:
            raise UsbError("Программа adb не ответила. Проверьте установку Android Platform Tools.") from None
        version = re.search(rb"(?m)^Android Debug Bridge version 1\.0\.(\d{1,5})\s*$", result.stdout)
        if result.returncode or not version:
            raise UsbError("Не удалось определить версию ADB. Проверьте путь в adb-path.txt и обновите Android Platform Tools.")
        client_version = int(version[1])
        deadline = time.monotonic() + 12
        launched = False
        while True:
            try:
                running_version = _daemon_version()
            except TimeoutError:
                if self.owned is not None and self.owned.poll() is None and time.monotonic() < deadline:
                    continue  # The foreground daemon can be bound while its USB scan is still running.
                raise UsbError("Служба на порту 5037 не ответила вовремя. Проверьте локальный ADB и повторите попытку.") from None
            except OSError:
                raise UsbError("Не удалось связаться с локальным ADB на порту 5037. Проверьте, какая программа использует порт.") from None
            if self.owned is not None and self.owned.poll() is not None:
                self.owned = None
            if running_version is not None:
                # adb exec-out would kill even an unrelated daemon on a version mismatch.
                if running_version != client_version:
                    raise UsbError("Версии программы и работающего сервера ADB различаются. Укажите тот же adb, что использует Android Studio.")
                return
            if self.owned is None:
                if launched:
                    raise UsbError("Сервер ADB завершился при запуске. Проверьте Android Platform Tools и доступность порта 5037.")
                try:
                    # nodaemon keeps the exact process handle: never kill whatever later owns 5037.
                    self.owned = subprocess.Popen([self.executable, "-L", "tcp:localhost:5037", "server", "nodaemon"],
                                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **_adb_options())
                except OSError as error:
                    raise _adb_launch_error(error) from None
                launched = True
            if time.monotonic() >= deadline:
                raise UsbError("Сервер ADB не запустился вовремя. Проверьте порт 5037 и повторите попытку.")
            time.sleep(0.1)

    def stop_after_commit(self):
        if self.owned is not None:
            try:
                if self.owned.poll() is None:
                    self.owned.terminate()
                    self.owned.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                return "Данные сохранены, но не удалось остановить запущенный сайтом сервер ADB."
            self.owned = None
        return None


def _usb_diagnostic(detail):
    text = detail.lower()
    if b"unauthorized" in text:
        return "Телефон не разрешил USB-отладку. Разблокируйте его и подтвердите запрос на экране."
    if b"offline" in text:
        return "Телефон виден ADB, но не отвечает (offline). Переподключите кабель и разблокируйте экран."
    if b"more than one" in text:
        return "Подключено несколько USB-устройств. Оставьте подключённым только нужный телефон."
    if b"no device" in text or b"device not found" in text:
        return "ADB не видит телефон. Проверьте USB-кабель, драйвер и включите USB-отладку на телефоне."
    if b"no permissions" in text:
        return "На ПК нет прав для доступа к USB-устройству. Проверьте разрешения для ADB."
    if b"cannot connect to daemon" in text or b"cannot start server on remote host" in text:
        return "Соединение с локальным сервером ADB потеряно. Повторите импорт, чтобы запустить его снова."
    if b"securityexception" in text or b"permission denial" in text:
        return "Android запретил чтение снимка MyLife. Разблокируйте телефон и проверьте версию приложения и профиль Android."
    if b"could not find provider" in text or b"no content provider" in text or b"no provider found" in text:
        return "На телефоне не найден источник данных MyLife. Проверьте установку и версию приложения в текущем профиле Android."
    if b"error while accessing provider" in text:
        return "Не удалось прочитать данные у приложения MyLife. Разблокируйте телефон, откройте приложение и повторите импорт."
    return None


def _run_usb(adb, args, stdout):
    command = [adb, "-H", ADB_ENDPOINT[0], "-P", str(ADB_ENDPOINT[1]), "-d", *args]
    try:
        result = subprocess.run(command, stdout=stdout, stderr=subprocess.PIPE, timeout=120, **_adb_options())
    except OSError as error:
        raise _adb_launch_error(error) from None
    except subprocess.TimeoutExpired:
        raise UsbError("Телефон не ответил вовремя. Разблокируйте его и повторите импорт.") from None
    if result.returncode:
        raise UsbError(_usb_diagnostic(result.stderr) or
                       f"Ошибка обмена с MyLife через ADB (код {result.returncode}). Проверьте подключение и версию приложения.")
    return result.stdout


def _read_provider(adb, path, consume):
    with TemporaryFile() as snapshot:
        _run_usb(adb, ["exec-out", "content", "read", "--uri", f"{PROVIDER}/{path}"], snapshot)
        snapshot.seek(0)
        # exec-out can return success with an Android content-provider exception on stdout.
        if snapshot.read(1) != b"{":
            snapshot.seek(0)
            diagnostic = _usb_diagnostic(snapshot.read(4096))
            if diagnostic:
                raise UsbError(diagnostic)
        snapshot.seek(0)
        return consume(snapshot)


def read_usb(adb, store):
    try:
        return _read_provider(adb, "snapshot", store.import_snapshot)
    except BadRequest:
        raise UsbError("Некорректный формат данных в снимке MyLife. Проверьте версию приложения. История на ПК не изменена.") from None


def _pc_chunks(source, rows):
    # All strings here are validated ASCII (dates, Android ID, compact group JSON).
    prefix = '{"source":' + json_text(source) + ',"days":{'
    entries = []
    size = len(prefix) + 2
    for day, payload in rows:
        entry = f'"{day}":{payload or "{}"}'
        if size + len(entry) + bool(entries) > PC_CHUNK_BYTES:
            yield (prefix + ",".join(entries) + "}}").encode("ascii"), len(entries)
            entries.clear()
            size = len(prefix) + 2
        size += len(entry) + bool(entries)
        entries.append(entry)
    if entries:
        yield (prefix + ",".join(entries) + "}}").encode("ascii"), len(entries)


def _push_pc_chunk(adb, payload):
    arg = urlsafe_b64encode(payload).rstrip(b"=").decode("ascii")
    output = _run_usb(adb, ["shell", "content", "call", "--uri", PROVIDER, "--method", "calendar-pc",
                            "--arg", arg], subprocess.PIPE)
    digest = sha256(payload).hexdigest().encode("ascii")
    if output.strip() != b"Result: Bundle[{ack=" + digest + b"}]":
        raise UsbError(_usb_diagnostic(output[:4096]) or
                       "Телефон не подтвердил получение пакета. Проверьте подключение и версию приложения.")


def _sync_calendars(adb, store):
    # The metrics snapshot already committed. Report subsequent failures as partial, not as a lost import.
    try:
        source = _read_provider(adb, "calendar", store.import_phone_calendar)
    except UsbError as error:
        return f"Показатели сохранены. Календарь телефона не импортирован: {error}"
    except BadRequest:
        return "Показатели сохранены. Календарь телефона неполный или имеет неверный формат; прежний календарь не изменён."
    except (sqlite3.Error, OSError):
        return "Показатели сохранены. Не удалось сохранить календарь телефона на ПК. Проверьте место на диске и повторите обмен."

    warnings = []
    try:
        days, details = aw_calendar.collect_detailed()
        store.replace_pc_calendar(days, details)
    except aw_calendar.ActivityUnavailable:
        warnings.append("ActivityWatch недоступен. Время ПК не пересчитано.")
    except BadRequest:
        warnings.append("Некорректные суточные итоги ActivityWatch. Время ПК не пересчитано.")
    except (sqlite3.Error, OSError):
        return "Календарь телефона сохранён на ПК. Не удалось обновить базу активности ПК. Повторите обмен."
    try:
        rows = store.pc_calendar_rows()
    except sqlite3.Error:
        return "Календарь телефона сохранён на ПК. Не удалось прочитать итоги ПК для отправки на телефон."
    confirmed = 0
    try:
        for payload, count in _pc_chunks(source, rows):
            _push_pc_chunk(adb, payload)
            confirmed += count
    except UsbError as error:
        warnings.append(f"Телефон подтвердил дней ПК: {confirmed} из {len(rows)}. {error} Повторите синхронизацию.")
    else:
        if warnings:
            warnings.append(f"На телефон переданы сохранённые итоги ПК: {confirmed} дн." if confirmed else
                            "Сохранённых итогов ПК для передачи на телефон нет.")
    return " ".join(["Календарь телефона сохранён на ПК.", *warnings]) if warnings else None


def exact_date(value, field):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
        try:
            return date.fromisoformat(value)
        except ValueError:
            pass
    raise BadRequest(f"invalid {field}: expected YYYY-MM-DD")


def _pc_detail_json(groups, details):
    if not isinstance(details, dict) or details.keys() != groups.keys():
        raise BadRequest("invalid PC calendar detail groups")
    for group, items in details.items():
        if not isinstance(items, list):
            raise BadRequest("invalid PC calendar detail list")
        seconds = 0
        names = set()
        for item in items:
            if not isinstance(item, dict) or item.keys() != {"name", "seconds"}:
                raise BadRequest("invalid PC calendar detail fields")
            name, duration = item["name"], item["seconds"]
            if (not isinstance(name, str) or not name or not name.isprintable() or "/" in name or "\\" in name
                    or name in names or type(duration) is not int or duration <= 0):
                raise BadRequest("invalid PC calendar detail item")
            names.add(name)
            seconds += duration
        if seconds != groups[group]:
            raise BadRequest("PC calendar detail duration mismatch")
    return json_text(details) if groups else None


def _calendar_rows(days, *, phone, details=None):
    if not isinstance(days, dict) or len(days) > MAX_RECORDS:
        raise BadRequest("invalid calendar days")
    if not phone and (not isinstance(details, dict) or details.keys() != days.keys()):
        raise BadRequest("invalid PC calendar detail days")
    for day, row in days.items():
        exact_date(day, "calendar date")
        if not isinstance(row, dict):
            raise BadRequest("invalid calendar day")
        if phone:
            if row.keys() != PHONE_FIELDS:
                raise BadRequest("invalid phone calendar fields")
        elif not row.keys() <= PC_GROUPS:
            raise BadRequest("unknown PC calendar group")
        for key, value in row.items():
            if phone and key == "steps" and value is None:
                continue
            if type(value) is not int or not 0 <= value < 2**63:
                raise BadRequest("invalid calendar count or duration")
        if not phone and sum(row.values()) > 48 * 3600:
            raise BadRequest("invalid PC calendar day duration")
        payload = json_text(row) if row else None
        if phone:
            yield day, payload
        else:
            yield day, payload, _pc_detail_json(row, details[day])


def _calendar_bounds(period):
    if re.fullmatch(r"[0-9]{4}", period):
        first = exact_date(period + "-01-01", "calendar year")
        return first.isoformat(), period + "-12-31"
    first = exact_date(period + "-01", "calendar month")
    return first.isoformat(), period + f"-{calendar.monthrange(first.year, first.month)[1]:02d}"


def instant(value, field, *, require_offset=True):
    match = TIMESTAMP.fullmatch(value) if isinstance(value, str) else None
    if match:
        try:
            parsed = datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
            if parsed.tzinfo is not None:
                parsed = parsed.astimezone(timezone.utc)
            if parsed.tzinfo is not None or not require_offset:
                # Keep Android's nanosecond precision; datetime only stores microseconds.
                seconds = ((parsed.toordinal() * 24 + parsed.hour) * 60 + parsed.minute) * 60 + parsed.second
                return seconds * NANOS_PER_SECOND + int((match[1] or "").ljust(9, "0"))
        except (ValueError, OverflowError):
            pass
    raise BadRequest(f"invalid {field}: expected an ISO 8601 timestamp")


def json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BadRequest("duplicate JSON object key")
        result[key] = value
    return result


def invalid_constant(_value):
    raise BadRequest("non-finite JSON number")


def json_text(payload):
    return json.dumps(payload, separators=(",", ":"), allow_nan=False)


def parse_json(body):
    try:
        data = json.loads(body.decode("utf-8"), object_pairs_hook=json_object, parse_constant=invalid_constant)
    except BadRequest:
        raise
    except (ValueError, RecursionError):
        raise BadRequest("invalid UTF-8 JSON body") from None
    return data


def parse_batch(body):
    data = parse_json(body)
    if not isinstance(data, dict) or data.keys() != {"stream", "month", "records"}:
        raise BadRequest("body must contain stream, month and records")
    stream, month, records = data["stream"], data["month"], data["records"]
    if stream not in STREAMS:
        raise BadRequest("unknown stream")
    if not isinstance(month, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}", month):
        raise BadRequest("invalid month: expected YYYY-MM")
    exact_date(month + "-01", "month")
    if not isinstance(records, list) or len(records) > MAX_RECORDS:
        raise BadRequest(f"records must be an array of at most {MAX_RECORDS} items")
    return stream, month, records


def merged_duration(intervals):
    total = 0
    start = end = None
    for next_start, next_end in sorted(intervals):
        if start is None:
            start, end = next_start, next_end
        elif next_start > end:
            total += end - start
            start, end = next_start, next_end
        elif next_end > end:
            end = next_end
    if start is not None:
        total += end - start
    return total


def sleep_metrics(record):
    start, end = instant(record["start"], "sleep start"), instant(record["end"], "sleep end")
    if end <= start:
        raise BadRequest("sleep end must follow start")
    if not isinstance(record["stages"], list):
        raise BadRequest("sleep stages must be an array")
    intervals = defaultdict(list)
    awake = []
    for entry in record["stages"]:
        if not isinstance(entry, dict) or entry.keys() != {"start", "end", "stage"}:
            raise BadRequest("each sleep stage must contain start, end and stage")
        stage = entry["stage"]
        if not isinstance(stage, str) or stage not in STAGES:
            raise BadRequest("unknown sleep stage")
        left, right = instant(entry["start"], "stage start"), instant(entry["end"], "stage end")
        if right <= left:
            raise BadRequest("stage end must follow start")
        # Keep source intervals intact in the API while limiting metrics to the session.
        clipped = max(start, left), min(end, right)
        if clipped[0] < clipped[1]:
            intervals[stage].append(clipped)
            if stage in {"awake", "out_of_bed"}:
                awake.append(clipped)
    duration = (end - start - merged_duration(awake)) / NANOS_PER_SECOND
    return start, duration, {stage: merged_duration(samples) / NANOS_PER_SECOND for stage, samples in intervals.items()}


def record_rows(stream, month, records):
    sleep_fields = {"start", "end", "stages"} if stream == "sleep" else set()
    required = {"id", "date"} | sleep_fields
    allowed = required | {"time", "value"}
    seen = set()
    for record in records:
        if not isinstance(record, dict) or not required <= record.keys() <= allowed:
            raise BadRequest("record fields do not match the stream schema")
        record_id = record["id"]
        if not isinstance(record_id, str) or not record_id.strip() or len(record_id) > 256 or not record_id.isprintable():
            raise BadRequest("invalid record id")
        if record_id in seen:
            raise BadRequest("duplicate record id in batch")
        seen.add(record_id)
        record_date = exact_date(record["date"], "record date").isoformat()
        if not record_date.startswith(month + "-"):
            raise BadRequest("record date does not match snapshot month")
        recorded_at = instant(record["time"], "record time", require_offset=False) if "time" in record else None
        value = record.get("value")
        if "value" in record and (type(value) not in (int, float) or not -MAX_VALUE <= value <= MAX_VALUE):
            raise BadRequest("record value must be a finite number within the supported range")
        stages = None
        if stream == "sleep":
            start, value, stages = sleep_metrics(record)
            recorded_at = recorded_at or start
        yield (
            stream, month, record_id, record_date, f"{recorded_at:021d}" if recorded_at is not None else "",
            float(value) if value is not None else None, json_text(record), json_text(stages) if stages is not None else None,
        )


def subtract_months(day, months):
    year, month = divmod(day.year * 12 + day.month - 1 - months, 12)
    return date(year, month + 1, min(day.day, calendar.monthrange(year, month + 1)[1]))


def period_windows(period, end):
    if period == "all":
        return None, None
    try:
        if period == "week":
            start = end - 6 * ONE_DAY
            previous_start = start - 7 * ONE_DAY
        else:
            months = MONTHS_PER_PERIOD[period]
            start = subtract_months(end + ONE_DAY, months)
            previous_start = subtract_months(start, months)
        return (start.isoformat(), end.isoformat()), (previous_start.isoformat(), (start - ONE_DAY).isoformat())
    except (OverflowError, ValueError):
        raise BadRequest("end date is outside the supported range for this period") from None


class Store:
    def __init__(self, path):
        self.path = path
        with closing(self.connect()) as connection, connection:
            if connection.execute("PRAGMA journal_mode=WAL").fetchone()[0] != "wal":
                raise sqlite3.OperationalError("analytics database requires WAL mode")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS records (
                    stream TEXT NOT NULL,
                    month TEXT NOT NULL,
                    id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    sort_time TEXT NOT NULL,
                    value REAL,
                    payload TEXT NOT NULL,
                    stage_seconds TEXT,
                    PRIMARY KEY (stream, month, id)
                ) WITHOUT ROWID;
                CREATE INDEX IF NOT EXISTS records_by_date ON records(stream, date, sort_time, id);
                CREATE TABLE IF NOT EXISTS usb_source (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    source TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS usb_partitions (
                    stream TEXT NOT NULL,
                    month TEXT NOT NULL,
                    digest BLOB NOT NULL,
                    PRIMARY KEY (stream, month)
                ) WITHOUT ROWID;
                CREATE TABLE IF NOT EXISTS calendar_phone (
                    date TEXT PRIMARY KEY, payload TEXT NOT NULL
                ) WITHOUT ROWID;
                CREATE TABLE IF NOT EXISTS calendar_pc (
                    date TEXT PRIMARY KEY, payload TEXT, details TEXT
                ) WITHOUT ROWID;
            """)
            connection.execute("BEGIN IMMEDIATE")
            if "details" not in {row[1] for row in connection.execute("PRAGMA table_info(calendar_pc)")}:
                # Old totals have no recoverable item breakdown until the next successful collection.
                connection.execute("ALTER TABLE calendar_pc ADD COLUMN details TEXT")
            connection.executemany("DELETE FROM records WHERE stream = ?", ((s,) for s in RETIRED_STREAMS))
            connection.executemany("DELETE FROM usb_partitions WHERE stream = ?", ((s,) for s in RETIRED_STREAMS))

    def connect(self):
        connection = sqlite3.connect(self.path, timeout=10)
        try:
            connection.execute("PRAGMA synchronous=FULL")
        except sqlite3.Error:
            connection.close()
            raise
        return connection

    def import_snapshot(self, snapshot):
        try:
            header = parse_json(snapshot.readline(256))
        except BadRequest:
            header = None
        if (not isinstance(header, dict) or header.keys() != {"version", "source"} or
                type(header["version"]) is not int or header["version"] != 1 or
                not isinstance(header["source"], str) or not re.fullmatch(r"[0-9a-f]{1,16}", header["source"])):
            raise UsbError("Снимок недоступен. Разблокируйте телефон и проверьте, что на нём установлена новая версия MyLife.")
        count = 0
        seen = set()
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            source = connection.execute("SELECT source FROM usb_source").fetchone()
            if source is None:
                connection.execute("INSERT INTO usb_source VALUES (1, ?)", (header["source"],))
            elif source[0] != header["source"]:
                raise UsbError("Эта база уже связана с другим телефоном или профилем Android.")
            previous = {(stream, month): digest for stream, month, digest in
                        connection.execute("SELECT stream, month, digest FROM usb_partitions")}
            while True:
                line = snapshot.readline(MAX_BATCH + 1)
                if line == b'{"complete":true}\n' and not snapshot.read(1):
                    break
                if not line.endswith(b"\n") or len(line) > MAX_BATCH:
                    raise UsbError("Снимок получен не полностью или слишком велик. История на ПК не изменена.")
                stream, month, records = parse_batch(line)
                key = (stream, month)
                if key in seen:
                    raise BadRequest("duplicate snapshot partition")
                seen.add(key)
                count += len(records)
                digest = sha256(line).digest()
                if previous.get(key) == digest:
                    continue
                connection.execute("DELETE FROM records WHERE stream = ? AND month = ?", key)
                connection.executemany("""
                    INSERT INTO records (stream, month, id, date, sort_time, value, payload, stage_seconds)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, record_rows(stream, month, records))
                connection.execute("INSERT OR REPLACE INTO usb_partitions VALUES (?, ?, ?)", (*key, digest))
            removed = previous.keys() - seen
            connection.executemany("DELETE FROM records WHERE stream = ? AND month = ?", removed)
            connection.executemany("DELETE FROM usb_partitions WHERE stream = ? AND month = ?", removed)
        return count

    def import_phone_calendar(self, snapshot):
        body = snapshot.read(MAX_BATCH + 1)
        if len(body) > MAX_BATCH:
            raise BadRequest("calendar snapshot too large")
        data = parse_json(body)
        if (not isinstance(data, dict) or data.keys() != {"source", "days", "complete"} or
                data["complete"] is not True or not isinstance(data["source"], str)):
            raise BadRequest("invalid or incomplete calendar snapshot")
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            if connection.execute("SELECT source FROM usb_source").fetchone() != (data["source"],):
                raise UsbError("Подключён другой телефон или профиль Android. Повторите обмен с исходным телефоном.")
            self._replace_calendar(connection, data["days"], phone=True)
        return data["source"]

    @staticmethod
    def _replace_calendar(connection, days, *, phone, details=None):
        if phone:
            table = "calendar_phone"
            upsert = """INSERT INTO calendar_phone (date, payload) VALUES (?, ?)
                        ON CONFLICT (date) DO UPDATE SET payload = excluded.payload
                        WHERE payload IS NOT excluded.payload"""
            remove = "DELETE FROM calendar_phone"
        else:
            table = "calendar_pc"
            upsert = """INSERT INTO calendar_pc (date, payload, details) VALUES (?, ?, ?)
                        ON CONFLICT (date) DO UPDATE SET payload = excluded.payload, details = excluded.details
                        WHERE payload IS NOT excluded.payload OR details IS NOT excluded.details"""
            # Replay deletions over USB, without keeping removed names in the calendar.
            remove = "UPDATE calendar_pc SET payload = NULL, details = NULL"
        existing = {day for day, in connection.execute(f"SELECT date FROM {table} WHERE payload IS NOT NULL")}
        connection.executemany(upsert, _calendar_rows(days, phone=phone, details=details))
        connection.executemany(remove + " WHERE date = ?", ((day,) for day in existing - days.keys()))

    def replace_pc_calendar(self, days, details):
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            self._replace_calendar(connection, days, phone=False, details=details)

    def pc_calendar_rows(self):
        with closing(self.connect()) as connection:
            return connection.execute("SELECT date, payload FROM calendar_pc ORDER BY date").fetchall()

    def calendar(self, period):
        bounds = _calendar_bounds(period)
        days = {}
        with closing(self.connect()) as connection:
            for day, side, payload, details in connection.execute("""
                SELECT date, 'phone', payload, NULL FROM calendar_phone WHERE date BETWEEN ? AND ?
                UNION ALL
                SELECT date, 'pc', payload, details FROM calendar_pc WHERE date BETWEEN ? AND ? AND payload IS NOT NULL
            """, bounds + bounds):
                row = days.setdefault(day, {"phone": None, "pc": None, "details": None})
                row[side] = json.loads(payload)
                if details is not None:
                    row["details"] = json.loads(details)
        return {"days": days}

    @staticmethod
    def select(connection, columns, stream, bounds=None, *, ordered=False):
        query = f"SELECT {columns} FROM records WHERE stream = ?"
        parameters = (stream,)
        if bounds is not None:
            query += " AND date BETWEEN ? AND ?"
            parameters += bounds
        if ordered:
            query += " ORDER BY date, sort_time, id"
        return connection.execute(query, parameters)

    @classmethod
    def summary(cls, connection, stream, bounds):
        if stream != "sleep":
            count, low, high, mean, total = cls.select(
                connection, "COUNT(value), MIN(value), MAX(value), AVG(value), TOTAL(value)", stream, bounds,
            ).fetchone()
            return {"count": count, "min": low, "max": high, "mean": mean, "total": total}
        count, seconds = cls.select(connection, "COUNT(*), TOTAL(value)", stream, bounds).fetchone()
        stages = defaultdict(float)
        for (serialized,) in cls.select(connection, "stage_seconds", stream, bounds):
            for stage, duration in json.loads(serialized).items():
                stages[stage] += duration
        return {
            "count": count,
            "mean_minutes": seconds / (60 * count) if count else None,
            "total_minutes": seconds / 60,
            "stage_minutes": {stage: duration / 60 for stage, duration in stages.items()},
        }

    @classmethod
    def records(cls, connection, stream, bounds, *, sleep_details=False):
        if stream == "sleep" and not sleep_details:
            return [
                {"id": record_id, "date": day, "value": value}
                for record_id, day, value in cls.select(connection, "id, date, value", stream, bounds, ordered=True)
            ]
        return [json.loads(payload) for (payload,) in cls.select(connection, "payload", stream, bounds, ordered=True)]

    @classmethod
    def series_for(cls, connection, stream, period, current, previous, *, sleep_details=False):
        all_time = cls.summary(connection, stream, None)
        aggregate = "TOTAL(value)" if stream in TOTAL_STREAMS else "AVG(value)"
        all_time["daily_mean"] = connection.execute(f"""
            SELECT AVG(day_value) FROM (
                SELECT {aggregate} AS day_value FROM records
                WHERE stream = ? AND value IS NOT NULL GROUP BY date
            )
        """, (stream,)).fetchone()[0]

        def window(bounds):
            records = cls.records(connection, stream, bounds, sleep_details=sleep_details)
            if bounds is None:
                start = records[0]["date"] if records else None
                end = records[-1]["date"] if records else None
                summary = all_time
            else:
                start, end = bounds
                summary = cls.summary(connection, stream, bounds)
            return {"start": start, "end": end, "records": records, "summary": summary}

        return {
            "stream": stream, "period": period, "current": window(current),
            "previous": window(previous) if previous is not None else None, "all_time": all_time,
        }

    def series(self, stream, period, end, *, sleep_details=False):
        current, previous = period_windows(period, end)
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN")
            if stream is not None:
                return self.series_for(connection, stream, period, current, previous, sleep_details=sleep_details)
            return {
                "period": period,
                "series": {name: self.series_for(connection, name, period, current, previous) for name in STREAMS},
            }

    @staticmethod
    def daily(connection, stream, bounds):
        aggregate = "TOTAL(value)" if stream in TOTAL_STREAMS else "AVG(value)"
        return [
            {"date": day, "value": value, "count": count}
            for day, value, count in connection.execute(f"""
                SELECT date, {aggregate}, COUNT(*) FROM records
                WHERE stream = ? AND date BETWEEN ? AND ? AND value IS NOT NULL
                GROUP BY date ORDER BY date
            """, (stream, *bounds))
        ]

    def overview(self, end):
        start = (end - 29 * ONE_DAY).isoformat()
        recent = (start, end.isoformat())
        result = {}
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN")
            for stream in STREAMS:
                days = self.daily(connection, stream, recent)
                row = connection.execute("""
                    SELECT date, value, payload FROM records
                    WHERE stream = ? AND value IS NOT NULL
                    ORDER BY date DESC, sort_time DESC, id DESC LIMIT 1
                """, (stream,)).fetchone()
                latest = None
                if row:
                    day, value, payload = row
                    if stream in TOTAL_STREAMS:
                        value = connection.execute("""
                            SELECT TOTAL(value) FROM records WHERE stream = ? AND date = ?
                        """, (stream, day)).fetchone()[0]
                    latest = {"date": day, "value": value}
                    if recorded_at := json.loads(payload).get("time"):
                        latest["time"] = recorded_at
                result[stream] = {"latest": latest, "days": days}
        return {"series": result}

    def sleep_on(self, day):
        bounds = (day, day)
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN")
            current, previous, mean = connection.execute("""
                WITH nights AS (
                    SELECT date, TOTAL(value) / 60 AS minutes FROM records
                    WHERE stream = 'sleep' AND date <= ? GROUP BY date
                )
                SELECT (SELECT minutes FROM nights WHERE date = ?),
                       (SELECT date FROM nights WHERE date < ? ORDER BY date DESC LIMIT 1),
                       (SELECT AVG(minutes) FROM nights WHERE date < ?)
            """, (day, day, day, day)).fetchone()
            return {
                "stream": "sleep", "date": day, "records": self.records(connection, "sleep", bounds, sleep_details=True),
                "summary": self.summary(connection, "sleep", bounds),
                "comparison": {
                    "minutes": current,
                    "previous_date": previous,
                    "previous_minutes": self.summary(connection, "sleep", (previous, previous))["total_minutes"] if previous else None,
                    "earlier_mean_minutes": mean,
                },
            }


class Handler(BaseHTTPRequestHandler):
    timeout = 30
    server_version = "MyLifeAnalytics"
    sys_version = ""

    def version_string(self):
        return self.server_version

    def log_message(self, _format, *_args):
        # Request paths, headers and bodies can be personal; don't put them in access logs.
        pass

    def respond(self, status, body, mime):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def json_response(self, status, payload):
        self.respond(status, json_text(payload).encode("utf-8"), "application/json; charset=utf-8")

    def send_error(self, code, message=None, explain=None):
        # Do not reflect client-supplied request text in protocol errors.
        self.json_response(code, {"error": HTTPStatus(code).phrase})

    def dispatch(self):
        if self.headers.get_all("Host") != [self.server.authority]:
            self.send_error(403)
            return
        try:
            try:
                target = urlsplit(self.path)
            except ValueError:
                raise BadRequest("invalid request path") from None
            if target.scheme or target.netloc or target.fragment:
                raise BadRequest("invalid request path")
            if self.command == "POST":
                self.import_usb(target)
            else:
                self.read(target)
        except BadRequest as error:
            self.json_response(400, {"error": str(error)})
        except UsbError as error:
            self.json_response(409, {"error": str(error)})
        except TimeoutError:
            self.json_response(408, {"error": "request timed out"})
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        except sqlite3.OperationalError as error:
            LOGGER.error("Analytics database operation failed (%s)", type(error).__name__)
            self.json_response(503, {"error": "База данных на ПК недоступна. Проверьте свободное место и права доступа к папке данных."})
        except (sqlite3.Error, OSError) as error:
            LOGGER.error("Analytics request failed (%s)", type(error).__name__)
            self.json_response(500, {"error": "Не удалось обработать данные на ПК. Проверьте свободное место и права доступа к папке сайта."})

    def do_GET(self):
        self.dispatch()

    def do_HEAD(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def import_usb(self, target):
        if target.path not in {"/v1/usb", "/v1/usb/start"}:
            self.send_error(404)
            return
        if self.headers.get_all("Origin") != [f"http://{self.server.authority}"]:
            self.send_error(403)
            return
        if (target.query or self.headers.get_all("Transfer-Encoding") or
                self.headers.get_all("Content-Length") not in (None, ["0"])):
            raise BadRequest("USB requests do not accept a query or body")
        if not self.server.import_lock.acquire(blocking=False):
            raise UsbError("Операция USB уже выполняется. Дождитесь её завершения и повторите попытку.")
        try:
            adb = self.server.adb
            adb.start()
            response = {}
            if target.path == "/v1/usb":
                response["count"] = read_usb(adb.executable, self.server.store)
                if warning := _sync_calendars(adb.executable, self.server.store):
                    response.update(partial=True, warning=warning)
                elif warning := adb.stop_after_commit():
                    response["warning"] = warning
        finally:
            self.server.import_lock.release()
        self.json_response(200, response)

    def read(self, target):
        asset = INDEX if target.path in {"/", "/index.html"} else None
        if match := BUNDLE_ASSET.fullmatch(target.path):
            asset = (target.path[1:], BUNDLE_MIME[match[1]])
        if asset:
            filename, mime = asset
            path = (self.server.site_root / filename).resolve()
            if not path.is_relative_to(self.server.site_root):
                self.send_error(404)
                return
            try:
                body = path.read_bytes()
            except FileNotFoundError:
                self.send_error(404)
                return
            self.respond(200, body, mime)
            return
        if target.path == "/v1/usb":
            status = {"app": "MyLifeAnalyticsUSB"}
            if error := self.server.adb.start_error:
                status["error"] = error
            self.json_response(200, status)
            return
        if target.path == "/v1/overview" and not target.query:
            self.json_response(200, self.server.store.overview(date.today()))
            return
        if target.path not in {"/v1/calendar", "/v1/series", "/v1/sleep"}:
            self.send_error(404)
            return
        allowed = {"period", "end", "date" if target.path == "/v1/sleep" else "stream"}
        try:
            query = parse_qs(target.query, keep_blank_values=True, strict_parsing=True, max_num_fields=3)
        except ValueError:
            raise BadRequest("invalid query parameters") from None
        if target.path == "/v1/calendar":
            if query.keys() != {"period"} or len(query["period"]) != 1:
                raise BadRequest("calendar requires one period: YYYY or YYYY-MM")
            self.json_response(200, self.server.store.calendar(query["period"][0]))
            return
        if not query.keys() <= allowed or any(len(values) != 1 for values in query.values()):
            raise BadRequest("unexpected or repeated query parameter")
        if target.path == "/v1/sleep" and "date" in query:
            if len(query) != 1:
                raise BadRequest("sleep date cannot be combined with period or end")
            day = exact_date(query["date"][0], "sleep date").isoformat()
            self.json_response(200, self.server.store.sleep_on(day))
            return
        stream = "sleep" if target.path == "/v1/sleep" else query.get("stream", [None])[0]
        if stream is not None and stream not in STREAMS:
            raise BadRequest("unknown stream")
        period = query.get("period", ["month"])[0]
        if period not in {"week", "all"} and period not in MONTHS_PER_PERIOD:
            raise BadRequest("unknown period")
        end = exact_date(query["end"][0], "end date") if "end" in query else date.today()
        self.json_response(200, self.server.store.series(stream, period, end, sleep_details=target.path == "/v1/sleep"))


class Server(ThreadingHTTPServer):
    def __init__(self, database, site_root, *, adb="adb", port=8786):
        self.site_root = site_root.resolve()
        self.adb = LocalAdb(adb)
        self.import_lock = Lock()
        self.store = Store(database)
        super().__init__(("127.0.0.1", port), Handler)
        self.authority = f"127.0.0.1:{self.server_port}"


def main():
    # All newly created files (including database journals) are owner-only on POSIX.
    os.umask(0o077)
    try:
        root = Path(__file__).resolve().parent
        site_root = root / "dist"
        if not (site_root / INDEX[0]).is_file():
            raise ValueError("dashboard build missing: run npm ci and npm run build")
        data = root / "data"
        data.mkdir(exist_ok=True)
        adb_file = root / "adb-path.txt"
        adb = os.environ.get("MYLIFE_ADB") or (adb_file.read_text(encoding="utf-8-sig").strip() if adb_file.is_file() else None)
        sdk = os.environ.get("ANDROID_HOME") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
        adb = adb or shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
        with Server(data / "analytics.db", site_root, adb=adb) as server:
            LOGGER.warning("MyLife analytics: http://%s/", server.authority)
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    except (ValueError, OSError, sqlite3.Error) as error:
        raise SystemExit(f"MyLife analytics could not start: {error}") from None


if __name__ == "__main__":
    main()
