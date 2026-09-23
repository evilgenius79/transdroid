Transdroid 3
============

[www.transdroid.org](https://www.transdroid.org/) - [transdroid@2312.nl](mailto:transdroid@2312.nl)

Manage torrents from your Android device.

> **Branch notice** — `master` now contains **Transdroid 3** (currently in alpha), a
> ground-up rewrite following the [Transdroid 3 plan](transdroid3_plan.md). The final
> Transdroid 2 code is preserved at the [`transdroid2-final`](../../tree/transdroid2-final)
> tag and receives no further development.

Download
========

Grab the latest APK from [Releases](../../releases/latest) — download **`app-full-debug.apk`**,
which is signed and installs directly. The optimized `release` APKs are published unsigned
until release signing keys are configured, and unsigned APKs cannot be installed as-is.

Features
========

This branch replaces the entire Transdroid 2 code base (Java, Apache HTTP legacy,
AndroidAnnotations, ORMLite, XML layouts) with a new app built from scratch.

**Torrent management**

* Torrent list with automatic refresh (configurable 3–60 s interval), pull-to-refresh,
  status filter chips, label/category filter chips, name search, and sorting by date
  added, name, download speed, upload speed or ratio; total transfer speeds shown in the
  title bar. On tablets/foldables the list and details show side by side.
* **Swipe gestures** on torrent rows — swipe right to pause/resume, left to remove (with
  confirmation); both directions configurable in Settings (pause/resume, remove,
  re-announce, or nothing).
* Torrent details with start/pause, remove (optionally deleting data), force
  **re-announce**, per-file progress and **per-file download priorities** (including
  skipping files), and **tracker management**: see each tracker with its status and
  remove trackers right from the app.
* Add torrents by magnet link, URL, or `.torrent` file — opened from other apps, shared,
  or picked in-app. Optionally **add paused** so files can be deselected before starting.
  With a single server configured, adding skips the server-confirmation step. Magnet
  links show their metadata-fetch progress instead of a blank 0% entry.

**Connectivity**

* **Local network discovery** — adding a server automatically scans your Wi-Fi/Ethernet
  subnet for Transmission, qBittorrent and Deluge daemons and offers what it finds with
  one tap to fill in the connection details.
* **qBittorrent API keys** (qBittorrent 5.2+) — paste a key generated under
  Options → WebUI → API Key and the app authenticates statelessly with it, no
  username/password login needed.
* Works behind reverse proxies and **Cloudflare Tunnel**: custom HTTP headers per server
  (e.g. Cloudflare Access service tokens), automatic port switching when toggling HTTPS,
  and a connection-help dialog covering LAN, domain/tunnel, portal and self-signed setups.
* **Self-signed HTTPS** done securely: the app shows the server's certificate fingerprint
  and, once accepted, pins exactly that certificate for that server (no "trust
  everything" toggle).
* **Error details** — tap any connection error (or test a connection in server settings)
  to see the exact underlying error, HTTP status codes included, with likely causes
  matched to it.

**Around the app**

* **Home screen widgets** (Glance) — a compact widget with torrent counts and total
  speeds, and an interactive list widget with per-torrent progress, speeds, ETA and
  **play/pause buttons that control torrents without opening the app**; both have manual
  refresh.
* **Search** — in-app torrent search via **Torznab** (Jackett/Prowlarr), so one endpoint
  unlocks hundreds of indexers; results sort by seeders and send straight to the active
  server. `full` flavor only.
* **RSS feeds** — subscribe to torrent RSS/Atom feeds, see new items highlighted, and
  send entries to your client with one tap. `full` flavor only.
* **Notifications** — an opt-in background check (~15 min interval) that notifies when
  torrents finish, with Android 13+ notification-permission handling.
* **Theme** — the classic grey-green Transdroid identity in light and dark; follow the
  system setting or force light/dark in Settings.
* **Settings backup** — export servers, feeds and search indexers to a
  passphrase-encrypted file (PBKDF2 + AES-GCM) and restore after a reinstall or on
  another device.

**Security & engineering**

* Server credentials, API keys, feed URLs and indexer keys are stored AES-256-GCM
  encrypted with a hardware-backed Android Keystore key and excluded from cloud backup;
  passwords never leave the device.
* A pure-JVM `:protocol` module with one normalized `DaemonAdapter` interface and
  adapters for **Transmission** (JSON-RPC, 409 session-id handshake), **qBittorrent**
  (Web API v2, 4.x and 5.x endpoint names, API keys), **rTorrent** (XML-RPC with a
  hardened minimal codec) and **Deluge** (Web UI JSON-RPC) — all unit-tested against
  recorded fixture responses, no emulator needed.
* CI on every push (tests, lint, R8 release builds, installable debug APK artifact),
  tag-triggered GitHub Releases, F-Droid-ready reproducible-build settings and fastlane
  metadata. [CONTRIBUTING.md](CONTRIBUTING.md) documents how to add more client adapters.

Not yet done: F-Droid inclusion (metadata is ready; store screenshots and the fdroiddata
merge request remain), translations, and the remaining Transdroid 2 client adapters. See
the [roadmap](transdroid3_plan.md#roadmap).

About the rewrite
=================

Transdroid 3 is a fresh start on the same mission: manage your torrents from your Android
device. The rewrite replaces the legacy Apache HTTP networking, AndroidAnnotations, ORMLite
and XML layouts of Transdroid 2 with a modern, testable stack:

* **Kotlin** everywhere, with coroutines and Flow for concurrency
* **Jetpack Compose** with Material 3, themed with the classic Transdroid grey-green identity
* **OkHttp** + kotlinx.serialization for the daemon protocols
* A pure-JVM **`:protocol` module** containing all client adapters, unit-tested against
  recorded fixture responses — no Android dependency, no emulator needed
* **Encrypted server profiles**: connection credentials are stored AES-256-GCM encrypted
  with a hardware-backed Android Keystore key, and excluded from device backups
* **DataStore** for preferences; no SQL database, no ORM
* minSdk 29 (Android 10) and up, edge-to-edge, adaptive two-pane layout on large screens

Supported clients
=================

| Client | Status |
| --- | --- |
| Transmission | ✅ Supported (RPC over JSON, session-id handshake, basic auth) |
| qBittorrent | ✅ Supported (Web API v2, works with qBittorrent 4.1+ and 5.x; API keys on 5.2+) |
| rTorrent | ✅ Supported (XML-RPC over HTTP, e.g. /RPC2 behind a web server or ruTorrent) |
| Deluge | ✅ Supported (Web UI JSON-RPC, Deluge 1.3 and 2.x) |

The remaining Transdroid 2 adapters are out of scope for the initial v3 release; community
contributions can revive them once the adapter interface stabilizes — see
[CONTRIBUTING.md](CONTRIBUTING.md) for how to add an adapter.

Building
========

```
./gradlew :protocol:test        # protocol layer unit tests (pure JVM)
./gradlew :app:assembleFullDebug
```

Two product flavors exist, carried over from Transdroid 2: `full` (transdroid.org, F-Droid)
and `lite` (Google Play). Feature differences are driven purely by flavor resources.

Contributions
=============

Code and design contributions are very welcome.
Please note that all code will be licensed in GNU GPLv3.

Developed By
============

Designed and developed by [Eric Kok](mailto:eric@2312.nl) of [2312 development](https://2312.nl/).
Contributions by various others (see commit log).

License
=======

    Copyright 2010-2026 Eric Kok et al.

    Transdroid is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Transdroid is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Transdroid.  If not, see <https://www.gnu.org/licenses/>.

Libraries used in the project:
*  [Android Jetpack (AndroidX)](https://developer.android.com/jetpack), including Compose —
   The Android Open Source Project, Apache License 2.0
*  [Kotlin and kotlinx libraries](https://kotlinlang.org/) —
   JetBrains and contributors, Apache License 2.0
*  [OkHttp](https://square.github.io/okhttp/) —
   Square, Inc., Apache License 2.0
