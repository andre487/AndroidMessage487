# Signed APK releases

[English](../en/releases.md) | [Русский](../ru/releases.md)

Installing on a phone? See [APK installation and Android restrictions](apk-installation.md).

Run `bundle exec fastlane android release_artifacts` with JDK 21 and Android SDK 36. The lane runs
Android JVM/Compose tests, debug/release lint and a signed release build, then checks the certificate,
package ID, version and non-debuggable flag. Outputs are `dist/release/message487-<version>.apk`,
R8 `mapping.txt` and `SHA256SUMS`. Keep the mapping with its exact APK when diagnosing crashes.

Signing follows MegaProxy's environment contract with the `MESSAGE487_` prefix:

| Variable | Source/default |
| --- | --- |
| `MESSAGE487_KEYSTORE_PATH` | `~/AndroidApkKey` locally; restored temporary file in CI |
| `MESSAGE487_KEY_PASSWORD_FILE` | `~/.my-tokens/android-key-password` locally; temporary file in CI |
| `MESSAGE487_KEYSTORE_PASSWORD` | Exported by the release script from the password file |
| `MESSAGE487_KEY_ALIAS` | `key0` locally; `ANDROID_KEY_ALIAS` secret in CI |
| `MESSAGE487_KEY_PASSWORD` | Password-file contents unless explicitly supplied |
| `MESSAGE487_EXPECTED_CERT_SHA256` | Expected public certificate fingerprint, pinned in the script |
| `MESSAGE487_RELEASE_DIR` | `dist/release` |

Gradle reads only the four signing environment variables (path, store password, alias and key
password). Partial signing configuration fails closed. The PR `checks` lane rejects signing inputs
and verifies an unsigned release APK. Passwords are not command-line arguments and must never be
printed, committed or passed through Gradle `-P` properties.

GitHub Secrets use the same names as MegaProxy: `ANDROID_SIGNING_KEY_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The release workflow
restores the key/password with private permissions and deletes those temporary files even on failure.
Its build job has read-only repository permissions; only the separate publication job can write a
GitHub Release. PR workflows do not consume signing secrets.

## Verification and publication

- Push a `release-check/*` tag to build and verify a signed APK in GitHub Actions without publishing
  a Release. The signed APK, checksums, mapping and test reports are available as Actions artifacts.
- After the workflow is merged into the default branch, manual dispatch also builds artifacts only.
- For publication, increment `versionCode`, set the intended `versionName` in `app/build.gradle.kts`,
  and merge the reviewed change after all required PR checks pass. Push the matching `v<versionName>`
  tag. The workflow requires the tag commit to be contained in `main` and rejects a version mismatch.
  It publishes the verified APK, mapping and checksums to GitHub Releases. An existing Release is
  not overwritten by a rerun.

The first configured version is `0.0.1` with `versionCode = 1`. Later releases must increase
`versionCode` to support Android upgrades. Keep using the same signing key for installed users.
