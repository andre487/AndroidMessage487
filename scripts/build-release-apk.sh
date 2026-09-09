#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$project_dir/scripts/java-toolchain.sh"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
: "${MESSAGE487_KEYSTORE_PATH:=$HOME/AndroidApkKey}"
: "${MESSAGE487_KEY_PASSWORD_FILE:=$HOME/.my-tokens/android-key-password}"
: "${MESSAGE487_KEY_ALIAS:=key0}"
: "${MESSAGE487_RELEASE_DIR:=$project_dir/dist/release}"
: "${MESSAGE487_EXPECTED_CERT_SHA256:=8a014a2a558a75b5f900ee0c33cd50f24b7432734912406699fc08866747f822}"
export ANDROID_HOME

if [[ ! -f "$MESSAGE487_KEYSTORE_PATH" || ! -f "$MESSAGE487_KEY_PASSWORD_FILE" ]]; then
    echo "Release keystore or password file is missing" >&2
    exit 1
fi
keystore_password="$(<"$MESSAGE487_KEY_PASSWORD_FILE")"
if [[ -z "$keystore_password" ]]; then
    echo "Keystore password file is empty" >&2
    exit 1
fi
: "${MESSAGE487_KEY_PASSWORD:=$keystore_password}"
keytool -list -keystore "$MESSAGE487_KEYSTORE_PATH" \
    -storepass:file "$MESSAGE487_KEY_PASSWORD_FILE" -alias "$MESSAGE487_KEY_ALIAS" >/dev/null
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
if [[ ! -x "$apksigner" || ! -x "$aapt" ]]; then
    echo "Android build tools 36.0.0 are required" >&2
    exit 1
fi
version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"/\1/p' "$project_dir/app/build.gradle.kts")"
version_code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)/\1/p' "$project_dir/app/build.gradle.kts")"
if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ || ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
    echo "A stable versionName and positive versionCode are required" >&2
    exit 1
fi

export MESSAGE487_KEYSTORE_PATH MESSAGE487_KEY_ALIAS MESSAGE487_KEY_PASSWORD
export MESSAGE487_KEYSTORE_PASSWORD="$keystore_password"
cd "$project_dir"
# Separate invocations ensure clean finishes before generated-resource tasks start.
./gradlew clean --no-daemon
./gradlew testDebugUnitTest lintDebug lintRelease assembleRelease bundleRelease --no-daemon
apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
actual_fingerprint="$("$apksigner" verify --verbose --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ "$actual_fingerprint" != "$MESSAGE487_EXPECTED_CERT_SHA256" ]]; then
    echo "Unexpected release signing certificate" >&2
    exit 1
fi
badging="$("$aapt" dump badging "$apk")"
if ! grep -Fq "package: name='life.andre.message487' versionCode='$version_code' versionName='$version_name'" <<< "$badging"; then
    echo "Release APK package or version does not match the project" >&2
    exit 1
fi
if grep -q '^application-debuggable' <<< "$badging"; then
    echo "Release APK must not be debuggable" >&2
    exit 1
fi
bundle="$project_dir/app/build/outputs/bundle/release/app-release.aab"
java "$project_dir/scripts/VerifyBundle.java" "$bundle" "$MESSAGE487_EXPECTED_CERT_SHA256"
mkdir -p "$MESSAGE487_RELEASE_DIR"
# Only remove artifacts owned by this script so old APKs cannot enter a new release.
find "$MESSAGE487_RELEASE_DIR" -maxdepth 1 -type f \
    \( -name 'message487-*.apk' -o -name 'message487-*.aab' -o -name message487.apk -o -name message487.aab -o -name mapping.txt -o -name SHA256SUMS \) -delete
cp "$apk" "$MESSAGE487_RELEASE_DIR/message487-$version_name.apk"
cp "$apk" "$MESSAGE487_RELEASE_DIR/message487.apk"
cp "$bundle" "$MESSAGE487_RELEASE_DIR/message487.aab"
cp "$project_dir/app/build/outputs/mapping/release/mapping.txt" "$MESSAGE487_RELEASE_DIR/mapping.txt"
(
    cd "$MESSAGE487_RELEASE_DIR"
    shasum -a 256 message487*.apk message487.aab mapping.txt > SHA256SUMS
)
echo "Verified signed release APK, App Bundle and checksums: $MESSAGE487_RELEASE_DIR"
