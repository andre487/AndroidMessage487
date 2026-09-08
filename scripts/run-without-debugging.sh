#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    echo "Usage: $0"
    echo "Start the development emulator, build and install the debug APK, and launch without a debugger."
    exit 0
fi
if [[ $# -ne 0 ]]; then
    echo "Unknown argument: $1" >&2
    exit 1
fi

if [[ -f "$HOME/.zshrc.extra" ]]; then
    # VS Code launched from Finder may not inherit the interactive shell environment.
    source "$HOME/.zshrc.extra"
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && "$(uname -s)" == Darwin ]]; then
    sdk_dir="$HOME/Library/Android/sdk"
fi
export ANDROID_HOME="$sdk_dir"
serial=$("$project_dir/scripts/emulator.sh" --background)
adb="$sdk_dir/platform-tools/adb"

cd "$project_dir"
bundle exec fastlane android debug_artifact
"$adb" -s "$serial" install -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
"$adb" -s "$serial" shell am clear-debug-app
"$adb" -s "$serial" shell am force-stop life.andre.message487
"$adb" -s "$serial" shell am start -W -n life.andre.message487/.MainActivity
