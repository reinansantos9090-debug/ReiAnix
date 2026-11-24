#!/usr/bin/env bash
# Static packaged-artifact checks; no emulator, network or signing key is needed.
set -euo pipefail
apk="$1"
aab="${2:-}"
bundletool="${3:-}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -s "$apk"
test -x "$sdk/build-tools/36.0.0/zipalign"
command -v readelf >/dev/null
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

check_elf() {
  local archive="$1" listing entry segment_count alignment offset virtual
  listing="$(unzip -Z1 "$archive")"
  local count=0
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    [[ "$entry" =~ ^([A-Za-z0-9_.-]+/)?lib/[A-Za-z0-9_-]+/[A-Za-z0-9_.+-]+\.so$ ]] || {
      echo "Unexpected native library path: $entry" >&2; return 1;
    }
    unzip -p "$archive" "$entry" > "$tmp/library.so"
    test -s "$tmp/library.so"
    readelf -lW "$tmp/library.so" > "$tmp/segments"
    segment_count=0
    while read -r offset virtual alignment; do
      [[ "$offset" =~ ^0x[0-9a-fA-F]+$ && "$virtual" =~ ^0x[0-9a-fA-F]+$ && "$alignment" =~ ^0x[0-9a-fA-F]+$ ]]
      if (( alignment < 16384 || (virtual - offset) % 16384 != 0 )); then
        echo "16 KB incompatible ELF segment: $entry ($alignment)" >&2
        return 1
      fi
      segment_count=$((segment_count + 1))
    done < <(awk '$1=="LOAD" {print $2, $3, $NF}' "$tmp/segments")
    test "$segment_count" -gt 0
    count=$((count + 1))
    printf 'ELF_16KB=PASS %s\n' "$entry"
  done < <(grep -E '^([^/]+/)?lib/[^/]+/[^/]+\.so$' <<< "$listing" || true)
  printf 'NATIVE_LIBRARIES=%s archive=%s\n' "$count" "$(basename "$archive")"
  NATIVE_COUNT="$count"
}

"$sdk/build-tools/36.0.0/zipalign" -c -P 16 4 "$apk"
echo 'APK_ZIP_ALIGNMENT=PASS'
check_elf "$apk"

if [[ -n "$aab" ]]; then
  test -s "$aab"
  test -s "$bundletool"
  check_elf "$aab"
  java -jar "$bundletool" dump config --bundle="$aab" > "$tmp/bundle-config"
  if (( NATIVE_COUNT > 0 )); then
    grep -Fq 'PAGE_ALIGNMENT_16K' "$tmp/bundle-config" || {
      echo "Bundle does not request 16 KB ZIP alignment." >&2; exit 1;
    }
    echo 'AAB_PAGE_ALIGNMENT_16K=PASS'
  else
    echo 'AAB_NATIVE_ALIGNMENT=NOT_APPLICABLE_NO_NATIVE_LIBRARIES'
  fi
fi
echo 'STATIC_16KB_CHECK=PASS (runtime validation remains required)'
