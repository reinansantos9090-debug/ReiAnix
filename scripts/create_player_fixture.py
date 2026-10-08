#!/usr/bin/env python3
"""Create the deterministic local MP4 used by Android player instrumentation tests."""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path,
                        default=Path("android/app/src/androidTest/assets/player_fixture.mp4"))
    args = parser.parse_args()
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise SystemExit("ffmpeg is required to generate the deterministic player fixture")

    output = args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y",
        "-f", "lavfi", "-i", "color=c=black:s=64x64:r=8",
        "-t", "30",
        "-an",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-profile:v", "baseline",
        "-level", "1.0",
        "-movflags", "+faststart",
        str(output),
    ]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    if output.stat().st_size < 512:
        raise SystemExit(f"generated fixture is unexpectedly small: {output}")
    print(f"generated deterministic player fixture: {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
