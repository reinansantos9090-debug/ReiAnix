# ReiAnix — Release Certification

## Evidence boundary

The release certification is split into two evidence classes:

1. **Source/build/APK evidence** from `scripts/release_certification.py` and `build_apk.yml`.
2. **Runtime Android evidence** from `.github/workflows/android_instrumented.yml`.

A build, unit-test run, packaged APK inspection, or source contract is never promoted to a runtime PASS.

## Runtime matrix

The instrumented workflow runs on GitHub-hosted Android emulators for:

- API 30 (Android 11)
- API 36 (Android 16)

The matrix uses the rendered Flet Android project from the same repository and executes:

    ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace

The emulator job fails when the instrumented suite fails. A timeout or an unavailable device is not converted into a success result.

Runtime evidence artifacts include the connected-test log, ADB device state, SDK level, Activity/Window state, and logcat.

## Static release certification

`python scripts/release_certification.py` remains the deterministic source/build/APK certification runner. It records Python test counts, Android unit tests, APK hashing, packaged manifest inspection, native-host DEX checks, and explicit no-device limitations.

Its no-device result is intentionally separate from the runtime matrix.

## Git and architecture integrity

`python scripts/audit_release.py --root .` validates:

- tracked-file presence in the CI worktree;
- absence of the competing `views/player_view.py`;
- singleton native player/mailbox/index components;
- canonical storage state names;
- MediaStore/SAF/Broad storage host files;
- the central consumption threshold;
- catalog identification fields;
- runtime matrix presence;
- shell failure-suppression patterns;
- silent `except Exception: pass` handlers.

The audit reports `exit 0` locations for human branch review rather than treating every `exit 0` as an error.

## Historical regressions

The Android instrumented suite reuses the existing native tests for:

- SAF picker Back/cancel behavior;
- external Settings Back behavior;
- MainActivity lifecycle;
- NativeMailbox atomic event envelopes;
- NativeIndex partial-generation preservation;
- MediaStore-backed NativePlayerActivity playback;
- immersive system bars;
- player gestures and pinch fit/zoom;
- visual and system Back;
- Picture-in-Picture where supported.

The test fixtures use locally created MediaStore content and do not require remote video sources.

The previously reported files `66619.mp4`, `66621.mp4`, and `66625.mp4` are not claimed as validated unless a runtime environment actually provides them.

## Final classification

The final result is based on evidence, not on the existence of code alone.

Allowed final classifications for the earlier validation stage 3 report:

- VALIDADO
- PARCIALMENTE VALIDADO
- FALHOU
- NÃO VALIDÁVEL

A runtime area without a connected emulator/device remains **NÃO VALIDÁVEL**.

A successful APK build does not by itself certify storage, SAF, lifecycle, Flet scrolling, or the player.
