Transdroid 3
============

[www.transdroid.org](https://www.transdroid.org/) - [transdroid@2312.nl](mailto:transdroid@2312.nl)

Manage torrents from your Android device.

> **Branch notice** — this branch contains the in-development **Transdroid 3**, a ground-up
> rewrite following the [Transdroid 3 plan](transdroid3_plan.md). The stable Transdroid 2
> app lives on `master`, which is in maintenance mode (security/critical fixes only).

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
| qBittorrent | ✅ Supported (Web API v2, works with qBittorrent 4.1+ and 5.x) |
| rTorrent, Deluge | Planned (see the [roadmap](transdroid3_plan.md#roadmap)) |

The remaining Transdroid 2 adapters are out of scope for the initial v3 release; community
contributions can revive them once the adapter interface stabilizes.

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
