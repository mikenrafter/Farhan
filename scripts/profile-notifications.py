#!/usr/bin/env python3
"""Capture Android diagnostics without resetting statistics or changing settings."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label", help="Scenario and checkpoint, e.g. categories-before")
    parser.add_argument("--serial", help="ADB device serial when multiple devices are connected")
    parser.add_argument("--package", default="ly.com.tahaben.farhan_alpha")
    parser.add_argument("--output", type=Path, default=Path("captures/notifications"))
    parser.add_argument("--note", default="", help="Build, enabled features, workload, and checkpoint")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.label) or args.label in (".", ".."):
        parser.error("label must contain only letters, digits, underscores, dots, or hyphens")
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("package must be a dotted Android package name")
    if not shutil.which("adb"):
        parser.error("adb is unavailable; run through nix develop -c python3 scripts/profile-notifications.py ...")

    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    try:
        device = subprocess.run(adb + ["get-serialno"], capture_output=True, text=True,
                                timeout=15, check=True).stdout.strip()
        installed = subprocess.run(adb + ["shell", "pm", "path", args.package],
                                   capture_output=True, text=True, timeout=15, check=True)
    except (subprocess.SubprocessError, OSError) as error:
        parser.error(f"cannot access device or installed package: {error}")
    if not installed.stdout.startswith("package:"):
        parser.error(f"package {args.package} is not installed or package lookup was denied")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    destination = args.output / f"{stamp}-{args.label}"
    destination.mkdir(parents=True, exist_ok=False, mode=0o700)
    commands = {
        "device": ["getprop", "ro.build.fingerprint"],
        "package": ["dumpsys", "package", args.package],
        "memory": ["dumpsys", "meminfo", args.package],
        "cpu": ["dumpsys", "cpuinfo"],
        "battery": ["dumpsys", "battery"],
        "batterystats": ["dumpsys", "batterystats", "--charged", args.package],
        "services": ["dumpsys", "activity", "services", args.package],
        "jobs": ["dumpsys", "jobscheduler", args.package],
        "frames": ["dumpsys", "gfxinfo", args.package, "framestats"],
    }
    manifest = {
        "started_at_utc": stamp, "device_serial": device, "package": args.package,
        "label": args.label, "note": args.note, "captures": {},
    }
    failed = False
    for name, command in commands.items():
        started = time.monotonic()
        record = {"command": adb + ["shell"] + command,
                  "started_at_utc": datetime.now(timezone.utc).isoformat()}
        try:
            result = subprocess.run(record["command"], capture_output=True,
                                    text=True, timeout=20)
            (destination / f"{name}.txt").write_text(result.stdout)
            (destination / f"{name}.stderr.txt").write_text(result.stderr)
            record["exit_code"] = result.returncode
            failed |= result.returncode != 0
        except (subprocess.TimeoutExpired, OSError) as error:
            record["error"] = str(error)
            failed = True
        record["elapsed_seconds"] = round(time.monotonic() - started, 3)
        manifest["captures"][name] = record
        (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(destination.resolve())
    if failed:
        print("Some captures failed; see manifest.json and stderr files.", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
