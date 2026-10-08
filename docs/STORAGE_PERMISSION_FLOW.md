# ReiAnix — Storage / Permission Flow 3.0

This document records the hardened storage permission contract.

## Authority

Android is the authority for current permission state. Python does not persist a
boolean such as `permission_granted=True` as proof of current access.

The native capability pipeline is:

Android Storage APIs
→ StorageAuthorization / StorageCapabilities
→ NativeMailbox
→ Python capability snapshot
→ ScanCoordinator
→ native scanner
→ validated scan result
→ LibraryService
→ LibraryStore
→ controlled UI refresh.

## MediaStore

Android 13+ uses `READ_MEDIA_VIDEO`. Android 14+ also declares and requests
`READ_MEDIA_VISUAL_USER_SELECTED` so selected-video access is represented as
partial access. A partial grant is scan-capable but is never considered enough
for destructive MediaStore reconciliation.

The MediaStore scanner records per-volume generations and stages documents in
NativeIndex. A volume is reconciled only when its own generation is complete,
error-free, uncancelled, stable, and the current access state permits
reconciliation.

## SAF

`ACTION_OPEN_DOCUMENT_TREE` is used for user-selected directories. The result
is persisted with a read/persistable grant and validated again after persistence.
Cancel produces a cancellation event only; it does not start a scan or alter the
catalog.

Persisted tree inventory is checked from Android's actual persisted URI grants.
Provider failure or revocation marks the source unavailable; it does not delete
the media catalog.

## Broad Storage

`MANAGE_EXTERNAL_STORAGE` is optional and is checked with the Android
authoritative API. Settings are opened with native Android intents. Returning
from Settings rechecks the actual grant and only then asks ScanCoordinator for a
scan when the capability really changed.

## Scan safety

These states are never destructive reconciliation:

- partial access
- unavailable volume
- cancelled scan
- failed scan
- provider error
- permission denial
- incomplete coverage.

Only a validated complete generation for the affected scope can reconcile
missing items in that scope.

A missing removable volume is therefore not equivalent to a deleted file.

## Lifecycle

MainActivity is `singleTask` with `documentLaunchMode="never"`. Permission,
SAF and Broad Storage callbacks converge through the existing Activity Result
and lifecycle state. Ordinary `onResume`, player return and background/foreground
transitions do not trigger a full rescan.

NativeMailbox remains the transport boundary. ScanCoordinator remains the single
logical scan coordinator.

## Deduplication

MediaStore, SAF and Broad Storage may discover the same physical media. Native
identity/fingerprint logic is retained; The storage hardening does not replace NativeIndex,
LibraryStore, LibraryService, Media3 or NativeMailbox.

## References

- Android 14 selected photos/videos access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- SAF document/tree access and persisted URI permissions: https://developer.android.com/training/data-storage/shared/documents-files
- All-files access: https://developer.android.com/training/data-storage/manage-all-files
