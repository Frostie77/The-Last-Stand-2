# Building the development APK

Windows prerequisites: Git, JDK 17 (or Android Studio's JBR), Android SDK platform 35, build tools 35.0.0 and platform-tools. Connect and authorize the Odin through USB debugging to install automatically.

```powershell
git clone --recurse-submodules https://github.com/Frostie77/The-Last-Stand-2.git
cd The-Last-Stand-2
powershell -ExecutionPolicy Bypass -File .\build-test.ps1
```

The helper synchronizes the pinned FrostFrontend submodule, downloads checksum-verified Gradle 8.11.1 and Ruffle 0.7.1 when needed, builds Debug, checks that no SWF is bundled, and installs with `adb install -r` if an authorized device is connected. It does not open the app. Otherwise install `app/build/outputs/apk/debug/app-debug.apk` manually. Import the verified SWF using the app's picker.

For preparation without a build/install, add `-PrepareOnly`. Use the same PC/debug key for subsequent test updates to retain app data.

## Checks

- `python3 tests/test_control_profile.py` validates the actual Activity's controller profile with the shared frontend.
- `node tests/test_input.cjs` checks canvas pointer offsets under scaling, fire/movement ownership, cancellation and pause/background interaction.
- `python3 tests/test_updates.py` exercises the production updater with controlled HTTPS responses and Android API stubs.
- `gradle :frostfrontend:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` runs Android tests and builds both variants after the pinned runtime is prepared.

GitHub Actions runs these checks and verifies package identity, minimum SDK, HTML, runtime hashes and the absence of SWFs in both APKs. CI's Debug artifact is for testing; use the Windows helper for a stable local debug signing key.
