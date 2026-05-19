# Bandcamp Extension for Echo123

Browse, search and stream Bandcamp's independent music catalog directly in the [Echo music player](https://github.com/brahmkshatriya/echo).

## Features

- 🔍 **Search** tracks, albums and artists
- 🎵 **Stream** mp3-128 previews from Bandcamp's CDN
- 💿 **Browse** artist discographies
- 📀 **Album view** with full tracklist

## Installation

### From GitHub Releases (easiest)
1. Go to the [Releases](../../releases) page
2. Download the latest `.eapk` file
3. Open it on your Android device — when prompted, select **Echo** as the handler
4. Done!

### Build from source

#### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17
- Git

#### Steps

```bash
git clone https://github.com/yourusername/echo-bandcamp-extension
cd echo-bandcamp-extension
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.  
Rename it to `.eapk` and open with Echo, or install directly via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Publishing your own build

1. Fork this repo
2. Go to **Settings → Actions → General → Workflow Permissions** → enable **Read & write**
3. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore keystore.jks -alias key0 -keyalg RSA -keysize 2048 -validity 10000
   ```
4. Add secrets in **Settings → Secrets → Actions**:
   - `KEYSTORE_B64` — `base64 keystore.jks`
   - `PASSWORD` — your keystore password
5. Push a commit — GitHub Actions will build and publish a release automatically

## Technical notes

Bandcamp has no public API, so this extension uses HTML scraping:

| Operation | Method |
|-----------|--------|
| Search | `GET bandcamp.com/search?q=<query>&item_type=t/a/b` — parse `.searchresult` items |
| Track stream | Fetch track page → extract `TralbumData` JSON → `trackinfo[n].file["mp3-128"]` |
| Album tracks | Same `TralbumData` extraction, iterate `trackinfo` array |
| Artist page | Parse `.music-grid-item` elements for album list |

Stream URLs point to `t4.bcbits.com` (Bandcamp's CDN) at 128 kbps MP3.  
Only tracks Bandcamp allows to preview publicly are streamable.

## License

MIT
