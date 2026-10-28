#!/usr/bin/env bash
set -euo pipefail

test_status=0
./gradlew --no-parallel --stacktrace :data:connectedDebugAndroidTest :feature:connectedDebugAndroidTest :app:connectedDemoDebugAndroidTest || test_status=$?

if (( test_status != 0 )); then
    diagnostics_dir='app/build/reports/androidTests/window-diagnostics'
    # Capture before the emulator shuts down; diagnostics must not mask test failure.
    if mkdir -p "$diagnostics_dir"; then
        adb pull /sdcard/Download/fieldnotes-ci-window.png "$diagnostics_dir/" || true
    fi
fi

exit "$test_status"
