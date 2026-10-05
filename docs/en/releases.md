# Signed APK releases

[English](../en/releases.md) | [Русский](../ru/releases.md)

Installing on a phone? See [APK installation and Android restrictions](apk-installation.md).

Use [release automation](release-automation.md) to prepare the version and EN/RU changelog.
The guide covers manual secret setup and release dispatch.

Run `bundle exec fastlane android release_artifacts` with JDK 21 and Android SDK 36.
The lane runs Android JVM/Compose tests and debug/release lint, then builds a signed release APK. It checks the APK certificate, package/version and non-debuggable
flag.

| Output in `dist/release/` | Purpose |
| --- | --- |
| `message487-<version>.apk` | Versioned signed APK |
| `message487.apk` | Byte-identical APK with a stable download filename |
| `mapping.txt` | R8 mapping for this exact build |
| `SHA256SUMS` | Checksums for both APK names and mapping |

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
and verifies unsigned release APK files. Passwords are not command-line arguments and must never be
printed, committed or passed through Gradle `-P` properties.

GitHub Secrets use the same names as MegaProxy: `ANDROID_SIGNING_KEY_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The release workflow
restores the key/password with private permissions and deletes those temporary files even on failure.
Its build job has read-only repository permissions; only the separate publication job can write a
GitHub Release. PR workflows do not consume signing secrets.

## Verification and publication

- Push a `release-check/*` tag to build and verify signed APK files in GitHub Actions without publishing
  a Release. The signed APK files, checksums, mapping and test reports are available as Actions artifacts.
- After the workflow is merged into the default branch, manual dispatch also builds artifacts only.
- For publication, increment `versionCode`, set the intended `versionName` in `app/build.gradle.kts`,
  and merge the reviewed change after all required PR checks pass. Push the matching `v<versionName>`
  tag. The workflow requires the tag commit to be contained in `main`, the version to match the tag,
  and EN/RU changelogs for the current `versionCode`. GitHub Release uses these texts.
  It publishes the verified APKs, mapping and checksums to GitHub Releases. An existing Release is
  not overwritten by a rerun.

The first configured version is `0.0.1` with `versionCode = 1`. Later releases must increase
`versionCode` to support Android upgrades. Keep using the same signing key for installed users.

## F-Droid

Store descriptions, changelogs and artwork live in
[`fastlane/metadata/android`](../../fastlane/metadata/android). Update both locales
before tagging a release. The submission recipe is maintained in `fdroid/fdroiddata`,
not duplicated in this repository. Pin each build to the full release commit SHA,
use JDK 21, and compare against the versioned GitHub release APK with the expected
signing certificate. Keep dependency metadata disabled for APKs.
A successful GitHub build alone does not establish reproducibility: the F-Droid
build and binary comparison must pass before marking that verification complete.

Android baseline/startup profile tasks are disabled because AGP generated different
profile contents for identical DEX code in GitHub and F-Droid builds. Android still
optimizes the app through its normal runtime profiling; initial launches do not get
the bundled profile optimization. See the [F-Droid reproducibility guidance](https://f-droid.org/docs/Reproducible_Builds/#bug-baselineprof-not-deterministic).
