# RMG Timer (Kotlin Android)

A native Android stopwatch for garment RMG time study, using the supplied RMG Timer runner/stopwatch logo and navy-blue, electric-blue and gold visual theme.

## Features
- Two multifunction buttons: left button Start/Pause/Resume; right button tap Lap and long-press Stop/Save.
- Hardware keys: Volume Up toggles Start/Pause/Resume; Volume Down adds a Lap; holding Volume Down stops and opens Save.
- Centisecond stopwatch using Android monotonic elapsed realtime.
- Date, lap quantity, average lap and best lap.
- Lap breakdown and locally saved session history.
- Name a session and export its report as PDF.
- GitHub Actions builds a debug APK.

## Build on GitHub
1. Upload the contents of this folder to the repository root (not the enclosing folder).
2. Open Actions and run **Build RMG Timer APK**, or push to `main`/`master`.
3. Download the `RMG-Timer-debug-apk` artifact from the completed workflow.

## Build locally
Open this folder in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then Build > Build APK(s).

Application ID is kept as `com.chronoflow.stopwatch` to preserve the existing package identity. The launcher display name is **RMG Timer**.
