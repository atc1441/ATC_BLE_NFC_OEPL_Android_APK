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
./gradlew assembleRelease  # release APK
```

The APK lands in `app/build/outputs/apk/release/`.

Instrumented tests under `app/src/androidTest/` need a connected device or
emulator and are not part of CI:

```bash
./gradlew connectedAndroidTest
```

## Signing

Every release APK carries the same signature no matter where it was built. That
is not cosmetic: Android identifies an app by package name **and** signing
certificate, so an APK signed with a different key counts as a different app.
Installing it over an existing copy fails, and the only way through is to
uninstall first — which throws away whatever the app had stored.

So `app/build.gradle.kts` has a single signing path that local builds and CI
both take. The key is never in the tree; it is found, in order, from:

1. the environment — `OEPL_KEYSTORE`, `OEPL_KEYSTORE_PASSWORD`, `OEPL_KEY_ALIAS`,
   `OEPL_KEY_PASSWORD`. This is how CI passes it.
2. `signing.properties` beside `settings.gradle.kts` (git-ignored):

   ```properties
   storeFile=/path/to/oeplnfc-release.jks
   storePassword=...
   keyAlias=oeplnfc
   keyPassword=...
   ```

With neither present, the release build logs a line saying so and produces an
**unsigned** APK rather than failing, so a fresh clone still builds. Android
will not install that APK.

The release certificate's SHA-256 digest is

```
76cb3e15b3fafb989bcfa47cd52073fd4f6d3c3465851fd141d40e2495ac81ec
```

and CI fails the build if a signed APK ever carries a different one — a wrong
key would force every installed copy to be uninstalled, so it is worth failing
loudly over.

## Version

`versionCode` and `versionName` come from `OEPL_VERSION_CODE` and
`OEPL_VERSION_NAME`, defaulting to `1` and `1.0`. A phone refuses an APK whose
`versionCode` is below the installed one, so if you sideload a local build over
one from CI, pass a `versionCode` at least as high as that build's:

```bash
OEPL_VERSION_CODE=1100 OEPL_VERSION_NAME=1.0.dev ./gradlew assembleRelease
```

Nothing in the tree is rewritten to set a version, which is why a CI build and a
local build of the same commit are otherwise the same build.

## Releases

`.github/workflows/release.yml` runs on every push. It picks a version from the
run number, runs the unit tests, builds and signs the release APK, verifies the
signature, and — for pushes to the default branch — publishes it as a GitHub
release tagged `v1.0.<run number>`.

### Required repository secrets

Set these under **Settings → Secrets and variables → Actions**. Signing is
skipped with a warning if `KEYSTORE_BASE64` is empty; the build still succeeds
and publishes an APK marked `-unsigned`.

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | the release keystore, base64 encoded, on a single line |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | password of that key |

To produce `KEYSTORE_BASE64` from a keystore:

```bash
base64 -w0 my-release.jks               # Linux
base64 -i my-release.jks | tr -d '\n'   # macOS
```

Keep the keystore backed up somewhere outside this repository. It cannot be
regenerated: lose it and no future build can produce an APK that upgrades an
already-installed one.
