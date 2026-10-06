# Scanner scope boundary

ReiAnix treats a configured library source as the authoritative discovery boundary.

The normal library scan path is currently SAF-only. MediaStore and broad storage remain diagnostic/native capabilities and are not accepted as library sources.

For SAF, membership is based on the persisted tree authority plus the canonical tree Document ID. Child Document IDs must equal the tree root or begin with the root plus a real `/` segment. Prefix lookalikes such as `primary:AnimeBackup` do not belong to `primary:Anime`.

For filesystem sources used by legacy/local traversal, membership is checked with canonical `realpath` + `commonpath`. This rejects symlink escapes and sibling directories such as `AnimeBackup`.

A scanner result is not trusted solely because it came from MediaStore, filesystem traversal, or SAF. The native batch gateway and library service re-check source membership before SQLite ingestion.

There is no fallback from a missing, invalid, or revoked library source to the device-wide storage root.

earlier validation stage 35 is responsible for reconciling legacy rows that may have been imported before this boundary was enforced. earlier validation stage 34 does not delete them.
