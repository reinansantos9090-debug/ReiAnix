# Kotlin Main Entrypoint Migration — Prompt 1/9

## Verdict

**BLOQUEADO — migration not completed.** The current repository does not yet have a safe, executable Kotlin replacement for main.py. Creating an unreferenced Main.kt would not change Android startup, and deleting main.py would break the currently configured Flet/Flutter runtime and existing Python contracts. For that reason, this step records the verified boundary instead of introducing a misleading wrapper or deleting the live entrypoint.

## Git snapshot inspected

- Reference branch: main
- Commit inspected before this report: 88ab56ffd872cb2b614bbf2b20470addc243d1c8
- Commit message: Restore main to Prompt 25 runtime dependency audit snapshot (preserve history)
- No existing Kotlin source, test, or workflow file was changed for this report.

## Verified startup path

1. android/app/src/main/AndroidManifest.xml registers .ReiAnixApplication as the application class.
2. The same manifest declares .MainActivity as the launcher and Leanback launcher, and also routes the reiflix://native VIEW deep link to it.
3. android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt declares class MainActivity : FlutterFragmentActivity(). Its source comment explicitly says the Flet-generated Android template requires this activity instead of the default Flutter activity. It also owns existing SAF, identity, player-intent, lifecycle, and native mailbox integration.
4. android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixApplication.kt declares ReiAnixApplication : FlutterApplication; onCreate() creates the existing ReiAnixAppDependencies root and starts its bootstrap.
5. main.py contains the Flet application entrypoint and ends with if __name__ == "__main__": ft.run(main). The file is approximately 7,984 lines in the inspected snapshot.
6. pyproject.toml declares flet==0.86.5 and configures Flet's Android packaging. The repository's existing development instructions and the generated Android host therefore still depend on the Python/Flet route.

These facts mean the executable Android host is still Flutter/Flet-based. An ordinary Kotlin main() function is not an Android launcher entrypoint, and a new class that is not wired into the manifest/activity path cannot replace the existing runtime.

## Responsibilities already present in Kotlin

The inspected tree already contains native startup/lifecycle work; this prompt should reuse it rather than create a parallel coordinator:

- ReiAnixAppDependencies.kt is the process-scoped native dependency root.
- ReiAnixAppBootstrap.kt provides idempotent process startup, database validation/recovery state, and controlled startup of existing native services.
- MainActivity.kt owns Android-specific SAF, permissions, player intents/results, and the existing Kotlin/NativeMailbox integration.
- NativePlayerActivity.kt, ReiAnixComposeLibraryHost.kt, ReiAnixComposeRoot.kt, and the existing Compose navigation/screens already provide substantial native functionality.

Their existence does not prove that the app can boot without Flutter/Flet, nor does it mean all Python-owned behavior has been migrated.

## Active Python/runtime dependencies that prevent safe deletion

- main.py still starts Flet and coordinates the legacy page/navigation and event-polling path.
- Existing Kotlin migration documentation describes Python-side LibraryStore, SettingsStore, Compose bridge adapters, ScanCoordinator, and residual screen/service callbacks as consumers that remain in the hybrid runtime.
- NativeMailbox and related bridges still form a compatibility boundary between native Android components and the Python application.
- pyproject.toml still declares the Flet runtime and its packaging configuration.
- Existing Python contracts read main.py directly. In particular, tests/test_main_startup_contract.py, tests/test_lifecycle_contract.py, and tests/test_navigation_lifecycle.py inspect its startup, lifecycle, mailbox polling, and navigation behavior. The tests must not be rewritten or deleted to conceal the remaining dependency.

## Why no Main.kt was added

The prompt forbids modifying pre-existing .kt files and also forbids presenting an isolated Kotlin file as a functional replacement. Under those constraints there is no safe integration point for a new Main.kt: the manifest still launches MainActivity, while that existing activity is a FlutterFragmentActivity. Adding an unreferenced object, a second Activity, or a dummy main() would not replace the runtime and would falsely suggest that the entrypoint had been migrated.

## Changes required before this migration can be completed

The following integration is required in a later, explicitly authorized change set:

1. Migrate the remaining Python-owned startup, event coordination, screen/navigation, and service behavior that is still required at launch, preserving the existing SQLite source of truth and native mailbox/event contracts until all consumers are moved.
2. Refactor the existing MainActivity.kt and ReiAnixApplication.kt so the launcher/application lifecycle no longer relies on Flutter base classes, while reusing their current Android behavior and native dependency graph. These are pre-existing Kotlin files, so the current prompt's no-edit constraint prevents that integration here.
3. Update and validate the Android launch/build/packaging configuration, including the Flet/Flutter template preparation path, so the native app can be built and launched without packaging or starting Python/Flet.
4. Re-check all source, test, script, Gradle, manifest, and workflow references; only remove main.py once an Android build and launch path without it has been demonstrated and the remaining Python contracts have been migrated without functional loss.

## Validation performed / not performed

- Repository structure, the full main.py response, manifest, relevant Kotlin startup files, app Gradle file, project Python packaging metadata, and existing lifecycle/startup contracts were inspected through GitHub.
- No tests were changed or added. No workflow or emulator infrastructure was changed or added.
- Kotlin/JVM tests and Android/Gradle/APK builds were not executed in this environment; this report does not claim they passed.
- main.py was not removed.
- Main.kt was not created, because the current integration constraint would leave it non-executable and misleading.
- The pre-existing .kt files, tests, and workflows were left untouched by this change.

This report is an evidence-based record of a blocked/partial stage, not a claim that the migration is complete.
