# WordCraft on Android

> **Last reviewed:** 2026-11-07 · **Last updated:** 2026-11-07 · **Change:** major (first committed version: the tablet app, its build, signing and install steps, and what it lacks)

WordCraft for Android tablets is a native Kotlin and Jetpack Compose app over the same Rust engine
as the desktop and web builds. The engine runs in-process through a small JNI bridge, so a command
that works on the desktop (`format.bold`, `edit.paste`, `file.save`) is the same command on the
tablet. Code is in [`apps/wordcraft-android`](../apps/wordcraft-android): the bridge crate
(`src/`) and the Gradle project beside it (`app/`). The architecture overview is
[`architecture.md`](architecture.md).

## What it does today

- Pages drawn by the engine's own renderer, with pinch zoom.
- Tap, double-tap and long-press selection; a caret and selection handles.
- The soft keyboard with composition and Gboard suggestions kept in the document, hardware keys
  and Ctrl shortcuts, and the system clipboard.
- A ribbon: a hand-made Home tab, the catalog tabs Insert to View with their commands that need no
  parameters, and command search.
- File menu: New, Open, Recent, Save, Save As, Export PDF, Share and Close, through Android's file
  picker.
- An unsaved-changes guard on every action that would replace the document, and crash recovery:
  the engine keeps recovery copies, and the app offers to restore them at the next start.

## Requirements

- Android 8.0 (API 26) or later. The release targets API 35.
- A 64-bit ARM (arm64-v8a) tablet. The default build also includes x86_64 so the same APK runs on
  the emulator.
- Storage for the APK: 175 MB with both ABIs, or about 85 MB for an arm64-only build. Nearly all
  of it is the engine library (84 MB for arm64-v8a, 89 MB for x86_64, stored uncompressed so
  Android maps it from the APK), which embeds the fonts and English proofing data.

## Not on the tablet yet

- **Dialogs.** A command that needs one shows "... isn't on the tablet yet". Commands run by id
  never open dialogs, so only the ribbon, not the engine, lacks them.
- **Pen and ink input.**
- **Language-pack downloads.** The build embeds English proofing data (`CRAFT_PROOF_EMBED=en`);
  other languages' spelling and hyphenation are not available.
- **Several documents at once.** One document is open; opening another asks about unsaved changes.
- **Password-protected files.**
- **A phone layout.** The interface is laid out for tablets.

## Build

Toolchain on the machine that built the checked-in app:

| Part | Version |
|---|---|
| Rust | 1.99 stable, with targets `aarch64-linux-android` and `x86_64-linux-android` (`rustup target add ...`) |
| `cargo-ndk` | 4.1.2 (`cargo install cargo-ndk`) |
| Android NDK | 30.0.16248370 (the Gradle project pins it, so AGP strips symbols from the engine library) |
| Android SDK | platform 35, build-tools 35.0.0; `ANDROID_HOME` points at it |
| JDK | 21 (the Kotlin and Java sources compile for 17) |
| Gradle | 8.11.1, from the wrapper (`apps/wordcraft-android/gradlew`) |
| Android Gradle Plugin / Kotlin | 8.7.3 / 2.1.0 |
| Jetpack Compose | BOM 2024.12.01 |

Gradle runs `cargo ndk` for each ABI before it builds the app, so one command builds everything.
The same optional inputs as the desktop build apply: `CRAFT_I18N_DIR` (craft-i18n, see
[`README.md`](../README.md#quick-start)) and `CRAFT_FONTS_DIR` (craft-fonts, whose Carlito and
friends keep Word documents paginated like Word). Without them the app still builds, with system
fonts and the built-in English word list. Set `CRAFT_PROOF_EMBED=en` (Gradle sets it for you) so
only English proofing data goes into the library. Use a separate `CARGO_TARGET_DIR` from the
desktop build; each ABI's engine takes about 3 minutes the first time.

```sh
cd apps/wordcraft-android
export ANDROID_HOME=$HOME/Android/Sdk
export CRAFT_I18N_DIR=$PWD/../../../craft-i18n CRAFT_FONTS_DIR=$PWD/../../../craft-fonts
./gradlew assembleDebug -Pabis=x86_64                    # emulator debug build
./gradlew assembleRelease -Pabis=arm64-v8a               # tablet release, arm64 only
./gradlew assembleRelease -Pabis=arm64-v8a,x86_64        # one APK for tablets and the emulator
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Unit tests: `./gradlew testDebugUnitTest`;
the bridge's Rust tests run with the workspace (`cargo test -p wordcraft-android`).

### Signing

Android installs only signed APKs. The release build reads the key from Gradle properties, so it
stays out of the repository (`*.jks` is gitignored). Make a key once, outside the checkout:

```sh
keytool -genkeypair -keystore ~/.android/wordcraft-release.jks -alias wordcraft \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=WordCraft"
```

Then put these in `~/.gradle/gradle.properties` (mode 600; `keytool` asks for the passwords):

```properties
WORDCRAFT_KEYSTORE=/home/you/.android/wordcraft-release.jks
WORDCRAFT_KEYSTORE_PASSWORD=...
WORDCRAFT_KEY_ALIAS=wordcraft
WORDCRAFT_KEY_PASSWORD=...
```

Without `WORDCRAFT_KEYSTORE` the release APK is unsigned and Android refuses to install it.
Check a signed APK with `$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-release.apk`.

**Back up the key and its passwords.** An installed copy updates only to an APK signed with the
same key; if the key is lost, every user must uninstall (losing the app's data) before they can
install a build signed with a new one. Never commit the keystore or the properties file. A debug
build is signed with a different key, so uninstall it before installing a release.

## Install

```sh
adb install -r apps/wordcraft-android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n ai.storyteller.wordcraft/.MainActivity --ez sample true   # open the sample document
```

Or copy the APK to the tablet and open it there (allow installs from that file manager when
Android asks). The app also registers for opening `.docx`, `.doc`, `.odt`, `.rtf`, `.txt`, `.md`
and `.html` files from other apps.

## How it works

- **One session, one thread.** The engine keeps a single `wordcraft_engine::Session` for the
  process. All calls go through Kotlin's `Engine`, which runs them in order on one thread
  (`wordcraft-engine`, 16 MB stack, since layout and import recurse deeply). It catches every
  `Throwable` and turns it into an error message; the document stays open.
- **Three native functions.** `Native.call(method, paramsJson)` returns JSON, `Native.render(page,
  scale)` returns a page as pixels and `Native.save(name)` returns the document's bytes in the
  format the name's extension picks. They are the only JNI entry points
  ([`src/exports.rs`](../apps/wordcraft-android/src/exports.rs)) and the only unsafe code in
  WordCraft; each hands straight to safe Rust in `src/lib.rs`. A panic becomes a Java exception,
  not a crash. R8 keeps the `Native` class by name in the release build.
- **JSON methods.** Every reply is `{"ok": ...}` or `{"error": "..."}`. The methods are `init`
  (starts or finds the session; opens recovery in the app's files directory), `run` (a command id
  and params, as `engine.execute` on the desktop's control channel), `view` (pages, caret,
  selection, format state, the text around the caret for the input method), `commands` (the
  catalog for the ribbon and command search), `shortcut` (a key chord through the keymap), `paste`,
  `ime.deleteSurrounding`, `markSaved` and `recovery.tick` / `recovery.list`.
- **Files.** Android hands the app a stream, not a path, so open sends the bytes with the command
  and save and export ask Kotlin to write through the file picker (`file.save`, `file.saveAs` and
  `file.exportPdf` come back as requests). The revision is checked on save so an edit made while
  the picker was open isn't marked saved.
- **Hostile input.** Parameters from Kotlin are treated like any untrusted input: bad JSON,
  junk numbers and path-like names return errors. `src/lib.rs` has a test that throws junk at
  every method.

## The emulator check

`apps/wordcraft-android/smoke.sh [out.png] [text]` boots the `wordcraft-tablet` virtual device if
none is connected (about 5 minutes cold; the comments in the script say how the AVD was made),
builds and installs the debug APK, opens the sample document, optionally taps the page and types,
and saves a screenshot. Release builds are checked by hand the same way: install the release APK,
open the sample, type, save, and watch `adb logcat` for `UnsatisfiedLinkError` and `FATAL`.

## Revision history

| Date | Change | Summary |
|---|---|---|
| 2026-11-07 | major | First committed version: what the tablet app does and lacks, the toolchain, the release signing setup, install and the emulator check |
