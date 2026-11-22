# Bingo2Playlist

An Android app that turns music bingo cards into a playlist.

1. **Start a batch** and tap **Take photos**. The camera reopens after every shot, so you can photograph card after card. Press back in the camera when you're done (or add photos from the gallery, or share photos into the app).
2. Each card is read as soon as it's taken: song titles and artists are pulled from the 5×5 grid, merged into one list, and duplicates are removed (even when the cards print them slightly differently).
3. Fix anything that looks off, then tap **Finish & save**. The playlist is saved in the app and the share sheet opens.
4. Share or save it as **CSV** (for Spotify import), a plain text list, or M3U.

## Download

**[bingo2playlist.apk](https://github.com/randaladams/bingo2playlist/releases/latest/download/bingo2playlist.apk)** (always the latest build)

On your phone: open the link, download, tap the file, and allow "Install unknown apps" for your browser when Android asks. Requires Android 9 or newer.

## Getting it into Spotify

Spotify can't import a song list itself. Use a free transfer service:
- [TuneMyMusic](https://www.tunemymusic.com/transfer) or [Soundiiz](https://soundiiz.com/): choose *Upload file*, pick the CSV, choose Spotify as the destination.
- Or tap **Find** next to any song to open it in Spotify.

## Reading engines

- **On this phone** (default): Google ML Kit text recognition, bundled in the APK, offline and free. Words covered by dauber ink can be missed, so check the list.
- **Claude** (Settings ⚙): sends each card photo to the Anthropic API with your own API key. Much better at reading through ink and correcting titles.

## Building

GitHub Actions builds the APK on every push to `main` and publishes it as a release (`.github/workflows/build-apk.yml`).
Locally: `./gradlew assembleRelease` (needs the Android SDK; JDK 17+).

The APK is signed with `app/debug.keystore`, a throwaway key kept in the repo so every build installs over the last one. It's fine for side-loading but don't use it for a Play Store release.
