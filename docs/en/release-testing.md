# Message487 release testing

[English](release-testing.md) | [Русский](../ru/release-testing.md)

Test an identified signed release candidate, followed by the published artifact. Green CI,
a successful dry run and a connection test do not replace Android capture testing.
This is a procedure, not a record of completed tests.

## Results and evidence

Each scenario on each device receives **PASS**, **FAIL**, **BLOCKED**, **NOT TESTED** or **N/A**.
N/A needs a product/platform reason; missing time or equipment is NOT TESTED. Source review
and JVM tests do not fill device-test cells. Describe exactly what passed in a partial result.

Record scenario ID, UTC time, commit/APK SHA-256, device/OS, preconditions, action,
expected/actual results, evidence paths, verdict and restoration. Correlate a synthetic marker
and `event_id` across **Journal**, the HTTP request/response and **n8n Executions**.
Negative tests require working controls before and after. Reproduce significant authentication
failures, leaks and data loss twice with fresh markers.

An accepted ACK confirms the webhook; generic HTTP 2xx confirms only HTTP success.
Neither proves Telegram/downstream delivery. Test downstream separately. Losing the response
may cause multiple executions with the same event ID; exactly-once delivery is not promised.
The fixture/downstream needs ID deduplication where the scenario requires it.

Keep raw database/UI dumps, logs, captures and n8n executions local in a 0700 directory,
sensitive files 0600. Webhook URLs may contain secrets. Commit only reviewed sanitized
excerpts and synthetic fixtures. Ordinary testing does not authorize sending diagnostic email,
uploading to VirusTotal, publishing issues/releases or modifying an external F-Droid MR.

## Preparation

| ID | Procedure and evidence |
| --- | --- |
| A01 | Record candidate commit, changes since the previous release, versionName/code, SDKs, dependency/toolchain versions and APK origin. Read versions from `app/build.gradle.kts`, expected signing identity from `scripts/build-release-apk.sh`. Preserve existing changes; do not change production versionCode just for a test. |
| A02 | Run `bundle exec fastlane android checks`, `PYTHON=.venv/bin/python bundle exec fastlane android python_checks` and, with local n8n running, `bundle exec fastlane android server_tests`. Retain results and CI links for the candidate. PR checks receive no signing configuration. See [Tests and CI](testing.md). |
| A03 | Run `bundle exec fastlane android release_artifacts` or obtain signed artifacts from a build-only mode. Retain SHA256SUMS, `apksigner verify --verbose --print-certs`, `aapt dump badging`, permissions and merged manifest. Verify release/non-debuggable, package/version and existing signing key. Debug builds do not replace this step. |
| A04 | Reconcile source/merged manifest, exported components, FileProvider, backup/data extraction, HTTP restrictions, dependency permissions/licenses. Explain RECEIVE_SMS, notification listener, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES and scheduler components. SMS history/default SMS role, Accessibility and QUERY_ALL_PACKAGES are unnecessary. Match store description/PRIVACY to behavior. |
| A05 | Describe two phones and two dedicated emulators: model, OS/API/OEM build/patch, ABI/page size, display/font/language, package/version/signer/installer, permissions, battery, network, VPN/Private DNS. Emulators cover minimum supported API and a modern API. Without Samsung hardware, its OEM check remains NOT TESTED. |
| A06 | Preserve phone settings and data; use only synthetic SMS and selected probe apps, never personal apps/OTP. Do not uninstall or clear existing data. Reboots, network/PIN changes and destructive checks need applicable authorization; start on dedicated emulators. |
| A07 | Prepare a private HTTPS fixture with a trusted certificate, separate tokens and controlled responses; prove it works directly. [DevServer](../../DevServer/README.md) is an HTTP/debug fixture, so release testing needs HTTPS ingress. Do not expose its public development credentials. Inventory created resources for cleanup. |

## Execution order

1. Select the candidate commit and previous signed release for migration. Record
   `git status`, `git rev-parse HEAD` and the diff; use a separate clean checkout when
   existing work is present. Before publication, test the signed candidate rather than
   the latest published APK at the stable URL.
2. Prepare JDK 21, Ruby/Bundler, the Android SDK declared in Gradle, Platform Tools,
   Python and Docker. Configure the SDK through `ANDROID_HOME` or `local.properties`.
   Run `python3 -m venv .venv`,
   `.venv/bin/python -m pip install -r requirements-dev.txt` and `bundle install`.
3. Start fixtures with
   `docker compose -f DevServer/compose.yaml up -d --wait --wait-timeout 300` and run A02.
   For signed release testing, add private HTTPS ingress with a certificate trusted by
   the device and separate authentication; the HTTP fixture alone is insufficient.
4. Build via A03 with the existing key and signing configuration:
   `MESSAGE487_KEYSTORE_PATH`, `MESSAGE487_KEY_PASSWORD_FILE`, `MESSAGE487_KEY_ALIAS`.
   Never print passwords. Verify `dist/release/SHA256SUMS` and associate every result
   with the installed artifact hash. For the F-Droid channel, run the separate official
   build below and identify its artifact.
5. Record each device serial/settings. Use `adb -s SERIAL install -r /path/to/candidate.apk`;
   fresh installs belong only on empty test devices/profiles. Record
   `adb -s SERIAL shell pm path life.andre.message487`, pull the returned path with
   `adb -s SERIAL pull` and verify SHA-256. For migration, first install the old signed
   APK and create its synthetic pending queue.
6. In **Connection**, enter the full published webhook URL, token without the `Bearer `
   prefix and synthetic device code. Enable n8n confirmation only for the ACK contract
   below. Save/send test and correlate Journal/server execution IDs. Then separately
   grant SMS/notification access and select only probe apps; run I/C/Q/L/D/U and record
   the per-device matrix.
7. After each negative test, restore the working fixture and prove delivery with a fresh
   marker. Finally restore devices and complete the report/decision. R01/R02 do not
   publish; R03 requires separately authorized publication.

### Test-server contract

Accept POST with `Content-Type: application/json` and
`Authorization: Bearer <test token>`. JSON includes `schema_version`, `event_id`,
`device_id`, `device_code`, `message_type`, `occurred_at`, `source`, `source_name`, `text`;
notifications add `title`, SMS adds `sender`. Retain these fields and receipt time in
private fixture logs. Use a fresh synthetic marker for each probe.

With n8n confirmation enabled, return HTTP 2xx and
`{"status":"accepted","event_id":"ID_FROM_REQUEST"}`. The workflow must implement this ACK;
n8n does not supply it automatically. Without confirmation, 2xx is sufficient. Prepare
controlled modes for correct ACK, 500, 401/403, wrong event ID, malformed JSON, a delay
exceeding client timeout and request acceptance with a lost response. A successful
execution without checking the HTTP response does not prove client acceptance.

Use endpoints A/B with separate tokens to test destination preservation. Positive
controls must use the app, not only curl. Emulator SMS broadcast can be probed with
`adb -s SERIAL emu sms send +15551234567 'Message487 synthetic RUN-ID'`.
The primary notification probe is a normal app with known fields and its own UID;
shell/root is supplemental. Both paths need actual server-request evidence.

## F-Droid and artifact provenance

Select the candidate build block for `life.andre.message487` in official
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), recording recipe hash, upstream SHA,
fdroiddata/fdroidserver revisions and any `Binaries`/`AllowedAPKSigningKeys` configuration.
Inspect an external MR's current state and review invitation when conducting an independent
review. A published recipe can also be the target; do not substitute an imagined new build block.

Use `registry.gitlab.com/fdroid/fdroidserver:buildserver`, recording its actual digest/platform,
supported build user and fdroiddata configuration. In a separate checkout, run applicable
commands supported by the selected official fdroidserver version:

```sh
fdroid readmeta
fdroid lint life.andre.message487
fdroid fetchsrclibs life.andre.message487:VERSION_CODE --verbose
fdroid build --verbose --test --scan-binary --on-server --no-tarball life.andre.message487:VERSION_CODE
```

Replace VERSION_CODE with the selected recipe's code. Explain necessary supported command
variations. Retain exit codes, source/binary scanner results and APK paths. Never edit the
recipe to obtain PASS. Investigate warnings, including updater/APK downloads, dependencies,
binary assets and applicable [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/).

For reproducible builds, use official binary comparison/signature-copy verification with the
exact upstream APK and allowed certificate. Gradle alone or SHA-256 comparison of differently
signed APKs does not establish reproducibility. See [F-Droid guidance](https://f-droid.org/docs/Reproducible_Builds/).
If the matching upstream APK is not published, record BLOCKED/NOT TESTED; do not publish just
to satisfy a test or claim reproducibility prematurely.

Install the verified signed artifact for runtime. A local unsigned APK cannot update a signed
installation. Identify F-Droid signing separately when applicable. Read back the installed APK
using `pm path`/`adb pull` and compare its hash on each device. Real F-Droid-client installation
is required to test its update-source path; ADB installation does not prove that path.

## Device matrix

Run applicable scenarios on all four devices. Never substitute emulator PASS for a missing phone
result. Destructive database/Keystore failures, APK/time manipulation, process kills, Doze and
artificial responses start on dedicated fixtures. A phone without SMS capability can be N/A with
a reason; emulator SMS does not establish carrier delivery.

### Installation, permissions and UI

| ID | Action → expected result |
| --- | --- |
| I01 | Fresh installation on an empty emulator/test profile; two cold starts. Capture off, no apps selected, no personal-data transmission without configuration. Account separately for default automatic update checks. |
| I02 | Upgrade the previous signed release containing synthetic credentials/device code, selections and pending events. Preserve installation ID, settings, pause, queue and original destinations; deliver old and new events. Reinstalling the same version is not migration. |
| I03 | Deny/grant/revoke SMS permission and notification access. Disabled sources cannot capture; no crash/false success; regrant captures only new events. Ordinary notification permission controls update reminders, not listener access. |
| I04 | Exercise restricted settings and notification access after APK install. Samsung: Auto Blocker during installation/upgrade and restore protection. Follow [APK installation](apk-installation.md); unknown-source permission does not resolve every block. |
| I05 | All main screens, Back/Home/rotation, narrow display, large font, EN/RU, light/dark, keyboard and foldable inner/outer screens. Actions/dialogs remain usable. Test colored/themed icons through the actual launcher switch where supported. |

### Capture and source controls

| ID | Action → expected result |
| --- | --- |
| C01 | A normal probe app with its own UID posts selected notifications; correlate title/text/time/package/source_name at the server. Unselected app: no event. Shell notification is supplemental, not a normal-UID substitute. |
| C02 | Plain/big text, InboxStyle, empty content, ongoing/group summary, text update. Excluded ongoing/summary/self notifications stay excluded. Existing notifications are not replayed when access is granted. Do not promise content Android redacts. |
| C03 | Select/unselect, manually add launcherless packages, search and Select all. Bulk selection covers the whole list and preserves manual entries; newly installed apps are not automatically selected. Persist across restart; avoid downstream apps that create feedback loops. |
| C04 | Receive synthetic SMS from an authorized test number on a phone. Verify sender/text/time and source `android`; no historical import, default-SMS role or reply. Test multipart, Cyrillic/Unicode and distinct SMS; record actual segmentation/timestamps. |
| C05 | Deduplication on/off/on, restart and upgrade. Identity is package + original timestamp + exact text; a changed timestamp is a new event. Title/sender changes alone do not change identity. Use controlled fixtures/JVM evidence and separate available runtime controls; two commands with different timestamps do not prove suppression. |
| C06 | Disable source/pause during capture/delivery. No new captures; pause also delays queue. An in-flight request may finish. Resume delivers queued events without importing events missed while paused. Repeated manual connection tests are not message-deduplicated. |

### Webhook, authentication and outbox

| ID | Action → expected result |
| --- | --- |
| Q01 | Save/test and actual captured events over HTTPS: Bearer header, JSON contract, event ID and matching ACK. Device code persists, installation ID stable. Journal hides bodies. Manual test success does not prove Android capture. |
| Q02 | Correct/wrong/missing/invalid/restored token. Invalid input is not saved; server rejects wrong credentials without client acceptance. Wrong CA/hostname, untrusted self-signed and release HTTP fail. Redirect must not forward body/Authorization. Working before/after controls required. |
| Q03 | Wrong/missing ACK ID/status, malformed/oversized JSON and generic mode without ACK. Invalid ACK with 2xx blocks for manual retry; generic 2xx yields HTTP_SUCCESS. A successful n8n error execution is not accepted delivery. |
| Q04 | Network/timeout and HTTP 408/425/429/5xx retry; other HTTP failures need correction/manual retry. Preserve event ID/body/original URL/token/ACK after recovery. Lost response may duplicate server receipt with the same ID. No exact WorkManager retry-time promise. |
| Q05 | Queue offline events for endpoint A, then switch settings to B. Old events retain A and its credentials; new events use B. UI token rotation does not repair old encrypted envelopes. Use synthetic endpoints; explicitly delete unwanted old events instead of silently rerouting. |
| Q06 | Retry now, delete test records, repeated actions and details during state change. No duplicate local event/lost queue; in-flight entries are not freely deletable. Acceptance removes payload from active records while bounded metadata remains. Logical deletion is not forensic erasure. |
| Q07 | Offline enqueue, process death/rotation/relaunch, network restoration; preserve encrypted payload and delivery, including previous-version queue. Keystore/storage failures on isolated fixtures: no garbage transmission or silent deletion, visible error. Robolectric success does not prove real Keystore. |

### Lifecycle and background

| ID | Action → expected result |
| --- | --- |
| L01 | Home/lock/task removal/return with actual capture and server evidence. Two unavailable/available server and offline/online cycles; real phone Wi-Fi/mobile/Wi-Fi separately. ADB reverse and virtual cellular do not prove carrier handover. |
| L02 | Separately exercise process death and `am force-stop` on a dedicated emulator. Force-stop is not a crash. Reopen to recover persisted queue; do not promise events while Android keeps the package stopped. Real LMKD/OOM is separate. |
| L03 | Reboot online/offline, unlock, new SMS/notification; delivery recovery and update preferences/jobs persist. Pre-unlock work is not promised: components are not directBootAware. RUNNING_LOCKED probes are separate from post-unlock results. |
| L04 | Forced Doze/OEM battery restrictions and restoration; pending survives and delivery resumes. Record idle/exemption/network/duration. Forced jobs do not prove natural daily scheduling. Foreground/background soak: at least 15 minutes and several events; longer/day-long tests separate. |
| L05 | Upgrade while queue/capture enabled, then while paused. Preserve decryptability/destination/state; pause/source choices do not change automatically. Test new captures and listener reconnect after installation. |

### Privacy and diagnostics

| ID | Action → expected result |
| --- | --- |
| D01 | Synthetic markers in bodies/title/sender/URL/token/device code/IDs/responses; search app/crash/ZIP and available system logs for excluded fields. Some private preferences/metadata lack additional encryption; match PRIVACY rather than promise universal encryption. Database/Keystore inspection only on authorized fixtures. |
| D02 | Refresh/clear/ZIP/mail chooser, cancellation/no mail app. Clearing diagnostics preserves queue. Temporary read-only attachment grants without arbitrary private-data/write access. Stop at the email editor without sending. |
| D03 | Isolated app crash/relaunch: prompt, crash file and ZIP. Debug injection does not establish release-candidate handling; use a reproducible release mechanism if available. Java handler does not cover ANR/native crash/kill. Correlate logcat, exit reason and action. |
| D04 | Normal probe APK tests external intents/broadcasts/provider access: no secrets, private update control or forged SMS receiver access. Verify BIND_NOTIFICATION_LISTENER_SERVICE/BROADCAST_SMS/BIND_JOB_SERVICE and merged components. Shell/root does not prove normal-app isolation. |

### Updates and release automation

| ID | Action → expected result |
| --- | --- |
| U01 | Real official/alternative F-Droid client, browser/file-manager and unknown-installer installations; manual choice. Source follows installer/repository capability; choice persists. Merely installed F-Droid does not change browser origin. 404/offline does not switch source. Installer spoofing is only simulation. |
| U02 | New/same/old/malformed metadata, consent/cancel, failed/interrupted APK and size/digest/package/version/signer checks. No download without consent; metadata checks do not depend on download consent. Invalid APKs first in JVM/isolated emulator. Never publish fixture versions or bump production code for tests. |
| U03 | GitHub download then separate Install; deny/grant unknown-source permission, return from Settings, cancel/succeed, rotate/background. Read back APK/version/signer and confirm delivery after upgrade. F-Droid opens its page; client installs. Different signers cannot upgrade one another; uninstall is not the first remedy. |
| U04 | Reminder permission/channel, Open/Skip/Disable, manual after Skip, disable in-flight job, reboot. Simulate time on an emulator for weekly repeats/no early repeats. Forced jobs/time simulation are not natural daily/weekly results. |
| R01 | Dry-run with an explicit proposed version: reads/generates EN/RU without release files/branch/PR/merge/tag/Release. Empty CI input runs normal CI. Compare refs/PRs/releases before/after; label mock and live checks separately. Dry run does not prove writing/signing/ruleset acceptance. |
| R02 | Read EN/RU notes, version/code, history and changed files before release. Gate requires full exact head/base CI; old/skipped CI cannot satisfy it, changed main/head stops merge. Test rejection locally with mocks, not by breaking production rules. |
| R03 | Only after separately authorized publication, verify actual merged commit/tag/tree, published APK provenance/checksum/signer, stable/versioned URLs and both notes. Compare with tested candidate; different artifacts require investigation/retest. F-Droid build/publication remains separate. |

## Completion and release decision

Prioritize artifact identity, test/SMS/notification delivery on each device, negative auth/ACK,
offline recovery and migration, followed by lifecycle, updater, privacy and OEM/UI. Reduced
coverage keeps NOT TESTED. A focused follow-up must explain the diff, adjacent regressions and
scope; old phone results never become new-version results simply because UI is unchanged.

Stop only created fixtures/captures; remove only owned ADB forwards/reverses, probes and synthetic
events. Restore networks, permissions, update source/automatic checks, battery, display/font/language,
Auto Blocker and original app settings. Preserve others' workflows/volumes and personal messages.
Local deletion does not remove n8n/downstream copies; clean synthetic executions separately.
Record leftovers and verify devices after restoration.

Do not recommend release with queue/secret loss, wrong signing, auth bypass, excluded-data leaks,
unexplained crashes or broken capture/delivery on supported devices. A critical untested migration
or blocked gate means **RELEASE NOT VERIFIED**. State GitHub/F-Droid readiness separately.
Fixes use a minimal separate branch, reproduction/regression test, new APK and affected/adjacent
retests; the old FAIL remains until retest.

Save a sanitized `docs/reviews/release-YYYY-MM-DD.md` with candidate commit/version/code/hash/signer,
artifact origin, devices, command/CI/F-Droid evidence, scenario/device/verdict matrix, findings and
controls, retest identity, restoration/leftovers and channel-specific decision/blockers/gaps.
Raw material stays local. Test the signed release-PR candidate before publication; configure
human review in [release automation](release-automation.md) to inspect notes before merge.
R03 is additional post-publication verification, not permission to release untested APKs.
No device tests were performed while writing this procedure.
