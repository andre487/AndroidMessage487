#!/bin/bash
set -euo pipefail

background=false
case "${1:-}" in
    --background) background=true ;;
    --help|-h)
        echo "Usage: $0 [--background]"
        echo "Create and launch the development emulator."
        echo "With --background, wait for Android to boot and print its ADB serial."
        exit 0 ;;
    "") ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
esac

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && "$(uname -s)" == Darwin ]]; then
    sdk_dir="$HOME/Library/Android/sdk"
fi
if [[ -z "$sdk_dir" || ! -x "$sdk_dir/emulator/emulator" ]]; then
    echo "Set ANDROID_HOME to an Android SDK with the emulator installed." >&2
    exit 1
fi

avd_name=Message487_API_35
adb="$sdk_dir/platform-tools/adb"
find_serial() {
    local serial state current_avd
    while read -r serial state; do
        if [[ "$serial" == emulator-* && "$state" == device ]]; then
            current_avd=$("$adb" -s "$serial" emu avd name | head -n 1 | tr -d '\r')
            if [[ "$current_avd" == "$avd_name" ]]; then
                echo "$serial"
                return
            fi
        fi
    done < <("$adb" devices)
}

serial=$(find_serial)
if [[ -n "$serial" ]]; then
    echo "$avd_name is already running on $serial." >&2
    if [[ "$background" == false ]]; then exit 0; fi
else

    case "$(uname -m)" in
        arm64|aarch64) abi=arm64-v8a ;;
        *) abi=x86_64 ;;
    esac
    system_image="system-images;android-35;google_apis;$abi"

    if ! "$sdk_dir/emulator/emulator" -list-avds | rg -qx "$avd_name"; then
        "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" "$system_image" >&2
        printf 'no\n' | "$sdk_dir/cmdline-tools/latest/bin/avdmanager" create avd \
            --name "$avd_name" --package "$system_image" --device pixel_7 >&2
    fi

    # Emulator 37.1.11 can hang in netsimd on macOS; virtual networking still works without radio simulation.
    emulator_options=(-avd "$avd_name" -no-snapshot -no-boot-anim -gpu auto
        -feature -WiFiPacketStream -feature -Uwb -feature -Nfc
        -netsim-args "--no-test-beacons --no-cli-ui --no-web-ui")
    if [[ "$background" == false ]]; then
        exec "$sdk_dir/emulator/emulator" "${emulator_options[@]}"
    fi
    emulator_log="${TMPDIR:-/tmp}/message487-emulator.log"
    echo "Starting $avd_name. Emulator log: $emulator_log" >&2
    # A separate session keeps the emulator alive after the task terminal exits.
    python3 - "$sdk_dir/emulator/emulator" "$emulator_log" "${emulator_options[@]}" <<'PY'
import subprocess as sp
import sys

with open(sys.argv[2], 'w') as log:
    sp.Popen([sys.argv[1], *sys.argv[3:]], stdin=sp.DEVNULL,
             stdout=log, stderr=sp.STDOUT, start_new_session=True)
PY
fi

echo "Waiting for Android to finish booting." >&2
for ((attempt = 0; attempt < 180; attempt++)); do
    if [[ -z "$serial" ]]; then serial=$(find_serial); fi
    if [[ -n "$serial" ]]; then
        boot_completed=$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [[ "$boot_completed" == 1 ]]; then
            echo "$serial"
            exit 0
        fi
    fi
    sleep 1
done
echo "Android emulator did not finish booting. Check ${emulator_log:-the emulator window}." >&2
exit 1
