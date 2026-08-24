# KOReader Voice Pager (Android)

An Android port of the Windows tray app: say **"next"** or **"back"** into your phone and it
turns the page on your e-reader over KOReader's HTTP inspector plugin. Runs as a foreground
service so it keeps listening in the background, same as the original always-on tray icon.

## What changed vs. the Windows version, and why

| Windows (.NET / WinForms) | Android (Kotlin) | Why |
|---|---|---|
| System tray icon + context menu | Foreground service + notification | Android has no tray; a foreground service is the platform's "always running, visible status" primitive |
| `HttpClient` + `SocketsHttpHandler` (1 pooled connection, kept alive) | OkHttp + `ConnectionPool(1, ...)` (1 pooled connection, kept alive) | Same technique, same effect: no TCP handshake in the hot path |
| `NAudio.WaveInEvent`, tunable buffer ms | `AudioRecord` read loop, tunable chunk ms | Same "small chunks = low latency" idea, Android's native audio API |
| Vosk (.NET bindings) | Vosk (Android/JNI bindings) | Same recognition engine, same offline/local guarantee, same 2-3 word grammar restriction |
| Browse to a pre-unzipped model folder | Pick the model `.zip` directly; app unzips it into app storage | Android's scoped storage makes "browse to an arbitrary folder" awkward; picking a file and letting the app unpack it is the idiomatic equivalent and is actually less manual work for you |
| HKCU Run key ("start with Windows") | `BOOT_COMPLETED` receiver ("start on boot") | Direct platform equivalent |
| Always-on while PC is awake | Partial wake lock while listening + optional battery-optimization exemption | A phone's CPU can sleep even with the screen off; without this the mic loop would get starved. The Windows app never needed this because a PC doesn't sleep out from under a running app the same way |

Everything on the **latency-critical path is architecturally identical** to the Windows
version: grammar-restricted recognition, small audio chunks, acting on Vosk's *partial*
hypothesis instead of waiting for silence-endpointed "final" results, a pre-warmed kept-alive
HTTP connection, and firing the request straight out of the recognition callback before any
notification/UI work happens.

### Where this version can actually be a bit faster

- **`VOICE_RECOGNITION` audio source.** Android's audio HAL applies noise suppression/AGC
  tuned specifically for speech-recognition input on this source, which can make word
  boundaries a little cleaner (and thus the partial hypothesis lock in a little faster) than
  a generic mic capture.
- **Same-LAN phone-to-e-reader hop** is usually a shorter/cheaper radio hop than a
  PC-to-e-reader Wi-Fi hop in practice (phone and e-reader are more likely to be sitting right
  next to each other), though this depends entirely on your network.

Everything else is a wash or slightly Android-taxed (JNI call overhead into Vosk's native
library is marginally higher per-call than the .NET P/Invoke path, but at 10-300ms chunk
sizes this is noise compared to word-length audio).

### Floored-latency pass

Once the port was up and working, the hot path got tightened as far as it reasonably goes:

| Change | Why |
|---|---|
| Audio read/recognize loop moved to its own thread at `THREAD_PRIORITY_URGENT_AUDIO` | Was sharing `Dispatchers.Default` (a general coroutine pool) with the rest of the app; this is the same priority class Android's own audio drivers run at, so it isn't waiting behind unrelated background work |
| Page-turn HTTP call moved to its own dedicated, pre-started, max-priority thread (`KOReaderClient`'s `hotExecutor`, `VoiceForegroundService`'s `commandExecutor`) | No thread pool dispatch/scheduling delay between "word recognized" and "socket write" - the thread is already running and idle, waiting |
| `TCP_NODELAY` set explicitly on every socket the HTTP client opens | Nagle's algorithm can buffer small writes (like this one-line GET) for tens of ms waiting to coalesce them - not something you want on a single time-critical request |
| Timeouts tightened to 800/600/400/900ms (connect/read/write/call) | Same-LAN hop should never legitimately take longer than this; failing fast into the existing one-shot retry beats sitting in a long timeout |
| Keep-alive ping interval 20s → 15s | Slightly less time the pooled connection could go stale/idle-closed before the next word |
| Default "Reaction speed" 50ms → 20ms, floor lowered from 20ms → 10ms | Smaller chunks reach the recognizer sooner; raise it in Settings if a phone's mic/CPU can't keep up that low |

## 1. Set up KOReader (identical to the Windows version)

Same as before: **Tools → More tools → KOReader HTTP inspector**, turn it on, enable
**Auto start**, note the IP and port (default `8080`). Phone and e-reader must be on the same
Wi-Fi network.

## 2. Get a speech model

Download **`vosk-model-small-en-us-0.15.zip`** from
https://alphacephei.com/vosk/models (~40MB) onto your phone (or somewhere your phone can pick
it from, e.g. Downloads or Google Drive). **Don't unzip it** - the app does that for you.

## 3. Build the app

Two options - pick whichever's easier for you.

### Option A: Build on GitHub, no Android Studio needed

A workflow at `.github/workflows/build.yml` is already included. GitHub's own runners have
full internet access (this sandbox doesn't, which is why I couldn't compile this myself first -
see the note below), so they can pull the Android SDK and all dependencies and build a real
APK for you:

1. Create a new (can be private) GitHub repo and push this whole folder to it:
   ```
   cd KOReaderVoicePagerAndroid
   git init
   git add .
   git commit -m "Initial import"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo-name>.git
   git push -u origin main
   ```
2. On GitHub, open the repo's **Actions** tab - the "Build APK" workflow runs automatically
   on push (or trigger it manually with **Run workflow**).
3. When it finishes (green check), click into the run and download the
   **KOReaderVoicePager-debug-apk** artifact from the bottom of the page - that's a zip
   containing `app-debug.apk`.
4. Transfer that APK to your phone (email it to yourself, Google Drive, `adb install`, etc.)
   and install it. You'll need to allow "install from unknown sources" for whatever app you
   use to open it, since it's not from the Play Store.

This also means every push gets auto-built and any compile errors show up as a red X in the
Actions tab with full logs - much faster feedback than round-tripping through me.

### Option B: Build locally with Android Studio

```
File -> Open... -> select the KOReaderVoicePagerAndroid folder -> let Gradle sync
Run -> Run 'app' (with a device/emulator connected)
```

Minimum SDK 26 (Android 8.0), target/compile SDK 34. The Gradle wrapper (`gradlew`,
`gradlew.bat`, `gradle-wrapper.jar`) is checked in, so this should sync without Android
Studio needing to bootstrap anything first.

> **Note on this build:** I wrote and reviewed this against the documented Vosk-Android,
> OkHttp, and AndroidX APIs, but couldn't actually compile it myself (no network access from
> this sandbox to Google's Maven / Maven Central to pull the SDK and dependencies) - that's
> exactly what Option A's GitHub Actions workflow is for. If a build fails, paste me the error
> from either the Actions log or Android Studio and I'll fix it.

## 4. First run

1. Open the app. On first launch it'll ask for microphone and notification permissions.
2. Tap **Choose model .zip...** and pick the file from step 2. Wait for "Model ready."
3. Enter your reader's **IP address** and **port**, tap **Save**.
4. Tap **Start listening**. A persistent notification confirms it's running.
5. Optionally tap **Exclude from battery optimization** and enable **Start automatically on
   boot** - both make background listening more reliable, since Android is much more
   aggressive than Windows about suspending background apps.

## 5. Use it

Say **"next"** to go forward a page, **"back"** to go back - no wake word, always listening
once started, exactly like the Windows version.

## Troubleshooting

- **"Not listening: ..."** in the status text/notification: almost always either no model is
  installed yet, or microphone permission was denied - check both in the app.
- **Nothing happens when you talk:** tap **Test connection** and check the status; confirm
  IP/port match KOReader's HTTP inspector screen and both devices share a network.
- **Stops listening after a while:** exclude the app from battery optimization (button in
  Settings) - some phone manufacturers (Samsung, Xiaomi, etc.) have their own aggressive
  battery managers on top of stock Android that may need the app whitelisted there too.
- **Misfires / cut-off words:** adjust "Reaction speed" - lower for snappier response, higher
  if a slower mic is cutting off "next"/"back".

## Notes

- Same as the Windows version: everything runs locally. No cloud speech API, no data leaves
  your phone except the page-turn HTTP call to your own reader on your own LAN.
- Uses the same `GotoViewRel` dispatcher event a physical page-turn sends, so it behaves
  identically to a manual turn.
