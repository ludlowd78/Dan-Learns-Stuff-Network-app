# Dan Learns Stuff Network — Android app

A real Android app for **The Dan Learns Stuff Network** podcast app. It wraps
the network's web app in a WebView and — the whole point of it — keeps episode
audio playing with the phone screen locked, with proper lock-screen controls.

## Why this exists

Inside the Muse app, playback stops about 3 seconds after the screen locks:
Android's WebView has no Media Session API and the OS suspends the page when
the screen goes off. No page-level fix can get around that. Opening the app in
Chrome works, but the "Open in Chrome" button opened a black screen on
Daniel's phone, so this native app is the reliable route instead.

## How it works

- **The UI is the web app.** `MainActivity` loads the published network app
  (`https://muse.ai/s/through-the-doorway-...`) in a WebView, so every show,
  episode, resume position, playback speed, sleep timer, pocket mode and voice
  command keeps working exactly as it does now. There is no separate native
  episode browser.
- **Lock-screen playback is native.** `PlaybackService` is a foreground service
  (`mediaPlayback` type) with a partial wake lock, so the process stays alive
  when the screen locks. It owns a native `MediaSessionCompat`, giving the lock
  screen play/pause, ±15-second seek, episode title, artist and cover art.
- **The web audio stays in charge.** The audio still comes from the web
  player's own `<audio>` element. A small JavaScript bridge (`AudioBridge`,
  injected after page load) reports play/pause/position/metadata to the
  service, and MediaSession commands (play, pause, seek) are sent back into
  the page to drive that same element. This preserves the web player's
  resume-position, speed and sleep-timer behaviour.

### Trade-offs, stated plainly

- **Swiping the app away from Recents stops playback.** The audio element
  lives in the activity's WebView, so destroying the activity destroys the
  audio. Locking the screen or pressing Home keeps it playing; swiping it
  away does not. (Running the WebView inside the service instead would fix
  this, but it is fragile and was not worth it for v0.1.)
- **Lock-screen title/artwork are best-effort.** The bridge reads the page's
  own Media Session metadata first, then scrapes the player DOM for a title
  and cover image, then falls back to the document title. If the web app's
  markup changes, the worst case is a generic title on the lock screen —
  playback itself is unaffected.
- **The app loads the published version of the web app.** New episodes appear
  when the app is shared again from Muse — same as the public link works
  today. If the public link is stale, the app shows older episodes.
- **Debug APK, not Play Store.** The Actions build produces a debug APK for
  sideloading. No signing key is needed for that, but the app cannot go on
  the Play Store without a release signing setup.

### Fallback if this ever proves unreliable

If the WebView-audio-kept-alive approach ever stops holding on a device, the
fallback is to route episode audio through a native player (ExoPlayer) driven
from the WebView via the same bridge, keeping the web UI in charge of what
plays. That is a bigger change and is not built here.

## Download and install

1. Open the **Releases** page for this repo:
   https://github.com/ludlowd78/Dan-Learns-Stuff-Network-app/releases
2. Under **Latest build**, download `app-debug.apk`.
3. Open the downloaded file on the phone. Android will ask to allow
   "Install unknown apps" for the browser/files app — allow it once.
4. Open **Dan Learns Stuff Network** from the app drawer and press play on an
   episode. Lock the phone: audio keeps playing, and the lock screen shows
   play/pause and ±15s controls.
5. Tip: also set the app's battery use to **Unrestricted** (Settings → Apps →
   Dan Learns Stuff Network → Battery) so Samsung's background limits don't
   interfere.

Every push to `main` rebuilds the APK and updates the **Latest build**
release, so reinstalling from Releases always gets the newest version.

## New episodes

Shows are still made in Muse. After new episodes are added to the network
app there, the app is shared again from Muse and this app picks them up
automatically (it loads the published link). Nothing needs rebuilding here
for a new episode.

## Building locally

- JDK 17, Android SDK with platform 35 and build-tools 35.
- `./gradlew assembleDebug` (or `gradle assembleDebug` if you have Gradle
  8.10+ installed — no wrapper is checked in, so the CI installs Gradle
  itself). The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Icon

The launcher icon is a simplified vector recreation of the DLS monogram badge
(orange ring, teal disc, glowing bulb on dark navy). To use the real badge
artwork instead, drop `ic_launcher.png` (and `ic_launcher_round.png`) into
`app/src/main/res/mipmap-xxxhdpi/` and delete the `mipmap-anydpi-v26`
adaptive-icon files.
