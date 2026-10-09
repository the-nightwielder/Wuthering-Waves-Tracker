# WuWa Tracker v1.3 — free structured intelligence pipeline

This backend turns public Wuthering Waves information into a normalized `feed.json` consumed by the Android app.

## No paid X API

v1.3 does **not** require an X/Twitter API token.

X discovery uses public X pages that are indexed by Google News RSS queries. Reddit's public RSS feeds are also used for community/leak discovery. This is intentionally a best-effort free strategy: it cannot guarantee complete coverage of every X post, and it does not bypass X access controls.

If you later self-host an RSS bridge that legally exposes specific public X feeds, you can add it as another RSS source in `sources.json` without changing the Android app.

## Sources

- Official Kuro Games news via Google News RSS indexing
- WutheringWaves.gg
- Game8
- LDShop
- GamingOnPhone
- Public X pages indexed by Google News
- r/WutheringWavesLeaks RSS
- r/WutheringWaves RSS
- WuWa Banners
- WuWa Countdown

## Structured output

`feed.json` schemaVersion 3 contains:

- `news[]` — source-attributed articles/posts
- `versions[]` — version entities and evidence
- `banners[]`, `activeBanners[]`, `upcomingBanners[]` — banner/phase entities, evidence, and lifecycle buckets
- `resonators[]`, `upcomingResonators[]` — conservatively extracted Resonator claims
- `events[]` — source-derived event/endgame date claims
- `sourceHealth[]` — fetch failures
- `policy` — explicit rules for confirmation/leaks

Each structured entity keeps source URLs and confidence. Multiple independent sources can increase confidence, but a leak never becomes official automatically. Official Kuro information remains authoritative.

## Local test

```bash
python -m venv .venv
# Windows: .venv\\Scripts\\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
python aggregator.py
```

## GitHub deployment

The repository workflow runs every 30 minutes and can also be started manually. It:

1. fetches sources;
2. extracts and correlates entities;
3. writes `feed.json`;
4. commits the snapshot;
5. publishes the same directory to GitHub Pages.

GitHub Pages is used as the free HTTPS data endpoint. After the first successful Pages deployment, the Android app can use:

```text
https://<github-user>.github.io/<repository>/feed.json
```

Do not put credentials or private data in this repository. The feed is intentionally public.
