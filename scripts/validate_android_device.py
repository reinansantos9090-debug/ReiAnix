#!/usr/bin/env python3
"""ReiAnix Android device validation runner.

This script intentionally refuses to claim a physical test when adb/device
access is unavailable. Interactive picker/player cases remain manual because
SAF and special-all-files settings are user-mediated Android surfaces.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = 'com.reiflix.reiflix_local'
API_MIN, API_MAX = 30, 36

def run(*args: str, check: bool = True) -> str:
    result = subprocess.run(args, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f'{" ".join(args)} failed: {result.stderr.strip()}')
    return result.stdout.strip()

def adb(*args: str, check: bool = True) -> str:
    return run('adb', *args, check=check)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--clean', action='store_true')
    args = parser.parse_args()
    if shutil.which('adb') is None:
        print('DEVICE_PENDING: adb não está disponível neste ambiente.')
        return 2
    devices = [line.split()[0] for line in adb('devices').splitlines() if line.strip() and not line.startswith('List of') and line.split()[1] == 'device']
    if not devices:
        print('DEVICE_PENDING: nenhum dispositivo/emulador Android autorizado.')
        return 2
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    print(f'DEVICE_API={sdk}')
    if not API_MIN <= sdk <= API_MAX:
        print(f'DEVICE_PENDING: API {sdk} fora da matriz Android {API_MIN}-{API_MAX}.')
        return 3
    if args.clean:
        adb('shell', 'pm', 'clear', PACKAGE, check=False)
    apk = args.apk.resolve()
    if not apk.is_file():
        raise SystemExit(f'APK inexistente: {apk}')
    print('INSTALLING=real-device')
    adb('install', '-r', str(apk))
    print('PACKAGE=' + PACKAGE)
    print('APK_SHA256=' + hashlib.sha256(apk.read_bytes()).hexdigest())
    package_dump = adb('shell', 'dumpsys', 'package', PACKAGE, check=False)
    version_code = next((line.strip() for line in package_dump.splitlines() if 'versionCode=' in line), 'unknown')
    print('PACKAGE_INFO=' + version_code)
    print('STARTING=MainActivity')
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'monkey', '-p', PACKAGE, '1')
    print('PHYSICAL_VALIDATED=INSTALL_START')
    print('MANUAL_REQUIRED=SAF picker, grant/revoke, MediaStore partial selection, volume detach/reconnect, and physical 66619.mp4/66621.mp4/66625.mp4 player matrix.')
    print('ARTIFACT_LOG_COMMAND=adb logcat -d -s [REIFLIX][SCANNER]:I [REIFLIX][SAF]:I [REIFLIX][MEDIASTORE]:I *:S')
    return 0

if __name__ == '__main__':
    sys.exit(main())
