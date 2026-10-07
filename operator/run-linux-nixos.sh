#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
exec nix-shell -p python3 python3Packages.pyside6 python3Packages.sounddevice python3Packages.numpy --run "python3 footdraw_operator.py --host 31.77.251.51"
