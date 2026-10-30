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
    exec ./gradlew "${gradle_args[@]}" :data:assembleDebugAndroidTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest
fi

# Optional non-UI integration checks against a device the developer started.
exec ./gradlew "${gradle_args[@]}" :data:connectedDebugAndroidTest :app:connectedDemoDebugAndroidTest
