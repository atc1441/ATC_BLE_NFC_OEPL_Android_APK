# Building

## Requirements

- JDK 17 (the Android Gradle Plugin 8.5 will not run on anything older, and
  Gradle 8.14 does not accept 23 yet)
- Android SDK with platform 34 and any build-tools 34+
- No Android Studio needed; the Gradle wrapper is checked in

Point the build at your SDK once, in `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

```bash
./gradlew test             # JVM unit tests
./gradlew assembleDebug    # debug APK, signed with the local debug key
./gradlew assembleRelease  # unsigned release APK
```

The release APK lands in `app/build/outputs/apk/release/` and is **unsigned** —
`app/build.gradle.kts` deliberately carries no `signingConfig`. Signing happens
in CI instead, which keeps the signing key out of the build files and lets these
sources stay byte-identical to the ones they are mirrored from.

To sign one by hand:

```bash
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks my-release.jks --ks-key-alias my-alias --out app.apk aligned.apk
```

Instrumented tests under `app/src/androidTest/` need a connected device or
emulator and are not part of CI:

```bash
./gradlew connectedAndroidTest
```

## Releases

`.github/workflows/release.yml` runs on every push. It stamps `versionCode` and
`versionName` from the run number, runs the unit tests, assembles the release
APK, signs it, and — for pushes to the default branch — publishes it as a
GitHub release tagged `v1.0.<run number>`.

The version is patched at build time rather than committed, so `versionCode`
always climbs (a phone refuses an upgrade whose `versionCode` went backwards)
without any commit needing to touch the Gradle files.

### Required repository secrets

Signing is skipped, with a warning, if `KEYSTORE_BASE64` is empty — the build
still succeeds and publishes an APK marked `-unsigned`, which Android will
refuse to install. For signed releases, set all four under
**Settings → Secrets and variables → Actions**:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | the release keystore, base64 encoded, on a single line |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | password of that key |

To produce `KEYSTORE_BASE64` from a keystore:

```bash
base64 -w0 my-release.jks            # Linux
base64 -i my-release.jks | tr -d '\n'  # macOS
```

Keep the keystore file itself backed up somewhere outside this repository. It
cannot be regenerated: lose it and no future build can produce an APK that
upgrades an already-installed one, every user has to uninstall first.
