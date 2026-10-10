# WuWa Tracker

An unofficial Android companion for **Wuthering Waves**. WuWa Tracker provides server-reset countdowns, daily and endgame checklists, banner schedules, and a source-linked intelligence feed for official announcements, community reports, and unconfirmed leaks.

This project was developed with substantial AI assistance using an informal “vibe coding” workflow. It is a fan project, not an official Kuro Games product, and is not affiliated with or endorsed by Kuro Games.

## Features

- Daily reset countdowns for America, Europe, Asia, SEA, and HMT servers, displayed in the device’s local time
- Optional reset and custom reminders
- Daily and Lunite checklists, plus monthly Endgame checklist reminders for Tower of Adversity, Whimpering Wastes, and Endstate Matrix
- Endgame tracking for Tower of Adversity, Whimpering Wastes, and Endstate Matrix
- Active and upcoming banner schedules, with resonator and weapon details when available
- Intelligence feed that separates official confirmations, community reports, and unconfirmed leaks
- Source links and evidence labels where available

The monthly Endgame checkboxes reset at the start of each calendar month in the selected game server’s time zone. They are convenience reminders; they do not track the individual modes’ live rotation start and end dates. The Endgame tab can display source-derived dates when the feed provides them, but its built-in recurring entries do not have exact dates. Use the in-game timers for current rotation timing.

## Intelligence feed and updates

The feed is assembled by a Python pipeline and published as `feed.json` through GitHub Pages. A GitHub Actions workflow runs once per day and can also be started manually from the repository’s **Actions** tab. The app’s **Refresh intelligence** button downloads the latest feed already published to GitHub Pages; it does not start the GitHub Actions workflow.

The feed is best-effort. Sources can change, become unavailable, or omit information; automated extraction and summaries can be incomplete or wrong. A report’s presence does not confirm that it is true. Treat leaks as unconfirmed, and use Kuro Games announcements and the in-game schedule as the final authority.

## Sources and attribution

WuWa Tracker links to public sources and uses them as references for schedule and feed information. Source availability and coverage may change.

### Official information

- [Wuthering Waves official website](https://wutheringwaves.kurogames.com/)
- Public official Wuthering Waves X posts discovered through Google News indexing

### Banner schedules and reference data

- [WuWa Banners](https://wuwabanners.net/) — current banner schedule reference
- [GenGamer Wuthering Waves Countdown](https://wuthering-countdown.gengamer.in/) — upcoming banner schedule reference
- [WuWaTracker Timeline](https://wuwatracker.com/timeline) — timeline and weapon schedule references
- [WuWaBuild Banners](https://www.wuwabuild.com/banners) — banner schedule reference

### Community reports and leak discovery

- [r/WutheringWavesLeaks](https://www.reddit.com/r/WutheringWavesLeaks/) and [r/WutheringWaves](https://www.reddit.com/r/WutheringWaves/) public RSS feeds
- Public X posts discovered through Google News indexing
- [WutheringWaves.gg](https://wutheringwaves.gg/), [Game8](https://game8.co/games/Wuthering-Waves), [GamingOnPhone](https://gamingonphone.com/), [Mone.gg](https://mone.gg/blog/wuthering-waves/), [UU TOP](https://uu-top.com/en/blog), [U7BUY](https://www.u7buy.com/), and [LDShop](https://ldshop.gg/blog/wuthering-waves/)

The sources above retain their own content, names, and rights. Links and attribution are provided to identify references; they do not imply endorsement or grant permission to reuse third-party material.

### Images and game intellectual property

Resonator and weapon image URLs are sourced from the [Wuthering Waves Wiki on Fandom](https://wutheringwaves.fandom.com/) when a matching image is available. The feed carries image URLs rather than the image files; the app fetches thumbnails from Fandom at runtime and caches them on the device for the relevant banner period.

The app also includes visual resources in its Android project, including tab backgrounds and launcher artwork. Some may depict Wuthering Waves characters or other game-related material. Wuthering Waves, its characters, logos, and original game artwork belong to their respective rights holders, including Kuro Games. The project’s code terms below do not license that material. Rights holders can contact the maintainer through the [repository issue tracker](https://github.com/the-nightwielder/WuWa-Tracker/issues) to raise a concern.

## Security, privacy, and use at your own risk

This project is AI-assisted and has not been represented as professionally audited or guaranteed secure. Use it only if you accept the risks of running third-party software and relying on automatically collected public information. Review the source code and permissions before installing or building it. Do not use leaked information as authoritative guidance for purchases or gameplay decisions.

The app requests internet access to retrieve the public feed and image thumbnails. It also requests notification and boot-completed permissions to deliver reminders. The project does not intentionally include feed-service credentials in the Android app.

The software and feed are provided “as is,” without warranties of any kind. To the fullest extent permitted by applicable law, the maintainer disclaims liability for loss, damage, security issues, inaccurate data, service outages, or other consequences arising from use of the app or feed. You are responsible for deciding whether and how to use them. This notice does not remove rights or liabilities that cannot legally be excluded in your jurisdiction.

## Code rights

No open-source license is currently granted for this repository’s original source code. All rights are reserved by the copyright holder, except for the limited rights GitHub’s terms grant for viewing and forking a public repository within GitHub. This notice does not change the rights in third-party dependencies, data, artwork, trademarks, or other materials.

## Build

1. Open the project in Android Studio and let Gradle sync.
2. Build the app using the included Gradle wrapper.
3. For public distribution, create a properly signed release APK. Never commit the signing key or passwords to the repository.

The project targets Android API 35. The current app version is **1.0.0** (version code **100**).
