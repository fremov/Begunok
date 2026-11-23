import json
from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlsplit

import aw_calendar as aw


# Derive the fixture boundary in the host time zone (the collector's calendar zone).
MIDNIGHT = datetime(2025, 2, 12).astimezone(timezone.utc)
TODAY = '2025-02-12'
WINDOW = 'aw-watcher-window_PC'
TAB = 'aw-watcher-web-chrome_PC'
AFK = 'aw-watcher-afk_PC'
BUCKETS = {WINDOW: {'type': 'currentwindow', 'hostname': 'PC'},
           TAB: {'type': 'web.tab.current', 'hostname': 'PC'},
           AFK: {'type': 'afkstatus', 'hostname': 'PC'},
           'aw-watcher-web-firefox_PC': {'type': 'web.tab.current', 'hostname': 'PC'},
           'aw-watcher-window_other': {'type': 'currentwindow', 'hostname': 'other'}}


def event(offset, duration, **data):
    return {'timestamp': (MIDNIGHT + timedelta(seconds=offset)).isoformat(), 'id': round(offset * 1000),
            'duration': duration, 'data': {**data, 'title': 'private title'}}


def collect_fixture(events, end=MIDNIGHT + timedelta(days=1), buckets=BUCKETS, detailed=False):
    def get(path):
        if path == 'info':
            return {'hostname': 'pc'}
        if path == 'buckets/':
            return buckets
        url = urlsplit(path)
        bucket = url.path.split('/')[1]
        rows = events[bucket]  # An unexpected source must not silently contribute time.
        params = parse_qs(url.query)
        low = datetime.fromisoformat(params['start'][0]) if 'start' in params else None
        high = datetime.fromisoformat(params['end'][0])
        result = []
        for row in reversed(rows):
            ts = datetime.fromisoformat(row['timestamp'])
            stop = ts + timedelta(seconds=row['duration'])
            if ts <= high and (low is None or low <= stop):
                result.append(row)
        return result[:int(params.get('limit', [len(result)])[0])]
    with patch.object(aw, '_get', side_effect=get):
        result = aw._collect(end)
        return result if detailed else result[0]


class ActivityCalendarTests(unittest.TestCase):
    def test_latest_foreground_interval_wins_and_afk_vetoes_duplicates(self):
        events = {WINDOW: [event(0, 120, app='chrome.exe'), event(30, 30, app='devenv.exe'),
                           event(70, 10, app=r'C:\Games\CS2.EXE'), event(90, 10, app='Orca.exe')],
                  TAB: [event(0, 150, incognito=False, url='https://youtube.com/watch?v=private'),
                        event(20, 50, incognito=False, url='https://chat.openai.com/private'),
                        event(95, 15, incognito=False, url='https://practicum.yandex.ru/private')],
                  AFK: [event(0, 120, status='not-afk'), event(10, 5, status='afk'),
                        event(11, 110, status='not-afk')]}
        days, details = collect_fixture(events, detailed=True)
        self.assertEqual(days, {TODAY: {'leisure': 35, 'ai': 30, 'development': 30,
                                       'games': 10, 'study': 10}})
        self.assertEqual(details[TODAY], {
            'leisure': [{'name': 'youtube.com', 'seconds': 35}],
            'ai': [{'name': 'chat.openai.com', 'seconds': 20}, {'name': 'orca.exe', 'seconds': 10}],
            'development': [{'name': 'devenv.exe', 'seconds': 30}],
            'games': [{'name': 'cs2.exe', 'seconds': 10}],
            'study': [{'name': 'practicum.yandex.ru', 'seconds': 10}]})

    def test_midnight_fractions_and_gaps_across_history(self):
        old = -28 * 24 * 3600
        events = {WINDOW: [event(old, 12, app='orca.exe'), event(-10, 20, app='chrome.exe'),
                           event(15, .58, app='steam.exe'), event(18, .58, app='steam.exe')],
                  TAB: [event(-10, 20, incognito=False, url='https://chatgpt.com/private')],
                  AFK: [event(old, 12, status='not-afk'), event(-10, 35, status='not-afk')]}
        self.assertEqual(collect_fixture(events), {'2025-01-15': {'ai': 12},
                                                   '2025-02-11': {'ai': 10}, TODAY: {'ai': 10, 'other': 1}})

    def test_sliced_events_do_not_duplicate_time(self):
        events = {WINDOW: [event(-5, 20, app='chrome.exe')],
                  TAB: [event(-5, 20, incognito=False, url='https://example.org/a')],
                  AFK: [event(-7, 30, status='not-afk')]}
        self.assertEqual(collect_fixture(events, MIDNIGHT + timedelta(days=7)),
                         {'2025-02-11': {'other': 5}, TODAY: {'other': 15}})

    def test_idle_days_observed_missing_days_absent(self):
        events = {WINDOW: [], TAB: [], AFK: [event(1, 75, status='afk'),
                                               event(-86400, 15, status='not-afk')]}
        self.assertEqual(collect_fixture(events), {TODAY: {'other': 0}})
        with self.assertRaises(aw.ActivityUnavailable):
            collect_fixture({WINDOW: [], TAB: [], AFK: []})
        with self.assertRaises(aw.ActivityUnavailable):
            collect_fixture(events, buckets={k: v for k, v in BUCKETS.items() if k != AFK})

    def test_domain_boundaries_and_unknown_apps(self):
        for url, group in [('https://M.VK.COM./x', 'leisure'), ('https://chatgpt.com', 'ai'),
                           ('https://chatgpt.com.example.net', 'other'),
                           ('https://youtube.com@example.net', 'other'),
                           ('https://practicum.yandex.ru.example', 'other')]:
            with self.subTest(url=url):
                self.assertEqual(aw._label(aw._TAB, {'incognito': False, 'url': url})[0], group)
        for url in ('chrome://youtube.com', 'https://[invalid', 'https://a%2fsecret.example'):
            self.assertIsNone(aw._label(aw._TAB, {'incognito': False, 'url': url}))
        self.assertEqual(aw._label(aw._WINDOW, {'app': 'steam.exe', 'title': 'cs2.exe'}), ('other', 'steam.exe'))
        self.assertEqual(aw._label(aw._WINDOW, {'app': 'studio64.exe'}), ('development', 'studio64.exe'))
        for app in (r'C:\Program Files\Orca\ORCA.EXE', r'C:\Apps\ChatGPT.exe'):
            with self.subTest(app=app):
                self.assertEqual(aw._label(aw._WINDOW, {'app': app})[0], 'ai')

    def test_chrome_requires_explicit_public_event_and_vetoes_overlaps(self):
        url = 'https://example.com:8888/path?token=secret#fragment'
        for fields in ({}, {'incognito': True}, {'incognito': None}, {'incognito': 0},
                       {'incognito': 'false'}):
            with self.subTest(fields=fields):
                events = {WINDOW: [event(0, 40, app='chrome.exe')],
                          TAB: [event(0, 20, url=url, **fields), event(10, 20, url=url, incognito=False)],
                          AFK: [event(0, 40, status='not-afk')]}
                self.assertEqual(collect_fixture(events, detailed=True),
                                 ({TODAY: {'other': 10}},
                                  {TODAY: {'other': [{'name': 'example.com', 'seconds': 10}]}}))

    def test_loopback_ports_only_and_deterministic_rounding(self):
        for host in ('localhost', '127.0.0.2', '[::1]'):
            data = {'incognito': False, 'url': f'http://user:pass@{host}:3456/a?b=c#d'}
            self.assertEqual(aw._label(aw._TAB, data), ('other', f'{host}:3456'))
        events = {WINDOW: [event(i, .75, app=name) for i, name in enumerate(('z.exe', 'a.exe', 'b.exe'))],
                  TAB: [], AFK: [event(0, 3, status='not-afk')]}
        self.assertEqual(collect_fixture(events, detailed=True),
                         ({TODAY: {'other': 2}}, {TODAY: {'other': [
                             {'name': 'a.exe', 'seconds': 1}, {'name': 'b.exe', 'seconds': 1}]}}))

    def test_failed_reads_never_return_partial_days_or_private_error_text(self):
        with patch.object(aw, '_get', side_effect=[{'hostname': 'pc'}, BUCKETS,
                          [event(0, 12, status='not-afk')], OSError('private title/URL')]):
            with self.assertRaises(aw.ActivityUnavailable) as err:
                aw.collect()
        self.assertNotIn('private', str(err.exception))
        with patch.object(aw, '_get', side_effect=json.JSONDecodeError('private', '', 0)):
            with self.assertRaises(aw.ActivityUnavailable) as err:
                aw.collect()
        self.assertNotIn('private', str(err.exception))

    def test_redirect_is_an_error_and_never_followed(self):
        conn = MagicMock()
        response = conn.getresponse.return_value.__enter__.return_value
        response.status = 302
        with patch.object(aw, 'HTTPConnection', return_value=conn) as connect:
            with self.assertRaises(aw.ActivityUnavailable):
                aw.collect()
            connect.assert_called_once_with('127.0.0.1', 5600, timeout=20)
        conn.request.assert_called_once_with('GET', '/api/0/info')
        response.read.assert_not_called()


if __name__ == '__main__':
    unittest.main()
