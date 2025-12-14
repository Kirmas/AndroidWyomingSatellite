#!/bin/sh
# Push all files from test_user_model to the connected Android device's Download folder
# Usage: ./push_test_user_models.sh
# No hardcoded device IP, works with the current adb device

set -e

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Please install Android Platform Tools."
  exit 1
fi

if ! adb get-state 1>/dev/null 2>&1; then
  echo "No device connected. Please connect a device via adb."
  exit 1
fi

for f in test_user_model/*; do
  [ -f "$f" ] || continue
  echo "Pushing $f to /sdcard/Download/"
  adb push "$f" "/sdcard/Download/"
done

echo "All test user models pushed."
