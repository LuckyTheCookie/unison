<div align="center">

# 🎧 Unison

**Listen to the same music, at the same instant, on several phones.**
A YouTube Music player with a shared queue, offline downloads and word-by-word lyrics.

![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

</div>

---

## ✨ Features

### 🎵 A full music player
- **YouTube Music inside the app**: search tracks, albums, artists and playlists, browse the home feed and charts. No account needed (except, optionally, for age-restricted tracks).
- **Its own player** (Media3 ExoPlayer): notification, lock screen, headset and Bluetooth controls, background playback, on-device audio cache.
- **Gapless playback**: the next track is prepared while the current one plays, so changing track never cuts the sound.
- **Swipe the artwork** to skip to the next track or go back to the previous one.
- **Add to a playlist from the player**: a "+" button next to the heart adds the current track to a playlist (or a new one) in one tap.
- **A player that grows out of the mini player**: tap the mini player and it expands to full screen; drag it up or down and it follows your finger step by step (also from the top of the lyrics or the queue), then settles on the side you were heading to or flung it.
- **Smooth predictive back** for the player (it lowers as you swipe), the "Together" screen and navigation (the page shrinks and slides away from the edge under your finger, fades out when you let go, and only then does the one below come in; cancelling puts it back with a small spring).
- **Organised settings**: a home page with your profile and one entry per topic (Playback, Audio sources, YouTube account, Downloads and storage, Appearance, About), each showing what is currently chosen.
- **Queue**: play next (or swipe a track to the right), add to queue, reorder, repeat, and an automatic radio that keeps the music going.
- **Shuffle mode** you can switch on and off like repeat: switching it off puts the remaining tracks back in their original order. The state is shared in a session.
- **Bass Booster** (Settings > Playback): five levels, applied on the phone with a bass shelf filter and a limiter, so it works on every device and on downloaded tracks.
- **A home made for you**, all computed on the phone from what you actually listen to (a score that adds up plays and halves every week):
  - shortcut tiles: the Mix, then the playlists, albums, artists and lists you play now and often, newest first (they stay put while you are on the screen);
  - "Listen again": albums, playlists and artists you played, plus tracks you started on their own;
  - "For you": tracks close to what you listen to right now;
  - your artists, ordered by how much you play them;
  - "To discover": tracks close to your current favourites and artists similar to them, taken from YouTube Music's "Related" pages, without anything you already know. Generic YouTube Music suggestions only show up while there is no listening history yet.
- **Mix and library search**: one tap plays a shuffle of your favourites, playlists, saved albums and downloads; search finds any track in your library.
- **Search inside a list**: the magnifier at the top of a playlist, album, favourites or history filters its tracks as you type; a tapped result plays the whole list from there.
- **Multi-select**: long-press a track in a list to select several, then play them, play them next, add them to the queue, a playlist or your favourites, download them, or remove them from your playlist.
- **Library on the phone**: favourites, playlists, saved albums, followed artists and listening history. It follows you to a new phone through Android backup and device transfer (downloads, caches and imported sources stay on the old one).

### 🔌 Audio sources
- YouTube Music is the default source. In Settings > Audio sources you can add **another source** from the URL of its addon manifest (HTTP `/manifest.json`, `/search`, `/stream/{id}`), pick it, search directly inside it, or remove it. Unison ships **no source by default**: you are responsible for the legality of the content and for the terms of any source you add.
- A track that the chosen source also has (same title, version, artist and duration) plays from the source; otherwise it falls back to YouTube Music (switchable, and never used in data-saver mode).
- Playback starts on YouTube Music right away and switches to the source as soon as it answers, even after a slow first request (a cold server gets up to 20 s in the background instead of blocking playback) — shown as "Augmentation de la qualité" while it happens. The next few tracks in the queue are looked up ahead of time so the switch is already done by the time they play. Never happens in a shared session or in data-saver mode.
- Safe by design: HTTPS only, size and time limits, no private network addresses, no encrypted/DRM or adaptive (HLS/DASH) streams, header allow-list, nothing downloaded from a source is ever executed. A confirmation screen with a health check comes before any source is added.
- Downloads remember their source and format; shared sessions work with source tracks without any protocol change.
- A **quality label** under the track ("Sans perte", "Haute résolution", "Haute qualité", Dolby Atmos) is read from the format that is really decoded and hidden when it is standard.
- **Age-restricted tracks** (Settings > YouTube account): YouTube only serves them to a signed-in adult account. Unison can sign in through [microG RE](https://github.com/MorpheApp/MicroG-RE) (the microG used by Morphe and ReVanced apps), so it never sees your password. The account is used for those tracks only: search, home and every other track stay anonymous, and nothing is added to your YouTube history. The session stays on the phone and is left out of backups.

### 🎤 Word-by-word lyrics
- Word-synced lyrics from Apple Music (through [BetterLyrics](https://better-lyrics.boidu.dev) and LyricsPlus), NetEase and [LRCLIB](https://lrclib.net), all asked at once: the most precise answer wins. YouTube titles are cleaned first ("Official MV", "Radio Edit", "Artist - Title"…).
- Each word lights up as it is sung with a soft edge, lifts slightly, and long notes glow. A playhead that advances every frame keeps the fill smooth. Far lines are blurred, and dots breathe through intros and instrumental breaks.
- Without word timings, the time of each line is spread over its characters, so the karaoke effect is always there.
- Tap a line to jump to it. Lyrics of downloaded tracks work offline.
- A one-line **lyrics strip** above the seek bar follows the song on the artwork page; tap it to open the full lyrics. A new setting reduces animations.

### 📥 Offline listening
- **Download** any track from its menu, or a whole favourites list, playlist or album.
- Downloaded tracks play with no connection at all, airplane mode included, with their artwork and lyrics.
- A small **download badge** marks downloaded tracks everywhere (library, favourites, playlists, history, search, queue, player); a progress ring shows downloads in progress.
- A Downloads screen shows progress, lets you pause, resume and delete, and tells you how much space is used. An option restricts downloads to Wi-Fi.
- Offline, the queue skips tracks that are not downloaded and jumps to the next available one.

### 🔁 Import your library
- Import a **CSV export** from another service (for example from [TuneMyMusic](https://www.tunemymusic.com), Deezer format included) from Settings or the Library.
- Liked tracks go to your favourites, each playlist becomes a Unison playlist, artists become follows.
- Tracks are matched through YouTube Music search. A final report lists what was imported, what was not found (with a manual search) and what was skipped. An interrupted import can be resumed.

### ⭐ Followed artists
- Follow artists from their page or through an import.
- Your artists show up on the home screen with their latest releases and tracks, and the automatic **radio favours them** (adjustable in Settings).

### 🎧 Listen together
- **Open your queue to friends** without stopping the music: show a QR code, they scan it and join.
- **Shared queue**: anyone can add, reorder, remove, skip or pause.
- **Millisecond-level sync**: NTP-style clock offset, a shared playback anchor, and drift corrected by tiny speed changes (a seek only for large gaps).
- **Two connection modes**: same Wi-Fi (WebSocket), or no Wi-Fi at all with Nearby Connections (Bluetooth + Wi-Fi Direct).
- Leaving a session gives you your own queue back.

### 🎨 Design
- The artwork colours the screen through an animated background, a Compose/AGSL port of [Kawarp](https://github.com/better-lyrics/kawarp).
- Neutral glass surfaces, calm motion, Inter and Montserrat typefaces, MingCute icons. Inspired by [Liner](https://github.com/tryliner/desktop).
- Small touches everywhere: the heart pops, play/pause morphs, the tab pill slides, the download ring turns into a check, loading shows skeletons. "Reduce animations" turns them off.
- The animated background is drawn at a quarter of the screen size and 30 fps, and stops in battery saver.

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
                                  │
                       downloads cache · audio cache
```

- The same queue logic runs alone and together: alone, the phone is its own host.
- **Playback anchor** (`PlayAnchor`): a track, a position and the host time at which that position was true.
- **Sync** (`PlaybackController`, `SyncPolicy`): under 25 ms nothing, up to 150 ms a ±3 % speed nudge, beyond that a seek. Alone, the anchor follows the player instead.
- **Gapless** (`Gapless`): ExoPlayer holds the current and the next track; the session switches to the prepared item instead of reloading.
- **Streams** are resolved by [InnerTubeX](https://github.com/MetrolistGroup/innertubex) just before playback, fetched in bounded ranges, and read from the permanent downloads cache first (`offline/`).
- **Bass Booster** (`BassBoost`): a Media3 audio processor (low-shelf biquad around 100 Hz, input headroom, limiter), updated live without reloading the track.
- **Sources** (`source/`): `MusicSource`, `YouTubeSource`, the addon client, `TrackMatcher` and `SourceResolver` (chosen source first, YouTube Music as a fallback); a network guard checks every hop of a source stream.
- **Library** (`library/`, `artists/`): Room database (schema versioned in `v2/app/schemas/`) for favourites, playlists, history, downloads and followed artists.
- **Import** (`importer/`): a RFC 4180 CSV parser with columns detected by header name, then a scored YouTube Music search per line.

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
./gradlew assembleDebug        # debug APK (installs next to the release as fr.lulu.unison.dev)
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

Kotlin · Jetpack Compose · Media3 ExoPlayer (cache and downloads) · InnerTubeX · Room · Coroutines and Flow · kotlinx.serialization · Ktor/OkHttp · Java-WebSocket · Nearby Connections · CameraX and ML Kit · ZXing · Coil · DataStore

Third-party notices: [`v2/THIRD_PARTY_NOTICES.md`](v2/THIRD_PARTY_NOTICES.md).

---

## ⚠️ Disclaimer

Unison is not affiliated with, nor endorsed by, YouTube or Google. It uses YouTube Music's private API, which may break without notice and whose use may be contrary to YouTube's terms of service. Downloads are meant for personal offline listening inside the app.

## 📄 License

Distributed under the [GNU GPL v3](LICENSE).
