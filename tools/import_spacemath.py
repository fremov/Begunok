"""Bundle the personally authorized Spacemath course, without site chrome or network code.

Development-only dependency: beautifulsoup4==4.15.0. Runtime needs no Python packages.
Run: python tools/import_spacemath.py --web-root D:/Dev/MyLifeWeb
All articles/resources are fetched and checked in staging before either package is replaced.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urljoin, urlsplit, unquote
from urllib.request import Request, urlopen
import time

from bs4 import BeautifulSoup, Comment

SOURCE = 'https://spacemath.xyz/'
CACHE = None
# The current source returns 404 for this diagram; the archived original is 280×128.
RECOVERED_SOURCE_URLS = {
    'https://spacemath.xyz/wp-content/uploads/2017/08/vzaimno-obratnye-operatsii-umnozhenie-i-delenie.png':
        'https://web.archive.org/web/20180815043732im_/http://spacemath.xyz/wp-content/uploads/2017/08/vzaimno-obratnye-operatsii-umnozhenie-i-delenie.png',
}


def fetch(url):
    cache_file = CACHE / (hashlib.sha256(url.encode()).hexdigest() + '.resource') if CACHE else None
    if cache_file is not None and cache_file.exists():
        mime, _, data = cache_file.read_bytes().partition(b'\n')
        return data, mime.decode('ascii')
    last_error = None
    for attempt in range(3):
        try:
            with urlopen(Request(RECOVERED_SOURCE_URLS.get(url, url), headers={'User-Agent': 'Mozilla/5.0 (personal offline course archive)'}), timeout=40) as response:
                data, mime = response.read(), response.headers.get_content_type()
                if cache_file is not None:
                    temporary = cache_file.with_suffix('.tmp')
                    temporary.write_bytes(mime.encode('ascii') + b'\n' + data)
                    temporary.replace(cache_file)
                return data, mime
        except Exception as error:
            last_error = error
            if attempt < 2:
                time.sleep(attempt + 1)
    raise RuntimeError(f'Cannot fetch required resource {url}: {last_error}') from last_error


def course_catalog():
    html, _ = fetch(SOURCE)
    soup = BeautifulSoup(html, 'html.parser')
    lessons = {}
    for anchor in soup.select('a[href]'):
        match = re.fullmatch(r'Шаг\s+(\d+)\.\s*(.+)', anchor.get_text(' ', strip=True))
        if not match:
            continue
        url = urljoin(SOURCE, anchor['href'])
        if urlsplit(url).netloc != 'spacemath.xyz':
            continue
        identifier = urlsplit(url).path.strip('/')
        if not re.fullmatch(r'[a-z0-9_-]+', identifier):
            raise ValueError(f'Unsupported lesson identifier: {identifier}')
        order = int(match[1])
        lessons[order] = {'id': identifier, 'order': order, 'title': match[2], 'url': url,
                          'localPath': f'lessons/{identifier}.html'}
    if not lessons or sorted(lessons) != list(range(1, max(lessons) + 1)):
        raise ValueError('The source course catalog is empty or has gaps')
    return {'source': SOURCE, 'title': 'Математика с нуля', 'lessons': [lessons[i] for i in sorted(lessons)]}


def remove_footer(entry):
    marker = next((p for p in entry.select('p') if p.get_text(' ', strip=True).startswith('Понравился урок')), None)
    if marker is not None:
        node = marker
        while node is not entry:
            for following in list(node.next_siblings):
                following.extract()
            parent = node.parent
            if node is marker:
                node.decompose()
            node = parent


def article(lesson):
    data, _ = fetch(lesson['url'])
    soup = BeautifulSoup(data, 'html.parser')
    entry = soup.select_one('.entry-content')
    if entry is None:
        raise ValueError(f'No article body in {lesson["url"]}')
    remove_footer(entry)
    unsupported = [str(tag)[:250] for tag in entry.select('iframe,video,audio,object,embed,form,input,canvas')
                   if not any(host in str(tag) for host in ('yoomoney.ru', 'vk.com', 'yandex'))]
    if unsupported:
        raise ValueError(f'Unsupported teaching media in {lesson["id"]}: {unsupported}')
    for tag in list(entry.select('script,iframe,object,embed,form,input,link,meta')):
        tag.decompose()
    for tag in list(entry.select('[id^="yandex"], [id^="vk_"]')):
        tag.decompose()
    for comment in entry.find_all(string=lambda text: isinstance(text, Comment)):
        comment.extract()
    for tag in entry.find_all(True):
        for key in list(tag.attrs):
            if key.lower().startswith('on') or key in ('srcdoc', 'target', 'ping'):
                del tag.attrs[key]
    answers = len(entry.select('a.show_hide'))
    for anchor in entry.select('a.show_hide'):
        selector = anchor.get('rel', '')
        if isinstance(selector, list):
            selector = ' '.join(selector)
        if not re.fullmatch(r'\.[A-Za-z_][A-Za-z0-9_-]*', selector) or not entry.select(selector):
            raise ValueError(f'Unresolved hidden solution in {lesson["id"]}: {selector!r}')
        anchor['data-answer'] = selector
        anchor['aria-expanded'] = 'false'
        for target in entry.select(selector):
            target['data-solution'] = 'true'
    return lesson, entry, answers


def collect_media(entry, base):
    urls = set()
    for image in entry.select('img'):
        source = image.get('data-src') or image.get('data-lazy-src') or image.get('src')
        if not source:
            raise ValueError(f'Image without source: {base}')
        if not source.startswith('data:'):
            source = urljoin(base, source)
            if urlsplit(source).scheme not in ('http', 'https'):
                raise ValueError(f'Unsafe media URL: {source}')
            urls.add(source)
        image['data-offline-source'] = source
    for element in entry.select('[style]'):
        for raw in re.findall(r'url\([\s\'"]*([^\)\'\"]+)[\s\'"]*\)', element['style']):
            if not raw.startswith('data:'):
                urls.add(urljoin(base, raw.strip()))
    return urls


def bundle(android_root, web_root):
    catalog = course_catalog()
    with ThreadPoolExecutor(max_workers=4) as pool:
        articles = list(pool.map(article, catalog['lessons']))
    assets = set()
    for lesson, entry, _ in articles:
        assets.update(collect_media(entry, lesson['url']))
    print(f'Fetched {len(articles)} complete articles; {len(assets)} required media resources', flush=True)
    with tempfile.TemporaryDirectory(prefix='mylife-spacemath-import-') as temporary:
        package = Path(temporary) / 'math'
        (package / 'media').mkdir(parents=True)
        (package / 'lessons').mkdir()
        def download_media(url):
            data, content_type = fetch(url)
            if not content_type.startswith('image/'):
                raise ValueError(f'Expected an image, got {content_type}: {url}')
            suffix = Path(unquote(urlsplit(url).path)).suffix.lower()
            if suffix not in ('.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.avif', '.bmp'):
                suffix = mimetypes.guess_extension(content_type) or '.bin'
            name = hashlib.sha256(url.encode()).hexdigest()[:32] + suffix
            (package / 'media' / name).write_bytes(data)
            return url, '../media/' + name
        media, failures = {}, []
        with ThreadPoolExecutor(max_workers=8) as pool:
            pending = {pool.submit(download_media, url): url for url in sorted(assets)}
            for future in as_completed(pending):
                try:
                    url, local = future.result()
                    media[url] = local
                except Exception as error:
                    failures.append(str(error))
                    print(str(error), flush=True)
        if failures:
            raise RuntimeError('Required source resources are missing:\\n' + '\\n'.join(failures))
        known = {urlsplit(lesson['url']).path.rstrip('/'): lesson for lesson in catalog['lessons']}
        report = []
        for lesson, entry, answers in articles:
            before = entry.get_text()
            for image in entry.select('img'):
                source = image.attrs.pop('data-offline-source', None)
                image['src'] = source if source.startswith('data:') else media[source]
                for attr in list(image.attrs):
                    if attr.startswith('data-') or attr in ('srcset', 'sizes'):
                        del image.attrs[attr]
                image['loading'] = 'lazy'
            for element in entry.select('[style]'):
                style = element['style']
                for remote, local in media.items():
                    style = style.replace(remote, local)
                if re.search(r'url\([^)]*(?:https?:|//)', style, re.I):
                    raise ValueError(f'Unresolved inline media in {lesson["id"]}')
                element['style'] = style
            for anchor in list(entry.select('a[href]')):
                href = anchor['href']
                if anchor.has_attr('data-answer') or href.startswith('#'):
                    continue
                full = urljoin(lesson['url'], href)
                parsed = urlsplit(full)
                linked = known.get(parsed.path.rstrip('/')) if parsed.hostname == 'spacemath.xyz' else None
                if linked is not None:
                    anchor['href'] = ('#' + parsed.fragment if linked['id'] == lesson['id'] and parsed.fragment
                                      else linked['id'] + '.html' + ('#' + parsed.fragment if parsed.fragment else ''))
                    if linked['id'] != lesson['id'] or not parsed.fragment:
                        anchor['data-lesson-id'] = linked['id']
                else:
                    anchor.unwrap()
            for source in list(entry.select('picture source')):
                source.decompose()
            if before != entry.get_text():
                raise ValueError(f'Educational text changed while rewriting assets: {lesson["id"]}')
            for element in entry.select('[src], [href], [srcset]'):
                for attr in ('src', 'href', 'srcset'):
                    value = element.get(attr, '')
                    if value and re.match(r'(?:https?:)?//|javascript:|file:|content:', value, re.I):
                        raise ValueError(f'Unresolved external reference: {lesson["id"]}: {value}')
            title = BeautifulSoup('', 'html.parser').new_tag('h1')
            title.string = f'{lesson["order"]}. {lesson["title"]}'
            html = (
                '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
                '<meta name="viewport" content="width=device-width,initial-scale=1"><title>'
                + str(title.string).replace('&', '&amp;').replace('<', '&lt;')
                + '</title><link rel="icon" href="../favicon.svg"><link rel="stylesheet" href="../lesson.css"><script src="../lesson.js" defer></script>'
                '</head><body><main>' + str(title) + str(entry) + '</main></body></html>'
            )
            (package / lesson['localPath']).write_text(html, encoding='utf-8')
            report.append({'id': lesson['id'], 'textCharacters': len(before), 'images': len(entry.select('img')), 'solutions': answers})
        (package / 'spacemath.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        source_tools = Path(__file__).parent
        for filename in ('lesson.css', 'lesson.js', 'favicon.svg'):
            shutil.copyfile(source_tools / filename, package / filename)
        for destination in (android_root / 'app/src/main/assets/math', web_root / 'public/math'):
            old = destination.with_name(destination.name + '-previous')
            if old.exists():
                raise ValueError(f'Remove or inspect leftover staging directory first: {old}')
            staging = destination.with_name(destination.name + '-staging')
            if staging.exists():
                shutil.rmtree(staging)
            shutil.copytree(package, staging)
            if destination.exists():
                destination.rename(old)
            staging.rename(destination)
            if old.exists():
                shutil.rmtree(old)
        print(json.dumps({'lessons': len(articles), 'media': len(media), 'images': sum(r['images'] for r in report),
                          'solutions': sum(r['solutions'] for r in report), 'textCharacters': sum(r['textCharacters'] for r in report),
                          'bytes': sum(p.stat().st_size for p in package.rglob('*') if p.is_file()), 'chapters': report}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web-root', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path, default=Path(os.environ.get('LOCALAPPDATA', tempfile.gettempdir())) / 'MyLifeWeb/spacemath-import-cache')
    args = parser.parse_args()
    CACHE = args.cache_dir.resolve()
    CACHE.mkdir(parents=True, exist_ok=True)
    bundle(Path(__file__).resolve().parents[1], args.web_root.resolve())
