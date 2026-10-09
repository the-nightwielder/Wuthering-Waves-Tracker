# WuWa Tracker v1.3

Android 15 Wuthering Waves tracker with a free, automated structured-intelligence backend.

## Architecture

```text
Public sources
  ├─ Kuro official news
  ├─ community sites
  ├─ public X pages indexed by Google News RSS
  └─ Reddit RSS
          ↓
GitHub Actions (every 30 min)
          ↓
Python intelligence pipeline
  ├─ normalization
  ├─ deduplication
  ├─ version extraction
  ├─ banner/phase extraction
  ├─ Resonator claim extraction
  ├─ event/endgame extraction
  ├─ cross-source evidence correlation
  └─ confidence scoring
          ↓
feed.json (schema v3)
          ↓
GitHub Pages HTTPS
          ↓
Android WorkManager sync
          ↓
local cache + notifications + countdown UI
```

## Free X strategy

There is no X API key in v1.3. The backend uses public X pages indexed through Google News RSS queries and Reddit RSS for additional community coverage. This is free and avoids putting a paid API dependency in the project, but it is not equivalent to direct full X search and cannot guarantee every X post.

## Android setup

1. Open this project in Android Studio.
2. Let Gradle sync.
3. Build the app with Android 15/API 35.
4. The app reads only this project's GitHub Pages feed: `https://the-nightwielder.github.io/Wuthering-Waves-Tracker/feed.json`.
5. Enable GitHub Pages using **GitHub Actions**, run the intelligence workflow, then use **Intel → Refresh feed** in the app to retrieve the latest published results.

The Android app never receives any backend/API credentials.

## Leak policy

`OFFICIAL`, `COMMUNITY`, and `LEAK` are kept separate. Cross-source agreement raises a leak's confidence but does not promote it to official. The in-game timer/official notice is the final authority for exact availability.

## Important build note

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk` when built in Android Studio or with Gradle.
