#!/usr/bin/env bash
# Fails if any native library packaged in the APK has LOAD segments aligned below 16 KB.
# Google Play requires 16 KB page-size support for apps targeting Android 15 and newer.
set -euo pipefail

apk="${1:?usage: $0 path/to/app.apk}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! unzip -q "$apk" 'lib/*' -d "$work" 2>/dev/null; then
  echo "No native libraries in $apk"
  exit 0
fi

fail=0
checked=0
while IFS= read -r lib; do
  checked=$((checked + 1))
  while read -r align; do
    if (( align < 16384 )); then
      echo "FAIL: ${lib#"$work"/} has LOAD alignment $((align)) (< 16384)"
      fail=1
    fi
  done < <(readelf -lW "$lib" | awk '$1 == "LOAD" { print $NF }')
done < <(find "$work/lib" -name '*.so')

if (( fail )); then exit 1; fi
echo "OK: $checked native librar$([ "$checked" -eq 1 ] && echo y || echo ies) aligned for 16 KB pages"
