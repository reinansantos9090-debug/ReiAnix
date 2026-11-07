#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

tmp_ids="$(mktemp)"
trap 'rm -f "$tmp_ids"' EXIT

echo "Collecting distinct AniList IDs from imported descriptor records..."
docker compose exec -T postgres psql \
  -U postgres -d postgres -At -v ON_ERROR_STOP=1 \
  -c 'SELECT DISTINCT anilist_id::text FROM files WHERE anilist_id IS NOT NULL ORDER BY 1;' \
  > "$tmp_ids"

mapfile -t ids < "$tmp_ids"
if [[ "${#ids[@]}" -eq 0 ]]; then
  echo "No AniList IDs were found in the imported descriptor records." >&2
  exit 1
fi

echo "Refreshing AniList metadata for ${#ids[@]} IDs in batches of 50..."
for ((offset = 0; offset < ${#ids[@]}; offset += 50)); do
  chunk=("${ids[@]:offset:50}")
  csv="$(IFS=,; printf '%s' "${chunk[*]}")"
  echo "AniList metadata batch $((offset + 1))-$((offset + ${#chunk[@]})) of ${#ids[@]}"
  docker compose exec -T api node script/anilist.ts --anime "$csv"
done

echo "AniList metadata hydration completed. Run filter-adult-entries.sql before loading any vector into Milvus."
