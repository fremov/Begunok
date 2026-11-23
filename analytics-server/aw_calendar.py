"""Summarize local ActivityWatch history without retaining titles or full URLs."""
from collections import defaultdict
from contextlib import closing
from datetime import datetime, time, timedelta, timezone
from heapq import heappop, heappush
from http.client import HTTPConnection, HTTPException
from ipaddress import ip_address
from itertools import groupby
import json
from math import isfinite
import ntpath
import re
from urllib.parse import quote, urlencode, urlsplit


class ActivityUnavailable(Exception):
    """No authoritative activity calendar is available for this synchronization."""


_IDES = set('code code-insiders devenv studio studio64 idea idea64 pycharm pycharm64 '
            'webstorm64 rider64 clion64 goland64 rustrover64 phpstorm64 eclipse'.split())
_GAMES = set('cs2 csgo dota2 eldenring cyberpunk2077 witcher3 gta5 gta5_enhanced '
             'rdr2 terraria stardewmoddingapi stardewvalley factorio hl2 '
             'valorant-win64-shipping fortniteclient-win64-shipping minecraft.windows'.split())
_SITES = {
    'chatgpt.com': 'ai', 'chat.openai.com': 'ai',
    'practicum.yandex.ru': 'study', 'practicum.yandex.com': 'study',
    'practicum.yandex.com.tr': 'study', 'practicum.com': 'study',
    'youtube.com': 'leisure', 'youtu.be': 'leisure', 'vk.com': 'leisure', 'vk.ru': 'leisure',
}
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_US = timedelta(microseconds=1)
_WINDOW, _TAB, _AFK = range(3)


def _label(kind, data):
    if kind == _AFK:
        if data['status'] not in ('afk', 'not-afk'):
            raise ValueError
        return data['status']
    if kind == _WINDOW:
        name = ntpath.basename(data['app']).casefold()
        app = name.removesuffix('.exe')
        if app == 'chrome':
            return 'chrome'
        if not name or not name.isprintable() or any(c in '<>:"|?*' for c in name):
            return None
        if app in ('orca', 'chatgpt'):
            group = 'ai'
        elif app in _IDES:
            group = 'development'
        else:
            group = 'games' if app in _GAMES else 'other'
        return group, name
    # aw-watcher-web 0.6.0 copies Chrome's boolean tab.incognito into event.data.
    # Missing, null, numeric and textual flags do not confirm a public tab.
    if data.get('incognito') is not False:
        return None
    try:
        url = urlsplit(data.get('url', ''))
        host = (url.hostname or '').rstrip('.').encode('idna').decode('ascii').lower()
        port = url.port
        if url.scheme not in ('http', 'https') or not host:
            return None
        try:
            ip = ip_address(host)
        except ValueError:
            if len(host) > 253 or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part)
                                          for part in host.split('.')):
                return None
            loopback = host == 'localhost' or host.endswith('.localhost')
        else:
            if '%' in host:  # No IPv6 zone identifiers in labels.
                return None
            loopback = ip.is_loopback
            host = f'[{ip}]' if ip.version == 6 else str(ip)
    except (TypeError, ValueError, UnicodeError, AttributeError):
        return None
    name = f'{host}:{port}' if loopback and port is not None else host
    while host:
        if host in _SITES:
            return _SITES[host], name
        host = host.partition('.')[2]
    return 'other', name


def _get(path):
    # HTTPConnection connects to the literal endpoint, ignoring proxy settings and redirects.
    with closing(HTTPConnection('127.0.0.1', 5600, timeout=20)) as conn:
        conn.request('GET', '/api/0/' + path)
        with conn.getresponse() as response:
            if response.status != 200:
                raise ActivityUnavailable(f'ActivityWatch вернул HTTP {response.status}.')
            return json.load(response)


def _buckets():
    host = _get('info')['hostname'].casefold()
    buckets = [[], [], []]
    for name, meta in _get('buckets/').items():
        kind = meta['type']
        local = meta['hostname'].casefold() == host
        if kind == 'currentwindow' and local:
            buckets[_WINDOW].append(name)
        elif kind == 'afkstatus' and local:
            buckets[_AFK].append(name)
        elif kind == 'web.tab.current' and name.casefold().split('_', 1)[0] == 'aw-watcher-web-chrome':
            if local or (name == 'aw-watcher-web-chrome' and meta['hostname'] == 'unknown'):
                buckets[_TAB].append(name)
    if not all(buckets):
        raise ActivityUnavailable('Нет локальных журналов окон, Chrome или простоя ActivityWatch.')
    return buckets


def _events(bucket, **query):
    path = 'buckets/' + quote(bucket, safe='') + '/events?'
    rows = _get(path + urlencode(query))
    if not isinstance(rows, list):
        raise ValueError
    return rows


def _bounds(points, kind, rows, low, high):
    for row in rows:
        dt = datetime.fromisoformat(row['timestamp'].replace('Z', '+00:00'))
        duration = row['duration']
        if dt.tzinfo is None or not isfinite(duration) or duration < 0:
            raise ValueError
        stamp = (dt - _EPOCH) // _US
        end = min(stamp + round(duration * 1_000_000), high)
        start = max(stamp, low)
        if start < end:
            value = _label(kind, row['data'])
            # A private/unknown tab vetoes overlapping public events, like AFK.
            veto = value == 'afk' or (kind == _TAB and value is None)
            points.append((start, kind, (-veto, -stamp, -row['id'], end, len(points), value)))
            points.append((end, kind, None))


def _accumulate(points, totals):
    active = [[], [], []]
    previous = 0
    group = None
    midnight = 0
    day = None
    for stamp, changes in groupby(sorted(points, key=lambda point: point[0]), lambda point: point[0]):
        if group is not None:
            while previous < stamp:
                if day is None or previous >= midnight:
                    date = (_EPOCH + previous * _US).astimezone().date()
                    day = date.isoformat()
                    # Resolve *this* local midnight; tomorrow may have a different UTC offset.
                    next_day = datetime.combine(date + timedelta(days=1), time.min).astimezone()
                    midnight = (next_day - _EPOCH) // _US
                stop = min(midnight, stamp)
                counts = totals[day]
                if group != 'afk':
                    counts[group] += stop - previous
                previous = stop
        for _, kind, item in changes:
            if item is not None:
                heappush(active[kind], item)
        for heap in active:
            while heap and heap[0][3] <= stamp:
                heappop(heap)
        window, tab, afk = (heap[0][-1] if heap else None for heap in active)
        group = ('afk' if afk == 'afk' else
                 (tab or 'afk') if afk == 'not-afk' and window == 'chrome' else
                 window if afk == 'not-afk' else None)
        previous = stamp


def _collect(end):
    buckets = _buckets()
    totals = defaultdict(lambda: defaultdict(int))
    # Bounded time slices, not one HTTP request per day or one raw history-sized buffer.
    # Do not use bucket creation as a lower bound: imported events can predate it.
    while True:
        start = end - timedelta(days=7)
        low, high = (dt - _EPOCH for dt in (start, end))
        points = []
        for kind in (_AFK, _WINDOW, _TAB):
            for bucket in buckets[kind]:
                rows = _events(bucket, start=start.isoformat(), end=end.isoformat())
                _bounds(points, kind, rows, low // _US, high // _US)
                del rows
            if kind == _AFK and not points:
                break
        if points:
            _accumulate(points, totals)
        elif not any(_events(b, end=start.isoformat(), limit=1) for b in buckets[_AFK]):
            break
        end = start
    if not totals:
        raise ActivityUnavailable('В ActivityWatch пока нет наблюдений для календаря.')
    days, details = {}, {}
    for day, counts in totals.items():
        grouped = defaultdict(dict)
        for (group, name), us in counts.items():
            grouped[group][name] = us
        days[day], details[day] = {}, {}
        for group, items in grouped.items():
            seconds = sum(items.values()) // 1_000_000
            rounded = {name: us // 1_000_000 for name, us in items.items()}
            # Preserve the old floor-per-group total; distribute its remaining
            # seconds by descending fractional remainder, then name for ties.
            priority = sorted(items, key=lambda name: (-(items[name] % 1_000_000), name))
            for name in priority[:seconds - sum(rounded.values())]:
                rounded[name] += 1
            days[day][group] = seconds
            details[day][group] = [{'name': name, 'seconds': rounded[name]}
                                   for name in sorted(items, key=lambda n: (-rounded[n], n)) if rounded[name]]
        if not grouped:
            days[day]['other'] = 0
            details[day]['other'] = []
    return days, details


def collect_detailed():
    try:
        return _collect(datetime.now(timezone.utc))
    except (HTTPException, OSError, ValueError, KeyError, TypeError, AttributeError, OverflowError, RecursionError) as error:
        # Neither raw values nor parser/transport details belong in USB/UI messages or logs.
        if isinstance(error, (HTTPException, OSError)):
            message = 'Не удалось прочитать локальный ActivityWatch (порт 5600).'
        else:
            message = 'Некорректный ответ локального ActivityWatch.'
        raise ActivityUnavailable(message) from None


def collect() -> dict[str, dict[str, int]]:
    return collect_detailed()[0]
