#!/usr/bin/env bash
# Check that only the unchanged Android application ID retains the legacy brand.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle="android/app/build.gradle.kts"
app_id="$(sed -nE 's/.*applicationId = "([^"]+)".*/\1/p' "$gradle" | head -n 1)"
if [[ -z "$app_id" ]]; then
  echo "Android applicationId missing" >&2
  exit 1
fi
grep -Fq 'namespace = "com.reianix.reianix_local"' "$gradle" || {
  echo "Unexpected Kotlin namespace" >&2
  exit 1
}
old="$(cut -d. -f2 <<< "$app_id")"
old_hyphen="${old:0:3}-${old:3}"
old_underscore="${old:0:3}_${old:3}"
pattern="$old|$old_hyphen|$old_underscore"
app_id_escaped="${app_id//./\\.}"
status=0
while IFS= read -r -d '' file; do
  if grep -Eiq "$pattern" <<< "$file"; then
    echo "Legacy name remains in path: $file" >&2
    status=1
  fi
  if [[ ! -f "$file" ]]; then continue; fi
  while IFS= read -r finding; do
    [[ -z "$finding" ]] && continue
    line="${finding#*:}"
    # Only the stable Android application ID may contain the old name.
    line="${line//${app_id}/}"
    line="${line//${app_id_escaped}/}"
    if grep -Eiq "$pattern" <<< "$line"; then
      echo "Legacy name remains in source: $file:${finding%%:*}" >&2
      status=1
    fi
  done < <(LC_ALL=C grep -IinE "$pattern" -- "$file" || true)
done < <(git ls-files -z)
if [[ $status -ne 0 ]]; then
  exit 1
fi
echo "ReiAnix identity check passed; unchanged Android ID preserved."
