<div align="center">

# 🎧 Unison

**Listen to the same music, at the same instant, on several phones.**
A YouTube Music player with a shared queue and synced lyrics.

![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

</div>

---

## ✨ Features

### 🎵 A full music player
- **YouTube Music inside the app**: search tracks, albums, artists and playlists, browse the home feed and charts. No account needed.
- **Its own player** (Media3 ExoPlayer): notification, lock screen, headset and Bluetooth controls, background playback, on-device audio cache.
- **Queue**: play next, add to queue, reorder, shuffle, repeat, and an automatic radio that keeps the music going.
- **Library on the phone**: favourites, playlists, saved albums and listening history.
- **Synced lyrics** from [LRCLIB](https://lrclib.net), then NetEase. Tap a line to jump to it.

### 🎧 Listen together
- **Open your queue to friends** without stopping the music: show a QR code, they scan it and join.
- **Shared queue**: anyone can add, reorder, remove, skip or pause.
- **Millisecond-level sync**: NTP-style clock offset, a shared playback anchor, and drift corrected by tiny speed changes (a seek only for large gaps).
- **Two connection modes**: same Wi-Fi (WebSocket), or no Wi-Fi at all with Nearby Connections (Bluetooth + Wi-Fi Direct).
- Leaving a session gives you your own queue back.

### 🎨 Design
- The artwork colours the screen through an animated background, a Compose/AGSL port of [Kawarp](https://github.com/better-lyrics/kawarp).
- Neutral glass surfaces, calm motion, Inter and Montserrat typefaces, MingCute icons. Inspired by [Liner](https://github.com/tryliner/desktop).

### 🔄 Built-in updates
- On launch, Unison looks for a new [GitHub release](../../releases) and offers to install it (SHA-256 verified).

---

## 🧠 How it works

```
   Phone that owns the queue (alone, or host)            Guests
   ┌────────────────────────────┐              ┌─────────────────────┐
   │ SessionLogic               │    state     │ ClientSession       │
   │  queue · history · anchor  │ ───────────▶ │  mirrors the state  │
   │                            │ ◀─────────── │  sends its requests │
   └─────────────┬──────────────┘   commands   └──────────┬──────────┘
                 ▼                                        ▼
        PlaybackController ──▶ ExoPlayer ◀── stream resolved from YouTube Music
```

- The same queue logic runs alone and together: alone, the phone is its own host.
- **Playback anchor** (`PlayAnchor`): a track, a position and the host time at which that position was true.
- **Sync** (`PlaybackController`, `SyncPolicy`): under 25 ms nothing, up to 150 ms a ±3 % speed nudge, beyond that a seek.
- **Streams** are resolved by [InnerTubeX](https://github.com/MetrolistGroup/innertubex) just before playback.

The app lives in [`v2/`](v2). The first version, which drove the Deezer app, is kept in `app/` for reference.

---

## 📲 Installation

Download the latest APK from the [**Releases**](../../releases/latest) page, then open it on your phone (allow installation from your browser if Android asks). After that, updates are handled from inside the app.

**Requirements**
- Android 13 or newer.

---

## 🛠️ Building from source

```bash
git clone https://github.com/LuckyTheCookie/unison.git
cd unison/v2
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
```

You need JDK 17 or newer and the Android SDK (platform 37).

### Signing a release

The release APK is signed with a local key that is never committed. Create `keystore.properties` at the repository root:

```properties
storeFile=/path/to/unison-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

then:

```bash
./gradlew assembleRelease
```

Without this file the build simply isn't signed with your key.

---

## 🧰 Tech stack

Kotlin · Jetpack Compose · Media3 ExoPlayer · InnerTubeX · Room · Coroutines and Flow · kotlinx.serialization · Ktor/OkHttp · Java-WebSocket · Nearby Connections · CameraX and ML Kit · ZXing · Coil · DataStore

Third-party notices: [`v2/THIRD_PARTY_NOTICES.md`](v2/THIRD_PARTY_NOTICES.md).

---

## ⚠️ Disclaimer

Unison is not affiliated with, nor endorsed by, YouTube or Google. It uses YouTube Music's private API, which may break without notice and whose use may be contrary to YouTube's terms of service.

## 📄 License

Distributed under the [GNU GPL v3](LICENSE).
