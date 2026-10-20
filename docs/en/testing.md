# Tests and CI

[English](../en/testing.md) | [Русский](../ru/testing.md)

| Suite | Command | Coverage |
| --- | --- | --- |
| Android | `bundle exec fastlane android checks` | JVM/MockWebServer, Robolectric storage/providers, Compose, localization/security/contrast, debug/release lint, debug and unsigned release APKs |
| Emulator | `ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests` | Real Keystore, encrypted settings/outbox, HTTP/ACK/redirect/cancellation, image recovery and FileProvider on API 26/35 |
| Python | `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` | Local HTTP client, release automation with mocked APIs/local Git, branding, Black/isort |
| n8n | `PYTHON=.venv/bin/python bundle exec fastlane android server_tests` | Live text/image/test/SMS validation, routing/progress keys, HTTP failures, invalid ACK and timeout |

Use JDK 21, Ruby/Bundler and the Android SDK (`ANDROID_HOME`). For Python/server lanes:

```sh
bundle install
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
```

Before server tests, start [DevServer](../../DevServer/README.md):
`docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300`.
Use synthetic data: n8n retains request bodies in execution history.

## CI and reports

[ci.yml](../../.github/workflows/ci.yml) runs these suites on PRs, main pushes and manual dispatch;
setting `release_dry_run_version` runs only the release dry run. PR checks reject signing inputs.
[Signed builds](releases.md) run Android tests/lint separately with signing inputs.

CI uploads test/lint XML/HTML even after failure and retains APKs and emulator HTML/Logcat for
14 days. Python XML is in `test-results/python/`, n8n XML in `test-results/server/`, device XML in
`app/build/outputs/androidTest-results/connected/`; Android JVM XML is under `app/build/test-results/`.
Python uses `unittest-xml-reporting`, Node its native reporter, Android Gradle JUnit XML.

[test-reports.yml](../../.github/workflows/test-reports.yml) publishes each finished suite to
GitHub Checks and collapsible summaries. Internal jobs call it directly; fork PRs use trusted
`workflow_run`. It parses artifacts without running PR code; missing XML cannot turn a failed
job into success. Signed-release JVM results are separate.
[pr-artifacts-description.yml](../../.github/workflows/pr-artifacts-description.yml) runs from
the default branch and updates only the marked APK-link block after successful CI, preserving
other PR text and rejecting stale heads/runs. It neither executes PR code nor posts comments.
Local success does not establish a GitHub CI result.

## Disposable emulators

Use a Google APIs AVD named `message487-tests`, `message487-tests-26` or `message487-tests-35`.
**Tests clear its Message487 settings and queue.** Connect only that emulator; the lane rejects
physical devices, other AVD names and extra devices. CI creates fresh API 26/35 AVDs without
release secrets, requires KVM and never retries failed assertions automatically.

Use `arm64-v8a` on Apple Silicon or `x86_64` on Intel/Linux:

```sh
sdkmanager "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd --name message487-tests-35 --package "system-images;android-35;google_apis;arm64-v8a" --device pixel_5
emulator -avd message487-tests-35 -no-snapshot
# In another terminal, using the serial from adb devices:
ANDROID_SERIAL=emulator-5554 bundle exec fastlane android device_tests
```

Repeat with API 26. Debug-emulator success does not certify a signed release candidate.

## Coverage boundaries

Compose tests use real screens/ViewModels/preferences with isolated test Applications, no startup
workers/global crash handler, and drained background completions. Regression suites cover update
routing/snooze/retry/cancellation, corrupt settings, bounded queues, paged recovery, metadata limits,
APK cleanup and image-allocation failure with text fallback. Emulator tests exercise real HTTP
cancellation/deadlines and image recovery; injected OOM tests the handler, not heap exhaustion.
StrictMode scenarios and required runtime checks are listed in [release testing](release-testing.md#ci-evidence-for-a-candidate).
Current queue/deadline/memory ceilings live in [capture](../../app/src/main/java/life/andre/message487/BackgroundOperations.kt),
[image queue](../../app/src/main/java/life/andre/message487/NotificationImageQueue.kt),
[outbox](../../app/src/main/java/life/andre/message487/Outbox.kt) and
[HTTP](../../app/src/main/java/life/andre/message487/WebhookClient.kt).

Neither Robolectric nor these emulator suites establish real SMS/notification permissions/capture,
WorkManager/JobScheduler timing, process death, OEM behavior, email clients or signed upgrades.
Use [release acceptance](release-testing.md) and the [crash/report scenario](diagnostics.md).
There is no Go/JNI core, native fuzz/race suite, MegaProxy change classifier or ancestor-check reuse.
