# The Last Stand 2 Android release preparation

The first public release is **v0.1.0**, Android version code **2**, package `io.github.frostie77.laststand2`. The stable feed starts empty. Use a clean checkout and the Windows prerequisites in [BUILDING.md](BUILDING.md).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-public-release.ps1
```

The helper builds and signs the release using `%USERPROFILE%\.android\The-Last-Stand-2-Release.keystore` with alias `the-last-stand-2-release`. It creates that key on the owner's PC for the first release. Keep a private backup of the keystore and its `.certificate.sha256` companion, and retain the password. Use the same key for all public updates. [Android app signing](https://developer.android.com/studio/publish/app-signing)

The helper verifies alignment, v2 and v3 signatures, the app identity, launcher icon resource, exact web assets and pinned Ruffle files. It rejects bundled SWFs and stages the following in `release/The-Last-Stand-2-v<version>-<source prefix>-<APK hash prefix>/`.

- The signed Android APK and its SHA-256 checksum
- `release-notes.md` for the GitHub draft
- `android-stable.json` generated from the exact APK and signing certificate

## Review and publish

1. Smoke-test the signed APK on a device. Check the launcher icon, import the verified SWF, start a night fight, try movement, aiming, fire and reload, and check that progress survives reopening. The public APK uses the same package and a different signing key from Debug. Preserve the Debug app's game, settings and saves before replacing it on the same device.
2. Assemble a GitHub release draft with title **The Last Stand 2 Android v0.1.0**, tag `v0.1.0` and target commit from the staged `source_commit`. Use the staged release notes and attach the tested signed APK and checksum file.
3. Review the draft and publish it after the device test. Verify the public asset name, size and SHA-256 against the staged feed.
4. Replace `updates/android-stable.json` with the staged feed once the APK is public. The feed's source commit stays tied to the APK build. Check **Updates** from the signed app.

For a later release, increase the version name and version code, update the notes, and verify that an older publicly signed APK can see the new update. Windows CI exercises signing with a disposable key. Public candidates use the owner's key.
