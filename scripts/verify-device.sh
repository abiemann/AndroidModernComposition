#!/usr/bin/env bash
set -euo pipefail

mode="${1:-test}"
if (( $# > 1 )) || [[ "$mode" != build && "$mode" != test ]]; then
    printf 'Usage: %s [build|test]\n' "$0" >&2
    exit 2
fi

gradle_args=(
    --no-daemon
    --no-parallel
    --max-workers=2
    --stacktrace
    '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'
    '-Pkotlin.compiler.execution.strategy=in-process'
)

if [[ "$mode" == build ]]; then
    # Finish compilation and its single-use Gradle process before emulator boot.
    exec ./gradlew "${gradle_args[@]}" :data:assembleDebugAndroidTest :feature:assembleDebugAndroidTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest
fi

test_status=0
./gradlew "${gradle_args[@]}" :data:connectedDebugAndroidTest :feature:connectedDebugAndroidTest :app:connectedDemoDebugAndroidTest || test_status=$?

if (( test_status != 0 )); then
    diagnostics_dir='app/build/reports/androidTests/window-diagnostics'
    # Capture before the emulator shuts down; diagnostics must not mask test failure.
    if mkdir -p "$diagnostics_dir"; then
        adb pull /sdcard/Download/fieldnotes-ci-window.png "$diagnostics_dir/" || true
    fi
fi

exit "$test_status"
