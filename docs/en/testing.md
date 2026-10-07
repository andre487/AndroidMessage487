# Tests and CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

Run the same suites locally and in GitHub Actions:

| Suite | Command | Coverage |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM logic, MockWebServer HTTP integration, Robolectric database/preferences/provider integration, Compose interactions, localization, manifest security, light/dark contrast, debug/release lint and APK builds, unsigned release verification |
| Emulator | `ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests` | Real Android Keystore, encrypted preferences/SQLite queue and retry identity, Android HTTP/ACK/redirect behavior, FileProvider access boundaries on API 26/35 |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | DevServer HTTP test-client behavior against a local HTTP server, release automation with mocked APIs/local Git, pinned Black/isort style checks |
| n8n | `bundle exec fastlane android server_tests` | Live receive/validation, notification/SMS/test payloads, HTTP failure, invalid ACK and timeout |

For Python, create `.venv` with `python3 -m venv .venv` and install `requirements-dev.txt`.
For n8n, first run `docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
The server suite uses synthetic data and retains it in the development execution history.

`.github/workflows/ci.yml` runs Android, Python, n8n and separate API 26/35 emulator jobs on pull requests, main pushes and manual dispatch.
Manual dispatch with `release_dry_run_version` runs only the release dry run and skips these suites.
Android XML/HTML test and lint reports are uploaded even if a check fails. Successful Android jobs
also publish separate debug APK and unsigned release APK artifacts, expiring after 14 days.
The trusted `pr-artifacts-description.yml` workflow keeps their links in a marked PR-description
block, preserving author text and rejecting old heads/runs. It never executes PR code or posts
comments. It starts working after merge into the default branch. These checks never sign release artifacts.
Local success does not establish a GitHub run result for uncommitted/unpushed changes.

## Comparison with MegaProxy

The applicable categories match MegaProxy: JVM logic and Android integration, Compose interactions
under Robolectric, resource/security/UI contracts, Python tests and formatting, lint and builds.
Message487 also has a live Docker/n8n integration suite. MegaProxy's Go race tests and optional
native-parser fuzz lane apply to its Go/JNI networking core; Message487 has no native core.
Message487 has its own release-automation Python tests, but no MegaProxy change classifier
or ancestor-check history lookup.

Compose tests use the real navigation, screens, ViewModel and preferences with a test Application that
suppresses startup workers and process-wide crash-handler installation. An explicit ViewModel
factory binds each test to its own Application; background completions are drained before assertions. Tests cover navigation/back,
connection validation and persistence, bulk application selection and diagnostic deletion. They do
not prove notification/SMS permission delivery, WorkManager/OS scheduling, real Android Keystore,
process death handling or email-client behavior. Those remain device/emulator checks; see
[diagnostics](diagnostics.md) for the crash/report scenario. The separate emulator checks exercise real Keystore, storage, HTTP and provider APIs;
Robolectric still covers most screen interactions. Emulator CI requires KVM; startup or test
failure keeps the check red, with no automatic assertion retries.

Signed release verification runs the Android test/lint suite separately with signing inputs. PR
checks reject those inputs and remain unsigned. See [release automation](releases.md).

## Emulator runs and JUnit reports

Use a **disposable** Google APIs AVD named `message487-tests` or `message487-tests-26` /
`message487-tests-35`. Tests clear Message487 settings and the queue on that emulator.
Connect only that emulator and set `ANDROID_SERIAL` and `ANDROID_HOME`; the lane rejects
physical devices, other AVD names and additional connected devices. CI creates a fresh AVD
for each API and builds only debug/test APKs, without release signing secrets.

To create a local AVD, choose `arm64-v8a` on Apple Silicon or `x86_64` on Intel/Linux:

```sh
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd --name message487-tests-35 --package "system-images;android-35;google_apis;arm64-v8a" --device pixel_5
emulator -avd message487-tests-35 -no-snapshot
# In another terminal, using the actual serial from adb devices:
ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests
```

Repeat with API 26. Reopening stores is tested; process death, real SMS/notification capture,
WorkManager scheduling, OEM restrictions and upgrade of a signed APK remain device acceptance
scenarios. These debug-emulator results do not certify a release candidate.

Android uses Gradle JUnit XML. Python and the live n8n smoke test use `unittest-xml-reporting`;
n8n Node tests use Node's native JUnit reporter. Install `requirements-dev.txt` for both Python
and server lanes. Python XML is in `test-results/python/`, n8n XML in `test-results/server/`,
and device XML in `app/build/outputs/androidTest-results/connected/`. CI uploads reports even
after test failures; emulator evidence includes HTML and Logcat (14-day retention).

`test-reports.yml` publishes each suite as GitHub Checks and a collapsible Actions summary
as soon as its job finishes. Internal CI/release jobs call it directly; fork PR reports use
a trusted `workflow_run` after merge into the default branch. The reporter parses artifacts
without checking out or executing PR code. A test/build failure without XML remains a failed
CI job, never a passing test report. Signed-release JVM results are published separately.

For signed candidates, F-Droid and device checks, follow [Release testing](release-testing.md).

Update regression tests cover the entry dialog, weekly snooze/skip, F-Droid routing,
GitHub navigation without automatic download, persisted background results, transient-error
retry policy, cancellation and stale-source handling. HTTP 429/5xx are retryable; 404 and
invalid responses are not. These JVM/Compose checks do not establish real JobScheduler timing.
