# Building FireSMS

This guide covers local Android development and CI checks for FireSMS.

## Requirements

- JDK 17.
- Android SDK with API 35 installed.
- Android SDK Build Tools installed.
- Android Studio, or command-line Android SDK tools.
- Git.

The project uses the Gradle wrapper, so you do not need to install Gradle globally.

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

Automatic release publishing is not configured yet.

## Common problems

### `SDK location not found`

Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/path/to/android/sdk`.

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

Pull requests and pushes to `main` should pass:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow is defined in `.github/workflows/android-ci.yml`.
