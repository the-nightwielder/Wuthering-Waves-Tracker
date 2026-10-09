#!/usr/bin/env python3
"""WuWa Tracker v1.3 free structured intelligence pipeline.

No paid X API is required. X discovery uses public pages indexed by Google News RSS,
while Reddit's public RSS feed is used for the leak community. All extracted claims are
source-attributed and confidence-scored; leaks never become official automatically.
"""
import hashlib, html, json, os, re
from collections import defaultdict
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import quote_plus, urljoin

import feedparser
import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent
SOURCES = json.loads((ROOT / "sources.json").read_text())['sources']
OUT = ROOT / "feed.json"
UA = "WuWaTrackerDataBot/1.3 (+https://github.com/)"
TIMEOUT = 20
session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

WUWA_TERMS = re.compile(r"wuthering\s+waves|wuwa|resonator|astrite|lunite|convene|tower of adversity|endstate matrix|whimpering wastes|banner|tacet field|phantom", re.I)
GAME_CONTEXT = re.compile(r"wuthering\s+waves|\bwuwa\b", re.I)
REDDIT_TITLE_CONTEXT = re.compile(r"wuthering\s+waves|\bwuwa\b|resonator|banner|convene|\bversion\s*\d|\bv\d+\.\d+", re.I)
LEAK_TERMS = re.compile(r"\b(leak|leaks|leaked|beta|datamine|datamined|test client|stc|subject to change|rumou?r|unconfirmed|sus|drip marketing leak)\b", re.I)
OFFICIAL_TERMS = re.compile(r"official|version|maintenance|special program|special report|resonator reveal|profile|update notice|preview|event notice", re.I)
VERSION_RE = re.compile(r"\b(?:(?:version|ver\.?|v)\s*|wuthering\s+waves\s*)(\d+\.\d+)\b", re.I)
DATE_RE = re.compile(r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+([0-3]?\d)(?:st|nd|rd|th)?,?\s+(20\d{2})\b", re.I)
DATE_RE_DMY = re.compile(r"\b([0-3]?\d)[/-](1[0-2]|0?[1-9])[/-](20\d{2})\b")
TIME_RE = re.compile(r"\b(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*(?:UTC)?\s*([+-]\d{1,2})?\b", re.I)
PHASE_RE = re.compile(r"\bphase\s*([12])\b", re.I)
BANNER_TERMS = re.compile(r"banner|convene|rerun|rate[- ]up|featured", re.I)
RESONATOR_TERMS = re.compile(r"resonator|5-star|4-star|\bcharacter\b", re.I)
ELEMENTS = ["Aero", "Fusion", "Glacio", "Electro", "Havoc", "Spectro"]
WEAPONS = ["Sword", "Broadblade", "Pistols", "Gauntlets", "Rectifier"]


def clean_text(value):
    if not value:
        return ""
    return re.sub(r"\s+", " ", BeautifulSoup(html.unescape(str(value)), "html.parser").get_text(" ")).strip()


def parse_date(entry):
    for key in ("published", "updated", "created"):
        value = entry.get(key)
        if value:
            try:
                return parsedate_to_datetime(value).astimezone(timezone.utc).isoformat()
            except Exception:
                pass
        parsed = entry.get(key + "_parsed")
        if parsed:
            try:
                return datetime(*parsed[:6], tzinfo=timezone.utc).isoformat()
            except Exception:
                pass
    return datetime.now(timezone.utc).isoformat()


def status_for(source, title, summary):
    text = f"{title} {summary}"
    if source['category'] == 'official':
        return "OFFICIAL", 0.98
    if source['category'] == 'leak-community' or LEAK_TERMS.search(text):
        return "LEAK", 0.52
    return "COMMUNITY", 0.68


def make_item(source, title, url, summary="", published=None, publisher=""):
    title, summary = clean_text(title), clean_text(summary)
    if not title or not url or not WUWA_TERMS.search(f"{title} {summary}"):
        return None
    if not GAME_CONTEXT.search(f"{title} {summary}"):
        return None
    if source['kind'] == 'rss' and not REDDIT_TITLE_CONTEXT.search(title):
        return None
    if 'megathread' in title.lower() and not REDDIT_TITLE_CONTEXT.search(title.replace('megathread', '')):
        return None
    status, confidence = status_for(source, title, summary)
    if source['id'].startswith('x-'):
        source_type = 'X · indexed by Google News'
    elif source['kind'] == 'rss':
        source_type = 'Reddit'
    elif source['category'] == 'official':
        source_type = 'Official website'
    else:
        source_type = 'Website'
    return {
        "id": hashlib.sha256(url.encode()).hexdigest()[:20],
        "title": title[:240], "url": url, "summary": summary[:1200],
        "publishedAt": published or datetime.now(timezone.utc).isoformat(),
        "sourceId": source['id'], "sourceCategory": source['category'],
        "sourceType": source_type, "sourceName": clean_text(publisher)[:100] or source['id'],
        "status": status, "confidence": confidence, "priority": source.get('priority', 50)
    }


def google_news(source):
    url = "https://news.google.com/rss/search?q=" + quote_plus(source['query']) + "&hl=en-US&gl=US&ceid=US:en"
    r = session.get(url, timeout=TIMEOUT); r.raise_for_status()
    feed = feedparser.parse(r.content)
    for e in feed.entries[:60]:
        source_info = e.get('source') or {}
        publisher = source_info.get('title', '') if isinstance(source_info, dict) else ''
        yield make_item(source, e.get('title'), e.get('link'), e.get('summary'), parse_date(e), publisher)


def rss(source):
    r = session.get(source['url'], timeout=TIMEOUT); r.raise_for_status()
    feed = feedparser.parse(r.content)
    for e in feed.entries[:60]:
        yield make_item(source, e.get('title'), e.get('link'), e.get('summary'), parse_date(e))


def html_page(source):
    r = session.get(source['url'], timeout=TIMEOUT); r.raise_for_status()
    soup = BeautifulSoup(r.text, 'html.parser'); seen = set()
    for a in soup.find_all('a', href=True):
        title = clean_text(a.get_text(' ', strip=True)); href = urljoin(r.url, a['href'])
        if not title or href in seen or len(title) < 8:
            continue
        seen.add(href)
        item = make_item(source, title, href)
        if item:
            yield item


def iso_utc(year, month, day, hour=0, minute=0):
    try:
        return datetime(year, month, day, hour, minute, tzinfo=timezone.utc).isoformat().replace('+00:00', 'Z')
    except ValueError:
        return None


def date_mentions(text):
    out = []
    months = {m.lower(): i for i, m in enumerate(__import__('calendar').month_name) if m}
    for m in DATE_RE.finditer(text):
        month_word = m.group(0).split()[0].lower().rstrip(',')
        month = next((v for k, v in months.items() if k.startswith(month_word[:3])), None)
        if month:
            value = iso_utc(int(m.group(2)), month, int(m.group(1)))
            if value: out.append((m.start(), m.end(), value, m.group(0)))
    for m in DATE_RE_DMY.finditer(text):
        value = iso_utc(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        if value: out.append((m.start(), m.end(), value, m.group(0)))
    return sorted(out)


def context(text, start, end, radius=180):
    return text[max(0, start-radius):min(len(text), end+radius)]


def claim_kind(title, summary):
    text = f"{title} {summary}"
    if BANNER_TERMS.search(text): return "banner"
    if VERSION_RE.search(text): return "version"
    if re.search(r"tower of adversity|endstate matrix|whimpering wastes", text, re.I): return "endgame"
    if RESONATOR_TERMS.search(text): return "resonator"
    return "event"


def extract_entities(items):
    versions, banners, resonators, events = {}, {}, {}, {}
    for item in items:
        text = f"{item['title']} — {item['summary']}"
        kind = claim_kind(item['title'], item['summary'])
        dates = date_mentions(text)
        vm = VERSION_RE.search(text)
        phase = PHASE_RE.search(text)
        version = vm.group(1) if vm else None
        evidence = {"sourceId": item['sourceId'], "sourceType": item.get('sourceType', 'Website'), "sourceName": item.get('sourceName', item['sourceId']), "url": item['url'], "status": item['status'], "confidence": item['confidence'], "publishedAt": item['publishedAt']}

        if version:
            key = version
            rec = versions.setdefault(key, {"id": f"version-{version}", "version": version, "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
            rec['sources'].append(evidence)
            rec['claims'].append(item['title'])
            if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)

        if kind == 'banner':
            bkey = re.sub(r"[^a-z0-9]+", "-", item['title'].lower()).strip('-')[:90]
            rec = banners.setdefault(bkey, {"id": f"banner-{hashlib.sha1(bkey.encode()).hexdigest()[:12]}", "title": item['title'], "version": version, "phase": int(phase.group(1)) if phase else None, "startAt": None, "endAt": None, "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
            rec['sources'].append(evidence); rec['claims'].append(item['title'])
            if dates:
                rec['startAt'] = rec['startAt'] or dates[0][2]
                if len(dates) > 1: rec['endAt'] = dates[-1][2]
            if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)

        # Conservative resonator extraction: only use proper-name-like tokens from
        # phrases around "Resonator"/"5-star" and never claim a leak is confirmed.
        if RESONATOR_TERMS.search(text):
            patterns = [r"(?:new|upcoming|leaked|rumored|featured)\s+(?:5[- ]star|4[- ]star)?\s*(?:resonator|character)?\s*[:\-]?\s*([A-Z][A-Za-z]{2,18})",
                        r"\b([A-Z][A-Za-z]{2,18})\s+(?:resonator|banner|rerun)\b"]
            for pat in patterns:
                m = re.search(pat, text)
                if not m: continue
                name = m.group(1)
                if name.lower() in {'wuthering','waves','resonator','character','version','banner','phase'}: continue
                key = name.lower()
                rec = resonators.setdefault(key, {"id": f"resonator-{hashlib.sha1(key.encode()).hexdigest()[:12]}", "name": name, "version": version, "phase": int(phase.group(1)) if phase else None, "element": next((e for e in ELEMENTS if re.search(e, context(text,m.start(),m.end()), re.I)), None), "weapon": next((w for w in WEAPONS if re.search(w, context(text,m.start(),m.end()), re.I)), None), "status": item['status'], "confidence": item['confidence'], "sources": [], "claims": []})
                rec['sources'].append(evidence); rec['claims'].append(item['title'])
                if item['status'] == 'OFFICIAL': rec['status'] = 'OFFICIAL'; rec['confidence'] = max(rec['confidence'], 0.98)
                break

        if dates and kind in ('event','endgame'):
            key = hashlib.sha1((item['url'] + dates[0][2]).encode()).hexdigest()[:16]
            rec = events.setdefault(key, {"id": f"event-{key}", "title": item['title'], "type": 'Endgame' if kind == 'endgame' else 'Event', "version": version, "startAt": dates[0][2], "endAt": dates[-1][2] if len(dates)>1 else None, "status": item['status'], "confidence": item['confidence'], "sourceUrl": item['url'], "sources": [], "claims": []})
            rec['sources'].append(evidence); rec['claims'].append(item['title'])

    return list(versions.values()), list(banners.values()), list(resonators.values()), list(events.values())


def merge_by_entity(records):
    for r in records:
        # Evidence from multiple independent sources increases confidence, but never
        # above 0.98 unless an official source is present.
        unique_sources = {s['sourceId'] for s in r.get('sources', [])}
        if r.get('status') != 'OFFICIAL' and len(unique_sources) >= 3:
            r['confidence'] = min(0.90, r['confidence'] + 0.10)
        elif r.get('status') != 'OFFICIAL' and len(unique_sources) >= 2:
            r['confidence'] = min(0.82, r['confidence'] + 0.06)
        r['sources'] = list({s['url']: s for s in r.get('sources', [])}.values())[:8]
        r['sourceUrls'] = [s['url'] for s in r['sources']]
        r['claims'] = list(dict.fromkeys(r.get('claims', [])))[:8]
    return records



def snapshot_date(value, end_of_day=False):
    try:
        day = datetime.strptime(value.strip(), "%B %d, %Y").replace(tzinfo=timezone.utc)
        if end_of_day:
            day = day.replace(hour=23, minute=59, second=59)
        return day.isoformat().replace("+00:00", "Z")
    except ValueError:
        return None


_FANDOM_IMAGE_CACHE = {}
def fandom_image(name):
    key = name.strip().lower()
    if not key:
        return None
    if key in _FANDOM_IMAGE_CACHE:
        return _FANDOM_IMAGE_CACHE[key]
    image = None
    try:
        response = session.get("https://wutheringwaves.fandom.com/api.php", params={
            "action": "query", "titles": name, "prop": "pageimages", "format": "json",
            "pithumbsize": 640, "redirects": 1
        }, timeout=TIMEOUT)
        response.raise_for_status()
        pages = response.json().get("query", {}).get("pages", {})
        page = next(iter(pages.values()), {})
        image = page.get("thumbnail", {}).get("source")
        if image and not image.startswith("https://"):
            image = None
    except Exception:
        pass
    _FANDOM_IMAGE_CACHE[key] = image
    return image


def banner_snapshot(source):
    r = session.get(source["url"], timeout=TIMEOUT)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    image_labels = " ".join(img.get("alt", "") for img in soup.find_all("img"))
    text = clean_text(soup.get_text(" ", strip=True) + " " + image_labels)
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    url = r.url
    if source.get("snapshotType") == "current":
        current = re.search(r"Current WuWa banner\s*:\s*([^\.]+?)\.\s*Banner", text, re.I)
        version = VERSION_RE.search(text)
        phase = PHASE_RE.search(text)
        start = end = None
        for row in soup.find_all("tr"):
            cells = [clean_text(cell.get_text(" ", strip=True)) for cell in row.find_all(["td", "th"])]
            if cells and "Live banner phase" in cells[0] and len(cells) > 2:
                phase_match = PHASE_RE.search(" ".join(cells[:2]))
                phase = phase_match or phase
                dates = re.findall(r"(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},\s+20\d{2}", cells[2], re.I)
                if len(dates) >= 2:
                    start = snapshot_date(dates[0])
                    end = snapshot_date(dates[1], True)
                break
        title = clean_text(current.group(1)).rstrip(".") if current else ""
        if not title:
            return None
        weapon_match = re.search(r"(?:featured|signature)\s+weapon(?:\s+banner)?\s*(?:is|:|—|-)\s*([A-Z][A-Za-z0-9'’ -]{2,40})", text, re.I)
        weapon = clean_text(weapon_match.group(1)).rstrip(" .") if weapon_match else None
        resonators = [name.strip() for name in re.split(r",|\band\b", title) if name.strip()]
        return {
            "title": title, "version": version.group(1) if version else None,
            "phase": int(phase.group(1)) if phase else None, "startAt": start, "endAt": end,
            "weapon": weapon, "status": "COMMUNITY", "confidence": 0.85,
            "resonatorImages": {name: image for name in resonators if (image := fandom_image(name))},
            "weaponImages": {weapon: image} if weapon and (image := fandom_image(weapon)) else {},
            "sourceLabel": "WuWa Banners", "sourceUrls": [url], "snapshotAt": now
        }
    heading = next((clean_text(h.get_text(" ", strip=True)) for h in soup.find_all(["h1", "h2", "h3"]) if "countdown" in h.get_text(" ", strip=True).lower()), "")
    match = re.search(r"([A-Z][A-Za-z]+(?:,\s*[A-Z][A-Za-z]+)*(?:,?\s+and\s+[A-Z][A-Za-z]+)?)\s+Banner Countdown", heading, re.I)
    release = re.search(r"is set to release (?:alongside .+? )?on\s+([A-Z][a-z]+\s+\d{1,2},\s+20\d{2})", text, re.I)
    title = clean_text(match.group(1)).replace(" and ", ", ") if match else ""
    if not title:
        return None
    version = VERSION_RE.search(text)
    phase_match = re.search(r"Phase\s*(?:II|2|I|1)", text, re.I)
    phase = 2 if phase_match and phase_match.group(0).lower().endswith(("ii", "2")) else (1 if phase_match else None)
    start = snapshot_date(release.group(1)) if release else None
    resonators = [name.strip() for name in re.split(r",|\band\b", title) if name.strip()]
    weapon_match = re.search(r"(?:featured|signature)\s+weapon(?:\s+banner)?\s*(?:is|:|—|-)\s*([A-Z][A-Za-z0-9'’ -]{2,40})", text, re.I)
    weapon = clean_text(weapon_match.group(1)).rstrip(" .") if weapon_match else None
    return {
        "title": title, "version": version.group(1) if version else None,
        "phase": phase, "startAt": start, "endAt": None, "weapon": weapon,
        "resonatorImages": {name: image for name in resonators if (image := fandom_image(name))},
        "weaponImages": {weapon: image} if weapon and (image := fandom_image(weapon)) else {},
        "status": "COMMUNITY", "confidence": 0.78,
        "sourceLabel": "GenGamer Countdown", "sourceUrls": [url], "snapshotAt": now
    }
def main():
    items, errors, snapshots = [], [], []
    for source in SOURCES:
        try:
            if source['kind'] == 'banner_snapshot':
                snapshot = banner_snapshot(source)
                if snapshot:
                    snapshots.append(snapshot)
                continue
            fn = {'google_news': google_news, 'rss': rss, 'html': html_page}[source['kind']]
            for item in fn(source):
                if item: items.append(item)
        except Exception as exc:
            errors.append({"sourceId": source['id'], "error": str(exc)[:300]})

    # Deduplicate URLs first, then near-identical titles.
    by_url = {}
    for item in items:
        old = by_url.get(item['url'])
        if old is None or (item['priority'], item['publishedAt']) > (old['priority'], old['publishedAt']):
            by_url[item['url']] = item
    by_title = {}
    for item in sorted(by_url.values(), key=lambda z: (z['priority'], z['publishedAt']), reverse=True):
        key = re.sub(r"[^a-z0-9]", "", item['title'].lower())[:140]
        by_title.setdefault(key, item)
    final = sorted(by_title.values(), key=lambda z: z['publishedAt'], reverse=True)[:300]

    versions, banners, resonators, events = extract_entities(final)
    versions, banners, resonators, events = [merge_by_entity(x) for x in (versions, banners, resonators, events)]
    versions.sort(key=lambda x: tuple(map(int, x['version'].split('.'))), reverse=True)

    payload = {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "refreshSeconds": 1800,
        "latestVersion": next((v['version'] for v in versions if v.get('status') == 'OFFICIAL'), versions[0]['version'] if versions else None),
        "news": final,
        "versions": versions[:30],
        "banners": banners[:80],
        "scheduleSnapshots": snapshots,
        # Keep the complete evidence-bearing collection and also expose convenient
        # lifecycle buckets for clients that want a direct active/upcoming view.
        "activeBanners": [b for b in banners if b.get("startAt") and b.get("endAt") and b["startAt"] <= datetime.now(timezone.utc).isoformat() <= b["endAt"]][:40],
        "upcomingBanners": [b for b in banners if b.get("startAt") and b["startAt"] > datetime.now(timezone.utc).isoformat()][:40],
        "resonators": resonators[:100],
        "upcomingResonators": [r for r in resonators if r.get("version") or r.get("phase")][:60],
        "events": events[:120],
        "sourceHealth": errors,
        "policy": {
            "officialIsAuthoritative": True,
            "leaksAreUnconfirmed": True,
            "multiSourceRaisesConfidenceButDoesNotConfirmLeaks": True,
            "inGameTimerIsFinalAuthority": True,
            "xApiRequired": False,
            "freeXStrategy": "public X pages indexed by Google News RSS; no paid X API token"
        }
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"wrote {len(final)} news; {len(versions)} versions; {len(banners)} banners; {len(resonators)} resonators; {len(events)} events; {len(errors)} source errors")


if __name__ == '__main__':
    main()
