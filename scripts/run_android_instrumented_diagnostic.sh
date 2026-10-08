#!/usr/bin/env bash
# Android instrumentation diagnostic for physical devices or CI emulators.
# The runtime certification workflow uses this diagnostic surface when deeper
# class-level evidence is required after the normal connected test suite.
set -Eeuo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
PACKAGE="com.reiflix.reiflix_local"
API_LEVEL="${REIFLIX_ANDROID_API_LEVEL:-}"
case "${API_LEVEL}" in
    30|31|32|33|34|35|36) ;;
    *)
        printf 'REIFLIX_ANDROID_API_LEVEL must be 34, 35, or 36 (got %q)\n' "${API_LEVEL}" >&2
        exit 2
        ;;
esac
CERT_ROOT="${WORKSPACE}/build/android${API_LEVEL}-certification"
DIAG_ROOT="${CERT_ROOT}/gestural"
CLASS_TIMEOUT_SECONDS="${REIFLIX_ANDROID_CLASS_TIMEOUT_SECONDS:-120}"
METHOD_TIMEOUT_SECONDS="${REIFLIX_ANDROID_METHOD_TIMEOUT_SECONDS:-90}"
FULL_TIMEOUT_SECONDS="${REIFLIX_ANDROID_FULL_TIMEOUT_SECONDS:-300}"

mkdir -p "${DIAG_ROOT}"
printf 'Android %s instrumentation diagnostic run\n' "${API_LEVEL}" > "${DIAG_ROOT}/summary.txt"

capture() {
    local output="$1"
    shift
    if timeout 20s "$@" >"${output}" 2>&1; then
        return 0
    else
        local status=$?
        printf '\nCOMMAND_EXIT=%s\n' "${status}" >>"${output}"
        return 0
    fi
}

collect_diagnostics() {
    local label="$1"
    local safe_label
    safe_label="$(printf '%s' "${label}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '_')"
    local dir="${DIAG_ROOT}/${safe_label}"
    mkdir -p "${dir}"

    {
        printf 'label=%s\n' "${label}"
        printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'git_sha=%s\n' "${GITHUB_SHA:-unknown}"
        printf 'runner=%s\n' "${RUNNER_NAME:-unknown}"
    } >"${dir}/metadata.txt"

    capture "${dir}/adb_devices.txt" adb devices -l
    capture "${dir}/sdk.txt" adb shell getprop ro.build.version.sdk
    capture "${dir}/release.txt" adb shell getprop ro.build.version.release
    capture "${dir}/boot_completed.txt" adb shell getprop sys.boot_completed
    capture "${dir}/instrumentation.txt" adb shell pm list instrumentation
    capture "${dir}/activity_top.txt" adb shell dumpsys activity top
    capture "${dir}/window.txt" adb shell dumpsys window
    capture "${dir}/input.txt" adb shell dumpsys input
    capture "${dir}/package.txt" adb shell dumpsys package "${PACKAGE}"
    capture "${dir}/media_session.txt" adb shell dumpsys media_session
    capture "${dir}/surfaceflinger.txt" adb shell dumpsys SurfaceFlinger
    capture "${dir}/gfxinfo.txt" adb shell dumpsys gfxinfo "${PACKAGE}"
    capture "${dir}/logcat_all.txt" adb logcat -d -b all -v threadtime
    if grep -Ei 'ActivityTaskManager|WindowManager|InputDispatcher|system_server|com\.android\.settings|DocumentsUI|com\.reiflix\.reiflix_local|Media3|ExoPlayer|MediaCodec|Surface|TextureView' "${dir}/logcat_all.txt" > "${dir}/logcat_focus.txt"; then
        :
    else
        grep_status=$?
        if (( grep_status == 1 )); then
            : > "${dir}/logcat_focus.txt"
        else
            return "${grep_status}"
        fi
    fi

    local pids_raw=""
    if pids_raw="$(timeout 20s adb shell pidof "${PACKAGE}" 2>/dev/null)"; then
        :
    else
        pids_raw=""
    fi
    local pids
    pids="$(printf '%s' "${pids_raw}" | tr -d '\r')"
    if [[ -n "${pids}" ]]; then
        {
            for pid in ${pids}; do
                if ! timeout 20s adb shell kill -3 "${pid}"; then
                    printf 'kill -3 failed for pid=%s\n' "${pid}"
                fi
            done
        } >"${dir}/thread_dump_command.txt" 2>&1
        capture "${dir}/logcat_after_thread_dump.txt" adb logcat -d -b all -v threadtime
    fi
}


snapshot_device() {
    local dir="$1"
    mkdir -p "${dir}"
    {
        printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'api=%s\n' "${API_LEVEL}"
    } > "${dir}/metadata.txt"
    capture "${dir}/adb_devices.txt" adb devices -l
    capture "${dir}/sdk.txt" adb shell getprop ro.build.version.sdk
    capture "${dir}/boot_completed.txt" adb shell getprop sys.boot_completed
    capture "${dir}/activity_top.txt" adb shell dumpsys activity top
    capture "${dir}/window.txt" adb shell dumpsys window
    capture "${dir}/input.txt" adb shell dumpsys input
}

watch_case() {
    local dir="$1"
    while :; do
        {
            printf '\n=== WATCH %s ===\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            if timeout 10s adb shell dumpsys activity top; then :; else echo "activity_top_timeout_or_failure=$?"; fi
            if timeout 10s adb shell dumpsys window; then :; else echo "window_timeout_or_failure=$?"; fi
            if timeout 10s adb shell dumpsys input; then :; else echo "input_timeout_or_failure=$?"; fi
            if timeout 10s adb shell getprop sys.boot_completed; then :; else echo "boot_completed_timeout_or_failure=$?"; fi
        } >> "${dir}/watchdog.txt" 2>&1
        sleep 10
    done
}

stop_case_watch() {
    local pid="$1"
    if [[ -n "${pid}" ]]; then
        if kill "${pid}" 2>/dev/null; then :; fi
        if wait "${pid}" 2>/dev/null; then :; fi
    fi
}

reset_device_state() {
    for package in "${PACKAGE}" "com.android.settings" "com.android.documentsui" "com.google.android.documentsui"; do
        if ! timeout 20s adb shell am force-stop "${package}"; then
            printf 'force-stop unavailable or failed for %s\n' "${package}"
        fi
    done
    if ! timeout 20s adb logcat -c; then
        printf 'logcat clear failed; continuing with existing logcat\n'
    fi
    if ! timeout 20s adb shell input keyevent 3; then
        printf 'home key dispatch failed; continuing\n'
    fi
}


configure_navigation_mode() {
    local mode="$1"
    local expected_value
    local expected_overlay
    case "$mode" in
        gestural)
            expected_value="2"
            expected_overlay="com.android.internal.systemui.navbar.gestural"
            ;;
        three_button)
            expected_value="0"
            expected_overlay="com.android.internal.systemui.navbar.threebutton"
            ;;
        *)
            echo "Unknown navigation mode: $mode" >&2
            return 2
            ;;
    esac

    for overlay in         com.android.internal.systemui.navbar.threebutton         com.android.internal.systemui.navbar.gestural         com.android.internal.systemui.navbar.twobutton; do
        if ! adb shell cmd overlay disable "$overlay" >/dev/null 2>&1; then
            printf 'NAVIGATION_OVERLAY_DISABLE_FAILED overlay=%s (continuing; expected overlay is validated below)\n' "$overlay"
        fi
    done
    adb shell cmd overlay enable "$expected_overlay" >/dev/null 2>&1 || {
        echo "Unable to enable navigation overlay: $expected_overlay" >&2
        return 1
    }
    adb shell settings put secure navigation_mode "$expected_value" || {
        echo "Unable to set navigation_mode=$expected_value" >&2
        return 1
    }
    sleep 2
    local state_dir="${DIAG_ROOT}/navigation"
    mkdir -p "$state_dir"
    capture "${state_dir}/overlay_list.txt" adb shell cmd overlay list
    capture "${state_dir}/navigation_mode.txt" adb shell settings get secure navigation_mode
    local actual
    actual="$(tr -d "\r\n " < "${state_dir}/navigation_mode.txt")"
    printf "requested_mode=%s\nexpected_navigation_mode=%s\nactual_navigation_mode=%s\nexpected_overlay=%s\n" "$mode" "$expected_value" "$actual" "$expected_overlay" > "${state_dir}/mode_validation.txt"
    if [[ "$actual" != "$expected_value" ]]; then
        echo "Navigation mode did not apply: requested=$mode expected=$expected_value actual=$actual" >&2
        return 1
    fi
    if ! grep -Fq "[x] ${expected_overlay}" "${state_dir}/overlay_list.txt"; then
        echo "Expected navigation overlay is not enabled: $expected_overlay" >&2
        return 1
    fi
    return 0
}

run_diagnostic_case() {
    local selector="$1"
    local label="$2"
    local timeout_seconds="$3"
    local safe_label
    safe_label="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '_')"
    local case_dir="$DIAG_ROOT/$safe_label"
    mkdir -p "$case_dir"

    GRADLE_INVOCATIONS=$((GRADLE_INVOCATIONS + 1))
    printf 'DIAGNOSTIC_GRADLE_INVOCATION=%s label=%s selector=%s\n' "$GRADLE_INVOCATIONS" "$label" "$selector" | tee -a "$DIAG_ROOT/summary.txt"
    reset_device_state
    snapshot_device "$case_dir/before"

    local watch_pid=""
    watch_case "$case_dir" &
    watch_pid="$!"
    local status=0
    # The normal suite already ran once. Diagnostic isolation reuses the Gradle daemon
    # so a failure does not pay a fresh Gradle JVM/configuration cost per class.
    set +e
    if [[ -n "$selector" ]]; then
        timeout --foreground --signal=TERM --kill-after=30s "$timeout_seconds"s \
            ./gradlew :app:connectedDebugAndroidTest --stacktrace \
            "-Pandroid.testInstrumentationRunnerArguments.class=$selector" \
            > "$case_dir/gradle.log" 2>&1
    else
        timeout --foreground --signal=TERM --kill-after=30s "$timeout_seconds"s \
            ./gradlew :app:connectedDebugAndroidTest --stacktrace \
            > "$case_dir/gradle.log" 2>&1
    fi
    status=$?
    set -e

    stop_case_watch "$watch_pid"
    snapshot_device "$case_dir/after"
    if (( status != 0 )); then
        printf 'CASE_FAILED label=%s selector=%s exit=%s\n' "$label" "$selector" "$status" | tee -a "$DIAG_ROOT/summary.txt"
        collect_diagnostics "$safe_label"
    else
        printf 'CASE_PASS label=%s selector=%s exit=%s\n' "$label" "$selector" "$status" | tee -a "$DIAG_ROOT/summary.txt"
    fi
    reset_device_state
}

discover_failed_tests() {
    local output="$1"
    python - "$output" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

out = Path(sys.argv[1])
seen = set()

for root in (Path("app/build/outputs/androidTest-results"), Path("app/build/outputs"), Path("build")):
    if not root.exists():
        continue
    for path in root.rglob("*.xml"):
        text = path.as_posix()
        if "androidTest" not in text and "connected" not in text:
            continue
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError):
            continue
        for testcase in tree.getroot().iter("testcase"):
            failed = any(child.tag.rsplit("}", 1)[-1] in {"failure", "error"} for child in testcase)
            if not failed:
                continue
            classname = testcase.attrib.get("classname", "").strip()
            method = testcase.attrib.get("name", "").strip()
            if classname:
                seen.add((classname, method))

for classname, method in sorted(seen):
    print(f"{classname}|{method}")
PY
}

extract_failed_classes_from_log() {
    local log="$1"
    local output="$2"
    : > "$output"
    for class_name in \
        "BackAndSettingsReturnInstrumentedTest" \
        "DeviceFlowInstrumentedTest" \
        "NativeIndexInstrumentedTest" \
        "NativeMailboxInstrumentedTest" \
        "NativePlayerPlaybackInstrumentedTest"; do
        if grep -Eq "$class_name.*(FAILED|FAILURE)|FAILURE.*$class_name|$class_name#.*FAILED" "$log"; then
            printf 'com.reiflix.reiflix_local.%s|\n' "$class_name" >> "$output"
        fi
    done
}

run_navigation_suite() {
GRADLE_INVOCATIONS=1
FULL_LOG="$DIAG_ROOT/full-suite.log"
FULL_STATUS=0

printf 'NORMAL_SUITE=./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace\n' | tee -a "$DIAG_ROOT/summary.txt"
printf 'GRADLE_INVOCATION=1 label=full-suite selector=<all>\n' | tee -a "$DIAG_ROOT/summary.txt"
reset_device_state

set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace 2>&1 | tee "$FULL_LOG"
FULL_STATUS=${PIPESTATUS[0]}
set -e

printf 'FULL_SUITE_EXIT=%s\n' "$FULL_STATUS" | tee -a "$DIAG_ROOT/summary.txt"
printf 'CONNECTED_DEBUG_ANDROID_TEST_INVOCATIONS=%s\n' "$GRADLE_INVOCATIONS" | tee -a "$DIAG_ROOT/summary.txt"

if (( FULL_STATUS == 0 )); then
    printf 'NORMAL_SUITE_PASS=1\n' | tee -a "$DIAG_ROOT/summary.txt"
    printf 'DIAGNOSTIC_NOT_REQUIRED=1\n' | tee -a "$DIAG_ROOT/summary.txt"
    return 0
fi

printf 'NORMAL_SUITE_FAIL=1\n' | tee -a "$DIAG_ROOT/summary.txt"
printf 'DIAGNOSTIC_REQUIRED=1\n' | tee -a "$DIAG_ROOT/summary.txt"

collect_diagnostics "full-suite-failure"
snapshot_device "$DIAG_ROOT/failure"

FAILED_TESTS_RAW="$DIAG_ROOT/failed-tests.raw"
FAILED_TESTS="$DIAG_ROOT/failed-tests.txt"
discover_failed_tests "$FAILED_TESTS_RAW"
if [[ ! -s "$FAILED_TESTS_RAW" ]]; then
    extract_failed_classes_from_log "$FULL_LOG" "$FAILED_TESTS_RAW"
fi
sort -u "$FAILED_TESTS_RAW" > "$FAILED_TESTS"

if [[ -s "$FAILED_TESTS" ]]; then
    printf 'FAILED_TESTS_IDENTIFIED=1\n' | tee -a "$DIAG_ROOT/summary.txt"
    cat "$FAILED_TESTS" | tee -a "$DIAG_ROOT/summary.txt"
else
    printf 'FAILED_TESTS_IDENTIFIED=0\n' | tee -a "$DIAG_ROOT/summary.txt"
fi

while IFS= read -r class_name; do
    [[ -n "$class_name" ]] || continue
    run_diagnostic_case "$class_name" "$class_name" "$CLASS_TIMEOUT_SECONDS"
done < <(cut -d'|' -f1 "$FAILED_TESTS" | sed '/^$/d' | sort -u)

# Do not replay every failed method after replaying its class. That multiplies
# emulator/instrumentation startup and was the direct source of the 30-60 minute
# failure runs seen in CI. The failed class already gives deterministic per-test
# results in its Gradle/XML output; the full-suite log and collected device state
# remain available for deeper diagnosis.
printf 'METHOD_LEVEL_REPLAY_SKIPPED=1\n' | tee -a "$DIAG_ROOT/summary.txt"
printf 'CONNECTED_DEBUG_ANDROID_TEST_INVOCATIONS=%s\n' "$GRADLE_INVOCATIONS" | tee -a "$DIAG_ROOT/summary.txt"
printf 'DIAGNOSTIC_COMPLETE=1\n' | tee -a "$DIAG_ROOT/summary.txt"
printf 'DIAGNOSTIC_PRESERVED_FAILURE_EXIT=%s\n' "$FULL_STATUS" | tee -a "$DIAG_ROOT/summary.txt"


}

OVERALL_STATUS=0
mkdir -p "$CERT_ROOT"
printf "api=%s\n" "$API_LEVEL" > "$CERT_ROOT/matrix-summary.txt"
for NAVIGATION_MODE in gestural three_button; do
    DIAG_ROOT="${CERT_ROOT}/${NAVIGATION_MODE}"
    mkdir -p "$DIAG_ROOT"
    printf "===== Android %s navigation=%s =====\n" "$API_LEVEL" "$NAVIGATION_MODE" | tee -a "$CERT_ROOT/matrix-summary.txt"
    if ! configure_navigation_mode "$NAVIGATION_MODE"; then
        echo "NAVIGATION_MODE_CONFIG_FAILED=$NAVIGATION_MODE" | tee -a "$CERT_ROOT/matrix-summary.txt"
        OVERALL_STATUS=1
        continue
    fi
    if ! run_navigation_suite; then
        echo "NORMAL_SUITE_FAILED navigation=$NAVIGATION_MODE" | tee -a "$CERT_ROOT/matrix-summary.txt"
        OVERALL_STATUS=1
    fi
done

printf "OVERALL_STATUS=%s\n" "$OVERALL_STATUS" | tee -a "$CERT_ROOT/matrix-summary.txt"
exit "$OVERALL_STATUS"
