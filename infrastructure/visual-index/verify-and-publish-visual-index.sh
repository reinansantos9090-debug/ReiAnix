#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

psql_scalar() {
  docker compose exec -T postgres psql -U postgres -d postgres -At -v ON_ERROR_STOP=1 -c "$1" | tr -d '[:space:]'
}

total_eligible="$(psql_scalar "SELECT COUNT(*) FROM files WHERE anilist_id IS NOT NULL AND media_info IS NOT NULL AND scene_changes IS NOT NULL AND color_layout IS NOT NULL;")"
if [[ ! "$total_eligible" =~ ^[0-9]+$ ]] || [[ "$total_eligible" -eq 0 ]]; then
  echo "No eligible precomputed visual descriptors were found; HTTPS will remain disabled." >&2
  exit 1
fi

orphan_files="$(psql_scalar "SELECT COUNT(*) FROM files f LEFT JOIN anilist a ON a.id = f.anilist_id WHERE a.id IS NULL;")"
if [[ "$orphan_files" != "0" ]]; then
  echo "${orphan_files} file records have no AniList metadata; run hydrate-anilist-metadata.sh and filter-adult-entries.sql again." >&2
  exit 1
fi

adult_or_unrated="$(psql_scalar "SELECT COUNT(*) FROM anilist WHERE lower(COALESCE(json->>'isAdult', '')) NOT IN ('false', '0', 'no');")"
if [[ "$adult_or_unrated" != "0" ]]; then
  echo "${adult_or_unrated} adult or unclassified AniList metadata records remain; HTTPS will remain disabled." >&2
  exit 1
fi

failed_vectors="$(psql_scalar "SELECT COUNT(*) FROM files WHERE anilist_id IS NOT NULL AND media_info IS NOT NULL AND scene_changes IS NOT NULL AND color_layout IS NOT NULL AND loaded = FALSE;")"
if [[ "$failed_vectors" != "0" ]]; then
  echo "${failed_vectors} eligible descriptor records are marked loaded=false. Do not rerun the upstream loader blindly: reconcile any partial Milvus inserts from the failed batches before resetting those rows to NULL." >&2
  exit 1
fi

pending_vectors="$(psql_scalar "SELECT COUNT(*) FROM files WHERE anilist_id IS NOT NULL AND media_info IS NOT NULL AND scene_changes IS NOT NULL AND color_layout IS NOT NULL AND loaded IS NULL;")"
if [[ "$pending_vectors" != "0" ]]; then
  echo "${pending_vectors} eligible visual descriptor records have not been loaded into Milvus yet; finish the initial bulk-load and verify its logs." >&2
  exit 1
fi

echo "Validated ${total_eligible} general-audience visual descriptor records. Starting the HTTPS search proxy."
docker compose up -d caddy
echo "The ReiAnix visual search endpoint has been published. Confirm the HTTPS domain from .env and test POST /search before adding it to a release build."
