<div align="center">

# 🎧 Unison

**Listen to the same music, at the same instant, on several phones.**
A shared queue, synced lyrics, and Deezer playing in perfect harmony.

![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

</div>

---

## ✨ Features

### 🎶 Shared listening
- **One-scan sessions**: the host shows a QR code (or a `unison://join` link), guests scan it and join.
- **Collaborative queue**: anyone can add, reorder, remove, skip or go back.
- **Millisecond-level sync**: NTP-style clock offset estimation, a shared playback anchor and continuous drift correction.
- **Scheduled start**: every track starts 3 s after it is requested, giving every phone time to load it and avoiding stutter at the beginning of a song.
- **Automatic reconnection** when the network drops.

### 📡 Two connection modes
| Mode | Technology | When to use it |
|---|---|---|
| **Wi-Fi** | WebSocket on the local network, address shared through the QR code | Everyone is on the same Wi-Fi |
| **No Wi-Fi** | Nearby Connections (Bluetooth + Wi-Fi Direct) | In the car, outdoors, anywhere without a network |

### 🎤 Synced lyrics
- The active line is highlighted and scrolls automatically, shared with the whole session.
- Animated dots during instrumental passages.
- Free sources, no account needed: [LRCLIB](https://lrclib.net), then NetEase. Plain lyrics as a fallback.

### 🎨 Two themes
- **Liquid Glass**: translucent surfaces and background blur.
- **Material 3 Expressive**: Material You colors taken from the wallpaper, shapes that morph on touch, a "cookie" play button and a wavy progress bar.

### 🔗 Deezer integration
- **Share from Deezer**: "Add to Unison" in a track's share menu.
- **Search** for tracks, albums and playlists directly in the app.
- **Whole albums and playlists**: share an album or playlist link to Unison, or tap "+" in the search, to queue every track (up to 100).
- **Add to your Deezer playlist** (optional): with the [Unison Morphe patch](https://github.com/LuckyTheCookie/unison-morphe-patches) applied to Deezer, a button adds the playing track to a playlist you choose, or automatically for everything you listen to. Unison suggests the patch on the home screen and in the settings.
- **Without the patch, Deezer is never modified**: Unison drives it through its media session.

### 🔄 Built-in updates
- On launch, Unison looks for a new [GitHub release](../../releases). If there is one, a card offers **Download** or **Later**.
- A progress bar while downloading, then Android installs the update.
- **Verified before installing**: SHA-256 digest published by GitHub, same application, newer version. Android refuses a different signature anyway.
- Asks only once for the "Install unknown apps" permission for Unison.
- "Check for updates" button in the settings.

### 📲 App sharing
- Send the APK to your friends from the session invite screen.

---

## 🧠 How it works

```
        Host (source of truth)                         Guests
   ┌────────────────────────────┐              ┌─────────────────────┐
   │ SessionLogic               │    state     │ ClientSession       │
   │  queue · history · anchor  │ ───────────▶ │  mirrors the state  │
   │                            │ ◀─────────── │  sends its requests │
   └─────────────┬──────────────┘   commands   └──────────┬──────────┘
                 │                                        │
                 ▼                                        ▼
          PlaybackSync  ──▶ DeezerController ──▶ Deezer media session
          (anchor + clock)    (play · seek · pause)
```

- **Playback anchor** (`PlayAnchor`): a track, a position and the host time at which that position was true. Every phone derives the expected position at any instant from it.
- **Clock** (`ClockSync`): regular pings; the sample with the shortest round trip is kept to estimate the offset from the host.
- **Correction** (`PlaybackSync`): every 2 s, Deezer's actual position is compared with the expected one. A seek only happens after two consecutive out-of-tolerance measurements, with a grace period after every jump.
- **Reliable launch**: `playFromUri` first, then opening Deezer through an intent if the media session does not respond.

### Code structure

```
app/src/main/java/fr/lulu/unison/
├── session/    queue logic, protocol, host/guest sessions, foreground service
├── sync/       shared clock and drift correction
├── net/        Wi-Fi (WebSocket) and no-Wi-Fi (Nearby) transports
├── deezer/     Deezer control, search API, link parsing
├── lyrics/     lyrics providers and LRC parsing
├── share/      receiving shares from Deezer
├── apkshare/   sending the APK
├── update/     updates from GitHub releases
├── data/       local profile (DataStore)
├── model/      shared models
└── ui/         theme, components, Liquid Glass, screens
```

---

## 📲 Installation

Download the latest APK from the [**Releases**](../../releases/latest) page, then open it on your phone (allow installation from your browser if Android asks). After that, updates are handled from inside the app.

**Requirements**
- Android 13 or newer.
- The **Deezer** app, installed and signed in.
- On first launch, Unison asks for notification access. It **does not read any notification**: this permission is only used to reach Deezer's media session.

---

## 🛠️ Building from source

```bash
git clone https://github.com/LuckyTheCookie/unison.git
cd unison
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
```

You need JDK 17 or newer and the Android SDK (platform 37).

### Signing a release

The release APK is signed with a local key that is never committed. Create `keystore.properties` at the project root:

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

Kotlin · Jetpack Compose · Material 3 (Expressive) · Coroutines and Flow · kotlinx.serialization · OkHttp · Java-WebSocket · Nearby Connections · CameraX and ML Kit (QR scanning) · ZXing · Coil · DataStore

---

## ⚠️ Disclaimer

Unison is neither affiliated with nor endorsed by Deezer. Every participant needs their own Deezer account to listen. The app does not bypass any subscription: it only controls playback in the official application.

## 📄 License

Distributed under the [GNU GPL v3](LICENSE).
