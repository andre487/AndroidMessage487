# Tests and CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

Run the same suites locally and in GitHub Actions:

| Suite | Command | Coverage |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM logic, MockWebServer HTTP integration, Robolectric database/preferences/provider integration, Compose interactions, localization, manifest security, light/dark contrast, debug/release lint and APK/AAB builds, unsigned release verification |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | DevServer HTTP test-client behavior against a local HTTP server; pinned Black/isort style checks |
| n8n | `bundle exec fastlane android server_tests` | Live receive/validation, notification/SMS/test payloads, HTTP failure, invalid ACK and timeout |

For Python, create `.venv` with `python3 -m venv .venv` and install `requirements-dev.txt`.
For n8n, first run `docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
The server suite uses synthetic data and retains it in the development execution history.

`.github/workflows/ci.yml` runs all three jobs on pull requests, main pushes and manual dispatch.
Android XML/HTML test and lint reports are uploaded even if a check fails. Successful Android jobs
also publish a debug APK and unsigned release APK/AAB files. These checks never sign release artifacts.
Local success does not establish a GitHub run result for uncommitted/unpushed changes.

## Comparison with MegaProxy

The applicable categories match MegaProxy: JVM logic and Android integration, Compose interactions
under Robolectric, resource/security/UI contracts, Python tests and formatting, lint and builds.
Message487 also has a live Docker/n8n integration suite. MegaProxy's Go race tests and optional
native-parser fuzz lane apply to its Go/JNI networking core; Message487 has no native core.
MegaProxy's Python CI-history tests target scripts that Message487 does not have.

Compose tests use the real navigation, screens, ViewModel and preferences with a test Application that
suppresses startup workers and process-wide crash-handler installation. An explicit ViewModel
factory binds each test to its own Application; background completions are drained before assertions. Tests cover navigation/back,
connection validation and persistence, bulk application selection and diagnostic deletion. They do
not prove notification/SMS permission delivery, WorkManager/OS scheduling, real Android Keystore,
process death handling or email-client behavior. Those remain device/emulator checks; see
[diagnostics](diagnostics.md) for the crash/report scenario. Required hosted CI does not depend on
an Android emulator, following MegaProxy's approach to unreliable KVM availability.

Signed release verification runs the Android test/lint suite separately with signing inputs. PR
checks reject those inputs and remain unsigned. See [release automation](releases.md).
