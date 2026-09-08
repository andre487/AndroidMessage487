# Message487 context

[English](../en/project-context.md) | [Русский](../ru/project-context.md)

Updated September 8, 2026.

## Confirmed by the user

- A new Android application replaces sms487 in a separate repository.
- Package ID: `life.andre.message487`.
- Primary positioning is n8n integration; arbitrary webhooks must also be supported.
- Project presentation, code style, privacy policy, documentation and CI follow
  [AndroidMegaProxy](https://github.com/andre487/AndroidMegaProxy).

## Original system

[sms487](https://github.com/andre487/sms487) forwards SMS and notifications through a Go API
and SQS to a separate Telegram bot. The bot's code was not reviewed. The running system
has not been migrated.

## Initial implementation

The user requested a Docker Compose DevServer with n8n test workflows and an Android
connection-testing client. The client saves the endpoint, sends a synthetic event,
checks acknowledgement with a matching `event_id` and displays the result. A generic
webhook mode accepts HTTP 2xx. The ACK format is our example contract, not the default
response of every n8n workflow.

Fastlane handles builds/checks. The UI uses Kotlin/Compose with English and Russian
resources. Notification/SMS capture, a persistent queue and retries were added later.
Webhook authentication remains unimplemented. `DevServer/README.md` describes the local
server and its limitations.

## Risks found in the old sms487 client

The prior discussion and static Android-code review identified asynchronous delivery
outliving a Worker, an SMS receiver without `goAsync()`, a loss window before persistence,
and no stable event ID. An HTTP success callback marks a batch sent before validating
the response. Logs contain a message prefix. These were static findings, not reproduced
on a device.

The old client appends `/add-sms` to its server address and uses a proprietary batch format.
Compatibility with that protocol was not agreed as a requirement for the new app.

## Capture and delivery

NotificationListenerService uses a package selection; SMS_RECEIVED uses RECEIVE_SMS and
goAsync. Both sources default to off. Existing SMS history is not read. Unchanged notification
updates are suppressed; changed content creates an event. Group summaries, ongoing notifications
and Message487's own notifications are excluded.

Events are persisted in SQLite before delivery; request bodies use AES-GCM with Android
Keystore. WorkManager retries transient failures; a periodic recovery task restores scheduling.
Event ID, contents, URL and acknowledgement mode are captured together. Changing the connection
does not redirect existing queued events. Invalid ACKs and permanent HTTP errors require manual
retry. Unconfirmed events are not deleted by age; confirmed payloads are removed while a bounded
metadata history remains. The journal hides message contents. Pause stops capture and new
attempts; a running request may complete. Backup and device transfer exclude application data.

Limitations: capture depends on Android, and the process may die before local persistence.
WorkManager does not promise immediate delivery. Sensitive-notification restrictions are not
bypassed. Physical erasure of SQLite pages is not guaranteed. SMS deduplication uses sender,
time, text and installation; it does not replace server-side deduplication.

## Product direction

These items describe the direction; showing the last successful delivery on the overview and
viewing journal message contents are not implemented yet.

- n8n connection with a sample workflow and synthetic test; alternatively a full custom webhook
  URL. Shared transport sends JSON over HTTPS.
- App selection and separate SMS enablement, with contextual permission requests. Filtering
  happens on the phone before persistence and delivery.
- A local queue persisted before network calls, stable retry IDs and installation identity
  rather than the phone model.
- Overview with connection/permission/queue status, global pause and last successful delivery;
  journal with contents hidden by default and manual retry.
- Webhook acceptance is distinct from delivery to Telegram or another downstream service.
  Unconfirmed events are not silently removed by age.
- Diagnostics exclude secrets and message contents. Encryption, backup and retention requirements
  must be decided before implementing storage.

n8n has test and production URLs; persistent integrations use a published workflow's URL.
`Immediately` acknowledges workflow startup, not completion of its actions. See the
[Webhook documentation](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/).

## Open decisions

1. Physical-device checks for power saving, reboot and permission restrictions.
2. ACK after workflow execution versus durable server-side queue persistence. HTTP success
   alone does not establish durable storage or downstream delivery.
3. Webhook authentication and future contract changes. README describes the current contract
   and queue; limits for the unconfirmed queue require a separate decision.
4. Distribution and required device checks. The initial minimum SDK is configured in Gradle;
   its suitability for future capture behavior still needs verification.
5. Whether to migrate the existing Telegram scenario and retain SQS. n8n does not require
   removing the existing queue; PostgreSQL is one replacement discussed.

Lost responses can produce duplicates. Deduplication must use a stable event ID; exactly-once
was not agreed and must not be promised.

## AndroidMegaProxy references

Its main branch was reviewed at `c8190e97b705a2c4578d278c40a690e97c5d5f27`.

- Kotlin, Compose Material 3, system light/dark themes, English and Russian resources.
- JDK 21, Gradle Kotlin DSL and Fastlane through Bundler for builds/checks.
- PR/main CI: JVM tests, Android lint and builds, without a mandatory hosted emulator.
- PR APK artifacts without release keys; signing and releases are separate.
- Public privacy documentation describes actual application behavior.

VPN, Go/JNI, DNS diagnostics and MegaProxy's publication specifics are not Message487
requirements. Its privacy policy cannot simply be copied: Message487 sends event contents
to the chosen recipient, and n8n/downstream services have their own retention policies.

Local rotating diagnostics, next-launch crash prompts and manual ZIP reports are implemented;
see [diagnostics](diagnostics.md). Recipient: der-morgenstern@yandex.ru.

[Test categories and CI commands](testing.md) include Compose tests on Robolectric without
an emulator. [Signed APK releases](releases.md) use environment-based signing as in MegaProxy.
