# Message487 context

[English](../en/project-context.md) | [Русский](../ru/project-context.md)

## Scope

Message487 replaces [sms487](https://github.com/andre487/sms487) in a separate repository.
It forwards selected Android notifications, images and new SMS to n8n or another HTTPS webhook.
The package ID and supported SDKs are defined in [Gradle](../../app/build.gradle.kts).
Presentation, Kotlin/Compose style, EN/RU localization and Fastlane conventions follow
[AndroidMegaProxy](https://github.com/andre487/AndroidMegaProxy); VPN/Go/JNI features are outside scope.
The historical reference was commit `c8190e97b705a2c4578d278c40a690e97c5d5f27`; current CI is
specified in [Testing](testing.md).

## Current behavior and constraints

[README](../../README.md) covers setup, capture, duplicate filters and delivery;
[the receiver guide](n8n-self-hosted.md#4-inspect-received-data) defines event fields,
and [image examples](n8n-examples.md#android-image-features-and-contract) cover media handling.
[Privacy](../../PRIVACY.md) describes encryption, retention, backup exclusion and diagnostics;
[release testing](release-testing.md) defines device and distribution acceptance.

Capture sources are opt-in. Duplicate matching uses package, exact text, image checksum and
original timestamps within the configured inclusive window; titles and SMS senders are excluded.
Hashes survive delivery and journal deletion until app data is cleared. This does not replace
server-side deduplication by `event_id`.

Events are persisted before sending; retries retain their ID, body, destination and credentials.
WorkManager and Android restrictions can delay delivery, and process death before persistence
can lose a capture. Unconfirmed events are not silently deleted by age. Webhook acceptance does
not prove durable server storage or downstream delivery; exactly-once delivery is not promised.

The initial connection-test client grew into notification/SMS capture with a persistent queue.
Overview shows readiness, permissions, queue and pause; last successful delivery and viewing
journal message contents remain unimplemented product directions.

## Open decisions

- Physical-device acceptance for power saving, reboot and permission restrictions; CI is insufficient.
- ACK after processing versus after durable server-side queue persistence. The current example ACK
  is our contract, not n8n's default; use a published production URL rather than a test URL.
- Future authentication/contract changes and limits for the unconfirmed queue.
- Migration of the existing Telegram scenario and whether to retain SQS; PostgreSQL was discussed
  as an alternative, not selected as a requirement.

## Previous system

sms487 uses a Go API, SQS and a separate Telegram bot. Its running system has not been migrated;
the bot code was not reviewed. Its `/add-sms` batch protocol is not a compatibility requirement.
Static Android review found delivery outliving a Worker, an SMS receiver without `goAsync()`,
a pre-persistence loss window, no stable event ID, acceptance before response validation and
message prefixes in logs. These findings were not reproduced on a device.
