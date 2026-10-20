# Message487

[![CI](https://github.com/andre487/AndroidMessage487/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/andre487/AndroidMessage487/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)
[![Release](https://img.shields.io/github/v/release/andre487/AndroidMessage487)](https://github.com/andre487/AndroidMessage487/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)

<p align="center">
  <img src="assets/branding/icon-round.svg" width="160" alt="Message487 app icon">
</p>

Message487 connects selected Android notifications and incoming SMS to your n8n workflows.
Forward notification text and images, with optional sender avatars and app icons, or enable SMS
forwarding separately. A custom HTTPS webhook is also supported. Telegram forwarding is one
possible workflow; the Android app does not depend on Telegram.

Notification and SMS forwarding are opt-in. Events are saved locally and delivered in the
background with retries and a delivery journal. Webhooks require a Bearer token.

## n8n workflow examples

Choose an [English guide](docs/en/n8n-examples.md) or [инструкцию на русском](docs/ru/n8n-examples.md).
Every workflow is self-contained, inactive on import and contains placeholders rather than personal data.

| Level | Importable workflow |
| --- | --- |
| Text webhook, no Telegram | [01 · Webhook text](examples/n8n/workflows/01-webhook-text.json) |
| Text + images webhook, no Telegram | [02 · Webhook images](examples/n8n/workflows/02-webhook-images.json) |
| Telegram text | [03 · Telegram text](examples/n8n/workflows/03-telegram-text.json) |
| Telegram images, built-in nodes | [04 · Telegram images standard](examples/n8n/workflows/04-telegram-images-standard.json) |
| Telegram Rich Messages, all media + retry progress | [05 · Telegram advanced](examples/n8n/workflows/05-telegram-images-advanced.json) |

Level 5 uses the MIT-licensed [N8nTelegramNode487](https://github.com/andre487/N8nTelegramNode487)
and a Data Table; it includes an optional [sent-key cleanup workflow](examples/n8n/workflows/05-telegram-cleanup.json).
Levels 1–4 require only built-in n8n nodes. Import instructions include synthetic payloads and setup steps.

## Getting started

**[Download the latest signed APK](https://github.com/andre487/AndroidMessage487/releases/latest/download/message487.apk)**

Also available from [F-Droid](https://f-droid.org/packages/life.andre.message487/).
Use **App updates** for updates; see the [installation guide](docs/en/apk-installation.md)
for permissions, installer restrictions and update sources.

Guides: [Install from APK](docs/en/apk-installation.md) · [n8n Cloud](docs/en/n8n-cloud.md) · [Self-hosted n8n](docs/en/n8n-self-hosted.md) · [Telegram forwarding](docs/en/n8n-telegram.md).
На русском: [Установка из APK](docs/ru/apk-installation.md) · [n8n Cloud](docs/ru/n8n-cloud.md) · [Свой сервер n8n](docs/ru/n8n-self-hosted.md) · [Пересылка в Telegram](docs/ru/n8n-telegram.md).

1. In **Connection**, save the published webhook URL, Bearer token and device code. Send a test.
2. Check **Journal** and find the same event ID in n8n **Executions**.
3. In **Sources**, enable notifications, grant notification access and select applications.
   Apps without a launcher icon can be added by package name.
4. Enable SMS separately and grant SMS permission. Only new incoming messages are captured.

Both sources start disabled. Avoid selecting apps that receive the forwarded messages: this
can create a feedback loop. Selecting your SMS app can forward the same message through both sources.
**Pause forwarding** stops capture and queued delivery; an in-flight request may finish.
Events received while paused are not saved. Android battery restrictions can delay background
delivery; after a force-stop, reopen the app. Sensitive notification content may be hidden by Android.

## Delivery and data

Duplicate SMS and notification filters are enabled independently by default. They compare source
package, exact text and images within the time window set in **Sources**; titles and senders are
excluded. Identical genuine messages within that window are also skipped. Set the window to zero
for exact timestamps, or disable the corresponding filter to forward repeated captures.

In n8n confirmation mode, return `{"status":"accepted","event_id":"<received ID>"}`.
For a custom webhook, disable confirmation to accept HTTP 2xx. Neither response proves downstream
delivery. Retries keep the same event ID and body; workflows should deduplicate by `event_id`.
Connection failures and transient HTTP errors retry automatically; other failures require a manual
retry from **Journal** after fixing the cause.

Queued events retain their original destination and credentials when settings change. Undelivered
events remain until confirmed or deleted. Confirmed payloads are removed; the journal keeps bounded
metadata without message contents. See [Privacy](PRIVACY.md).

Text and optional JPEG/PNG images are sent as JSON. **Sources → Graphics** controls image format,
limits, avatars and app icons. See the [payload format](docs/en/n8n-self-hosted.md#4-inspect-received-data)
and [image workflow guide](docs/en/n8n-examples.md#android-image-features-and-contract) for integration details.

## Troubleshooting, preview and settings transfer

- **Overview → Why was a message not forwarded?** checks connection settings, permissions,
  selected apps and delivery failures. Inspect **Journal** for individual attempts.
- **Connection → Preview event** and **Sources → Preview current notifications** show events
  without sending them. **Send this event** explicitly queues a preview; test events can be
  sent while paused. Automatic capture continues unless paused.
- **Connection → Import and export settings** transfers saved settings as JSON.
  **Exports include the Bearer token by default and are not encrypted.** Import requires
  confirmation, pauses forwarding, and clears the saved token if the file omits it.
  Permissions, queued events and journal history are not transferred.

For diagnostic logs and crash reports, use the top-bar bug icon and follow the
[diagnostics guide](docs/en/diagnostics.md). Sending a report requires action in your email app.

## Local development

Build with JDK 21, Ruby/Bundler and the Android SDK; the emulator launcher also uses Python 3.
Use `ANDROID_HOME` or an untracked
`local.properties` file to point Gradle to your SDK. SDK and library versions are in the Gradle
build files; Ruby dependencies are pinned by `Gemfile.lock`.

```sh
bundle install
bundle exec fastlane android checks
scripts/run-without-debugging.sh
```

The run script starts the development emulator, installs the debug APK and launches the app,
preserving its data. In VS Code, use **Run Message487 on Emulator → Run Without Debugging**.
For the local n8n endpoint and its test dependencies, follow [DevServer setup](DevServer/README.md).
Release builds require HTTPS. Test with synthetic data.

See [Testing](docs/en/testing.md) for CI and device checks, [Releases](docs/en/releases.md)
for signed builds, and [release automation](docs/en/release-automation.md) and
[release testing](docs/en/release-testing.md) for publication.
[Branding](assets/branding/README.md) covers artwork and resource generation;
[design](docs/en/design.md) and [project context](docs/en/project-context.md) cover conventions.

This project succeeds [sms487](https://github.com/andre487/sms487) and follows
[AndroidMegaProxy](https://github.com/andre487/AndroidMegaProxy) conventions.
Licensed under the [MIT License](LICENSE).
