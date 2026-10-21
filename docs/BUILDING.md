# Building FireSMS

This guide covers local Android development and CI checks for FireSMS.

## Requirements

- JDK 17.
- Android SDK with API 35 installed.
- Android SDK Build Tools installed.
- Android NDK 27.0.12077973 (for stripping native libraries bundled by dependencies).
- Android Studio, or command-line Android SDK tools.
- Git.

The project uses the Gradle 8.14.4 wrapper with Android Gradle Plugin 8.13.2, so you do not need to install Gradle globally. Keep the wrapper on a compatible Gradle 8.x release; AGP 8.x uses dependency APIs deprecated in Gradle 9.

`gradle/gradle-daemon-jvm.properties` selects JDK 17 for the build daemon, even if your shell defaults to a newer Java version. Install JDK 17 and set `JAVA_HOME` to it if Gradle cannot discover it automatically.

## Clone the project

```bash
git clone git@github.com:PepeByte/FireSMS.git
cd FireSMS
```

If you cloned over HTTPS, use the HTTPS URL instead.

## Android SDK setup

Gradle must know where your Android SDK is installed.

### Option 1: `ANDROID_HOME`

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

### Option 2: `local.properties`

Create a file named `local.properties` in the project root:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

`local.properties` is ignored by Git and should not be committed.

Install the required SDK packages (or select the same versions in Android Studio's SDK Manager):

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973"
```

## Build commands

### Run unit tests

```bash
./gradlew testDebugUnitTest
```

### Run Android lint

```bash
./gradlew lintDebug
```

Lint reports are written under:

```text
app/build/reports/lint-results-debug.html
```

### Build debug APK

```bash
./gradlew assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Run the full standard check

```bash
./gradlew check
```

### Clean build outputs

```bash
./gradlew clean
```

## Installing on a connected device

Enable USB debugging on your Android device, then run:

```bash
./gradlew installDebug
```

Or build the APK and install it with Android Studio or `adb`.

## Release builds

A release build can be created with:

```bash
./gradlew assembleRelease
```

The current project enables minification for release builds. A publishable release APK/AAB should be signed with a private Android signing key. Do not commit keystores, signing passwords, or `keystore.properties`.

### Publish a GitHub Release

`.github/workflows/android-release.yml` builds, tests, lints, and signs a release APK, then uploads it and `SHA256SUMS` to a GitHub Release titled `Release vX.Y.Z`.

First, add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore (`base64 -w 0 release.jks` on Linux). |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password. |
| `RELEASE_KEY_ALIAS` | Signing key alias. |
| `RELEASE_KEY_PASSWORD` | Signing key password. |

Use your existing release signing key if the app has already been distributed. Keep this key backed up privately: Android updates must use the same signing key. If you do not have a key yet, create one locally (this prompts for passwords):

```bash
keytool -genkeypair -v -keystore "$HOME/firesms-release.jks" \
  -alias firesms -keyalg RSA -keysize 2048 -validity 10000
```

Update `versionName` and increment `versionCode` in `app/build.gradle.kts`, then commit with a title exactly like `Release v1.0.2` and push to `main`:

```bash
git add app/build.gradle.kts # Also stage any other release changes.
git commit -m "Release v1.0.2"
git push origin main
```

The workflow must be included in the pushed source. It checks the **latest commit in the push**; other commit titles are skipped. The version in the title must match `versionName` (for example, `Release v1.0.2` for `1.0.2`). Optional commit-body text is allowed.

The action automatically creates tag `v1.0.2` at that exact commit and publishes the GitHub Release with the signed APK and checksum. **No manual tag creation or push is needed.** Tag-only pushes do not trigger this workflow.

Adding the workflow does not retroactively release earlier commits. Re-running a successful workflow replaces its APK/checksum assets without overwriting existing release notes. If the version tag already points at a different commit, publishing fails: use a new version instead of moving an existing release tag.

## Common problems

### `SDK location not found`

Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/path/to/android/sdk`.

### `Unable to strip the following libraries`

Install the pinned NDK into the same SDK configured above:

```bash
sdkmanager "ndk;27.0.12077973"
./gradlew clean assembleDebug
```

The NDK supplies `llvm-strip` for native libraries included by Compose and DataStore. Without it, Gradle packages those libraries unstripped; no blanket warning suppression or symbol-retention workaround is needed.

### Java version errors

Use JDK 17. Check your version with:

```bash
java -version
```

### Android licenses not accepted

Run:

```bash
yes | sdkmanager --licenses
```

### Dependency or Gradle cache issues

Try:

```bash
./gradlew clean
./gradlew --refresh-dependencies
```

## CI expectations

Pull requests and pushes to any branch should pass:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow is defined in `.github/workflows/android-ci.yml`.
