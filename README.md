# HN Hands-Free

A personal Android app that reads the Hacker News front page to you. For each
story it speaks the title and a short summary; on request it reads the full
article. It's built to be used with the screen off, controlled from headphone
or Bluetooth media buttons. It is sideloaded, not published on the Play Store.

## Controls

| Button     | During a summary        | During a full article     |
|------------|-------------------------|---------------------------|
| Next       | Skip to the next story  | Stop article, next story  |
| Previous   | Read the full article   | Restart the paragraph     |
| Play/Pause | Pause / resume          | Pause / resume            |

The notification, the lock screen and Bluetooth devices all behave the same.
The notification's extra buttons and the app also offer **Previous story** and
**Save for later**. If you press nothing, the next story follows automatically.

Stories you've heard (including skipped ones) are left out next time.

## How it works

- Stories come from the official HN Firebase API.
- Articles are downloaded and cleaned up with Readability4J. If that fails
  (paywall, PDF, video, JavaScript-only page), the summary is based on the post
  text and the top five comments instead, and says so.
- Summaries come from the Gemini API free tier (default model
  `gemini-3.5-flash-lite`, changeable in Settings). They're cached per story.
  Free-tier requests may be used by Google to improve its products.
- Speech is the phone's own text-to-speech, rendered to files and played
  through Media3/ExoPlayer, so it works with the media session.

See [PLAN.md](PLAN.md) for the design and the reasoning behind it.

## Requirements

- JDK 17 (the build uses it via `gradle.properties`, whatever your default Java is)
- Android SDK with platform 37 and build-tools 37
- A phone with Android 8.0 (API 26) or newer, with USB or wireless debugging on
- A Gemini API key from [Google AI Studio](https://aistudio.google.com/)
  (the free tier is enough)

On Manjaro/Arch:

```sh
sudo pacman -S jdk17-openjdk
# Android command-line tools, unpacked to ~/Android/Sdk/cmdline-tools/latest, then:
sdkmanager "platform-tools" "platforms/android-37.0" "build-tools/37.0.0"
```

`local.properties` (not committed) must point at the SDK:

```properties
sdk.dir=/home/you/Android/Sdk
```

## Build and install

Debug build, straight onto a connected phone:

```sh
./gradlew installDebug
```

Release build (faster, signed with your own key):

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then open the app, go to **More → Settings**, paste the Gemini API key and save.
The key is stored encrypted with a key from the Android Keystore and is never logged.

### Release signing key

The release build is signed with a key kept outside the repository. Create one once:

```sh
keytool -genkeypair -keystore ~/keystores/no-hand-sam-release.jks -storetype PKCS12 \
  -alias release -keyalg RSA -keysize 4096 -validity 10000
```

and add it to `local.properties`:

```properties
release.storeFile=/home/you/keystores/no-hand-sam-release.jks
release.storePassword=...
release.keyAlias=release
release.keyPassword=...
```

Without these entries `assembleRelease` still builds, but the APK is unsigned
and can't be installed.

**Back up the keystore and its password.** Updates only install over an app
signed with the same key. Debug and release builds use different keys, so
switching between them needs `adb uninstall de.reibisch.hnaudio` first, which
deletes the app's data (API key, heard stories, saved stories).

## Wireless debugging

Pair once (Developer options → Wireless debugging → Pair device with pairing code):

```sh
adb pair <phone-ip>:<pairing-port> <code>
adb connect <phone-ip>:<port>
```

After that the phone usually reconnects on its own; `adb devices` lists it
under its `adb-…._adb-tls-connect._tcp` name.

## Tests

```sh
./gradlew testDebugUnitTest
```

Unit tests cover article extraction (with hand-written HTML fixtures), HN item
parsing, prompt building, Gemini response parsing and text chunking.
