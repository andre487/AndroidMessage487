#!/bin/bash
set -euo pipefail

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && "$(uname -s)" == Darwin ]]; then
    sdk_dir="$HOME/Library/Android/sdk"
fi
if [[ -z "$sdk_dir" || ! -x "$sdk_dir/emulator/emulator" ]]; then
    echo "Set ANDROID_HOME to an Android SDK with the emulator installed." >&2
    exit 1
fi

avd_name=Message487_API_35
while read -r serial state; do
    if [[ "$serial" == emulator-* && "$state" == device ]]; then
        current_avd=$("$sdk_dir/platform-tools/adb" -s "$serial" emu avd name | head -n 1 | tr -d '\r')
        if [[ "$current_avd" == "$avd_name" ]]; then
            echo "$avd_name is already running on $serial."
            exit 0
        fi
    fi
done < <("$sdk_dir/platform-tools/adb" devices)

case "$(uname -m)" in
    arm64|aarch64) abi=arm64-v8a ;;
    *) abi=x86_64 ;;
esac
system_image="system-images;android-35;google_apis;$abi"

if ! "$sdk_dir/emulator/emulator" -list-avds | rg -qx "$avd_name"; then
    "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" "$system_image"
    printf 'no\n' | "$sdk_dir/cmdline-tools/latest/bin/avdmanager" create avd \
        --name "$avd_name" --package "$system_image" --device pixel_7
fi

exec "$sdk_dir/emulator/emulator" -avd "$avd_name" -no-snapshot -no-boot-anim -gpu auto
