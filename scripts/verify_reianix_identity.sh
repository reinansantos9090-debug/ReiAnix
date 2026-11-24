#!/usr/bin/env bash
# Enforce the permanent Play identity and reject old branding in tracked text and paths.
set -euo pipefail
cd "$(dirname "$0")/.."
gradle="android/app/build.gradle.kts"
app_id="$(sed -nE 's/.*applicationId = "([^"]+)".*/\1/p' "$gradle" | head -n 1)"
if [[ "$app_id" != "com.reianix.app" ]]; then
  echo "Unexpected Android applicationId: $app_id" >&2
  exit 1
fi
grep -Fq 'namespace = "com.reianix.reianix_local"' "$gradle" || {
  echo "Unexpected Kotlin namespace" >&2
  exit 1
}
# Independent of applicationId: do not accidentally match the current brand.
pattern='rei[-_]?flix'
status=0
while IFS= read -r -d '' file; do
  if grep -Eiq "$pattern" <<< "$file"; then
    echo "Legacy name remains in path: $file" >&2
    status=1
  fi
  if [[ ! -f "$file" ]]; then continue; fi
  while IFS= read -r finding; do
    [[ -z "$finding" ]] && continue
    echo "Legacy name remains in source: $file:${finding%%:*}" >&2
    status=1
  done < <(LC_ALL=C grep -IinE "$pattern" -- "$file" || true)
done < <(git ls-files -z)
if [[ $status -ne 0 ]]; then
  exit 1
fi
echo "ReiAnix identity check passed: $app_id"
