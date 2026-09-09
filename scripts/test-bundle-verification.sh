#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/message487-bundle-test.XXXXXX")"
trap 'rm -rf "$fixture_dir"' EXIT
mkdir -p "$fixture_dir/content/base/manifest" "$fixture_dir/content/base/dex"
for entry in BundleConfig.pb base/manifest/AndroidManifest.xml base/resources.pb base/dex/classes.dex; do
    printf 'Synthetic test entry' > "$fixture_dir/content/$entry"
done
jar --create --file "$fixture_dir/unsigned.aab" -C "$fixture_dir/content" .
verify=(java "$project_dir/scripts/VerifyBundle.java")
"${verify[@]}" "$fixture_dir/unsigned.aab" unsigned >/dev/null
printf 'Synthetic-test-password' > "$fixture_dir/password"
keytool -genkeypair -alias fixture -keyalg RSA -keystore "$fixture_dir/key.p12" \
    -storepass:file "$fixture_dir/password" -dname CN=BundleTest -validity 1 >/dev/null 2>&1
fingerprint="$(keytool -exportcert -alias fixture -keystore "$fixture_dir/key.p12" \
    -storepass:file "$fixture_dir/password" | shasum -a 256 | cut -d ' ' -f 1)"
cp "$fixture_dir/unsigned.aab" "$fixture_dir/signed.aab"
jarsigner -keystore "$fixture_dir/key.p12" -storepass:file "$fixture_dir/password" \
    "$fixture_dir/signed.aab" fixture >/dev/null
"${verify[@]}" "$fixture_dir/signed.aab" "$fingerprint" >/dev/null
expect_rejected() {
    if "${verify[@]}" "$@" >/dev/null 2>&1; then
        echo "Bundle verification accepted an invalid fixture" >&2
        exit 1
    fi
}
expect_rejected "$fixture_dir/unsigned.aab" "$fingerprint"
expect_rejected "$fixture_dir/signed.aab" unsigned
expect_rejected "$fixture_dir/signed.aab" "${fingerprint}00"
cp "$fixture_dir/signed.aab" "$fixture_dir/appended.aab"
printf 'Unsigned appended payload' > "$fixture_dir/content/base/extra.pb"
jar --update --file "$fixture_dir/appended.aab" -C "$fixture_dir/content" base/extra.pb
expect_rejected "$fixture_dir/appended.aab" "$fingerprint"
cp "$fixture_dir/signed.aab" "$fixture_dir/metadata.aab"
mkdir -p "$fixture_dir/content/META-INF"
printf 'Unsigned appended metadata' > "$fixture_dir/content/META-INF/extra.txt"
jar --update --file "$fixture_dir/metadata.aab" -C "$fixture_dir/content" META-INF/extra.txt
expect_rejected "$fixture_dir/metadata.aab" "$fingerprint"
printf 'Tampered entry' > "$fixture_dir/content/base/dex/classes.dex"
jar --update --file "$fixture_dir/signed.aab" -C "$fixture_dir/content" base/dex/classes.dex
expect_rejected "$fixture_dir/signed.aab" "$fingerprint"
jar --create --file "$fixture_dir/incomplete.aab" -C "$fixture_dir/content" BundleConfig.pb
expect_rejected "$fixture_dir/incomplete.aab" unsigned
echo "Passed bundle verification: signatures, certificate, tampering, unsigned additions and structure"
