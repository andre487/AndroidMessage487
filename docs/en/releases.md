# Signed APK and App Bundle releases

[English](../en/releases.md) | [Русский](../ru/releases.md)

Run `bundle exec fastlane android release_artifacts` with JDK 21 and Android SDK 36.
The lane runs Android JVM/Compose tests and debug/release lint, then builds signed APK and AAB
from the same release variant. It checks the APK certificate, package/version and non-debuggable
flag, and verifies the certificate and signature of every AAB payload entry.

| Output in `dist/release/` | Purpose |
| --- | --- |
| `message487-<version>.apk` | Versioned signed APK |
| `message487.apk` | Byte-identical APK with a stable download filename |
| `message487.aab` | Signed Android App Bundle for manual Play Console upload |
| `mapping.txt` | R8 mapping for this exact build |
| `SHA256SUMS` | Checksums for both APK names, AAB and mapping |

The [permanent APK link](https://github.com/andre487/AndroidMessage487/releases/latest/download/message487.apk)
follows GitHub's latest published release, not the current branch. Existing versioned URLs keep
working. A copy of the original 0.0.1 APK provides the stable filename for that release;
it does not acquire features added after its tag.

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
and verifies unsigned release APK and AAB files. Passwords are not command-line arguments and must never be
printed, committed or passed through Gradle `-P` properties.

GitHub Secrets use the same names as MegaProxy: `ANDROID_SIGNING_KEY_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The release workflow
restores the key/password with private permissions and deletes those temporary files even on failure.
Its build job has read-only repository permissions; only the separate publication job can write a
GitHub Release. PR workflows do not consume signing secrets.

## Verification and publication

- Push a `release-check/*` tag to build and verify signed APK/AAB files in GitHub Actions without publishing
  a Release. The signed APK/AAB files, checksums, mapping and test reports are available as Actions artifacts.
- After the workflow is merged into the default branch, manual dispatch also builds artifacts only.
- For publication, increment `versionCode`, set the intended `versionName` in `app/build.gradle.kts`,
  and merge the reviewed change after all required PR checks pass. Push the matching `v<versionName>`
  tag. The workflow requires the tag commit to be contained in `main` and rejects a version mismatch.
  It publishes the verified APKs, AAB, mapping and checksums to GitHub Releases. An existing Release is
  not overwritten by a rerun.

The first configured version is `0.0.1` with `versionCode = 1`. Later releases must increase
`versionCode` to support Android upgrades. Keep using the same signing key for installed users.

## Upload to Google Play

1. Build release artifacts as above or download them from a verified release workflow run.
   Use a new `versionCode` for each Play upload; increase it in `app/build.gradle.kts` before building.
2. Create/select the Play Console app for `life.andre.message487` and configure Play App Signing.
   The AAB uses the same configured key as the GitHub APK. Confirm that Play accepts it as the
   upload key. To allow updates between GitHub and Play installs, plan the **app signing key**
   consistently; an upload key and a Play-generated app signing key are different roles.
   See [Android signing guidance](https://developer.android.com/studio/publish/app-signing).
3. Create an internal-testing release and upload `message487.aab`. An AAB is a publishing
   artifact; install the APK on phones, not the AAB. Keep `mapping.txt` with the build
   (AGP also embeds the R8 mapping in the bundle).
4. Add the icon and feature graphic from [Branding](../../assets/branding/README.md), real app
   screenshots, descriptions, privacy policy and Data safety answers. Complete Play's
   [SMS permissions declaration/review](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
   for `RECEIVE_SMS`; a successful AAB build does not establish store eligibility.
5. Review the internal release in Play Console before rolling it out.

CI builds and verifies the artifacts; it does not upload to Google Play or require a Play service
account. Native symbol packaging from MegaProxy is unnecessary here: this app has no native core.
The bundle signature verifier also rejects unsigned added entries, modified entries, missing
required bundle entries and unexpected certificates; its regression fixtures run in Android CI.
