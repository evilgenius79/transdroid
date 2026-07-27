# Contributing to Transdroid 3

Code and design contributions are very welcome. All code is licensed under the
GNU GPL v3 (see [COPYING](COPYING)).

## Project layout

| Module | What it is |
| --- | --- |
| `:protocol` | Pure-JVM Kotlin library: torrent client (daemon) adapters, protocol models, no Android dependencies |
| `:app` | The Android app: Jetpack Compose UI, ViewModels, encrypted settings storage |

Build and test with:

```
./gradlew :protocol:test                 # protocol unit tests (run these first, they're fast)
./gradlew :app:testFullDebugUnitTest     # app unit tests
./gradlew :app:lintFullDebug             # Android lint
./gradlew :app:assembleFullDebug         # installable debug APK
```

CI runs all of the above on every push and pull request.

## Adding a torrent client adapter

Client adapters are the project's main extension point. Each one lives in its own package
under `protocol/src/main/kotlin/org/transdroid/protocol/` and consists of:

1. **An implementation of `DaemonAdapter`** (`org.transdroid.protocol.DaemonAdapter`) —
   seven suspend functions: `testConnection`, `listTorrents`, `addByUrl`, `addByFile`,
   `start`, `pause`, `remove`, `listFiles`. Map your client's states and units onto the
   normalized `Torrent`/`TorrentFile` models (progress 0..1, rates in bytes/second, eta in
   seconds or null when unknown). Throw the right `DaemonException` subtype — `Connection`,
   `Authentication` or `UnexpectedResponse` — so the UI can give targeted feedback.
2. **A `DaemonType` entry** in `Models.kt` with the client's default ports, plus a branch
   in `DaemonAdapterFactory.create`.
3. **Fixture-based unit tests** — record real responses from your client into
   `protocol/src/test/resources/<client>/` and test against a `MockWebServer`. Look at
   `TransmissionAdapterTest` for the pattern. Cover at minimum: the happy-path torrent
   list with all status mappings, the authentication failure path, and any session or
   handshake quirks of the protocol.
4. **UI wiring** in the app module: a display name and default-path hint branch in
   `EditServerScreen.kt` (the compiler's exhaustive `when` will point you to every spot).

Use the existing adapters as references — Transmission (JSON-RPC), qBittorrent (REST +
cookie auth), rTorrent (XML-RPC) and Deluge (web JSON-RPC) cover most protocol shapes a
new client is likely to need.

## Other extension points

- **Search providers** implement `org.transdroid.protocol.search.SearchProvider`. The
  shipped `TorznabProvider` covers Jackett/Prowlarr; a provider for another API follows
  the same pattern (pure JVM, fixture-tested, results expose a `torrentUrl` a daemon
  adapter can consume).
- **Feed handling** lives in `org.transdroid.protocol.rss.RssFetcher`; extend it there
  (with fixtures) rather than in the UI layer if a tracker's dialect needs special-casing.

Guidelines:

- Keep adapters free of Android imports; the `:protocol` module must stay pure JVM.
- Never log or embed credentials, hosts or torrent names in exception messages beyond
  what the UI needs.
- Support current client versions first; only add legacy fallbacks that you can test.

## Code style

Standard Kotlin style (`kotlin.code.style=official`). Match the surrounding code, prefer
small focused files, and let the compiler's exhaustiveness checks do the wiring work.
