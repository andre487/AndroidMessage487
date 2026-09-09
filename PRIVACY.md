# Privacy Policy

Last updated: September 9, 2026.

Message487 forwards selected notifications and new incoming SMS to a webhook you configure.
Both capture sources are off by default. Notifications require Android notification access and
an explicit selection of source apps. SMS forwarding requires the receive-SMS permission.
The app does not read existing SMS history, send SMS, or reply to notifications.

## On your device

The app stores your webhook URL, editable device code, confirmation preference, enabled sources,
selected package names, pause state and a random installation ID in private preferences. These
preferences do not have additional application-level encryption. A webhook URL may itself contain
a secret, so treat it as sensitive. Android cloud backup and device transfer are disabled for app data.

The webhook Bearer token is encrypted with AES-GCM and an Android Keystore key before
being saved in preferences. It is sent in the Authorization header to your configured endpoint.

Captured event bodies, including message text, notification titles and SMS senders, are stored in
a private SQLite outbox encrypted with AES-GCM and a key held in Android Keystore. Each queued
request includes its original destination, encrypted authentication token and confirmation mode. Delivery metadata (event ID,
source display name, type, timestamps, state, attempt count and HTTP result) is stored without
additional application-level encryption. Notification duplicate detection stores hashes of keys
and contents; these hashes are not a substitute for encryption against guesses of known content.

The journal shows delivery metadata and does not display message contents. Confirmed events have
their encrypted payload removed from the active database record; only recent delivery metadata is
retained. Undelivered payloads are not automatically deleted by age. Database deletion is logical
and does not guarantee forensic erasure of previously allocated storage.
Message bodies and server response bodies are not written to diagnostic logs by the app.

## Diagnostics and voluntary email reports

The app records local diagnostic events: startup, listener connectivity, capture and delivery
outcomes, HTTP status codes, queue counts and failures. Logs exclude message and response bodies,
notification titles, SMS senders, source packages, webhook URLs, authentication tokens, device codes and installation IDs.
Exception messages are omitted; limited stack traces contain exception classes and code locations.
Unhandled Java/Kotlin exceptions are saved synchronously and a report prompt appears on next launch.

Diagnostic files reside in private app storage without additional encryption. Three rotating files
are limited to 256 KiB each, with a separate last-crash file up to 256 KiB. In Diagnostics you can
view recent logs, clear them, or prepare an email to **der-morgenstern@yandex.ru**. The ZIP attachment
includes these logs and an environment summary: app version, Android version/security patch,
manufacturer/model, CPU architecture, enabled-source flags and selected-app count. Up to three
archives are retained in private cache. Clearing logs also removes the last crash and cached archives.

Nothing is sent automatically. Your selected email/sharing app receives temporary read access to
the attachment; review it before sending. Sent copies and your email address are processed by your
email provider and the recipient and are not erased by clearing local logs. Reports are used to
investigate the problem you report. You may request deletion of a received report at the address above.

## Network requests

Your selected endpoint receives event text, an event ID, installation ID, device code, timestamp,
schema version, event type, source package identifier and source display name. Notifications add
a title; SMS add the sender address. Manual connection tests send synthetic content through the
same queue. Android may redact notification content before giving it to the app.

The endpoint operator can also see connection metadata such as your IP address. The endpoint and
any downstream services process data under their own policies. An n8n instance may retain complete
event bodies in its execution history; the bundled development server does so. Retries can produce
more than one server-side copy of an event. Changing the webhook affects new events; existing
queued events keep their previous destination and token. Server execution history may also
include request headers; restrict access and retention.

The app lists visible launcher apps locally to let you select sources and resolve display names.
It does not upload an installed-app inventory or request visibility of all installed packages.
If a source name is unavailable, its package identifier is used instead.

Release builds require HTTPS. Debug builds also permit HTTP to the Android emulator host and
localhost for development. The app contains no advertising, analytics SDKs or automatic crash
reporting, and does not automatically send data to the developer.

## Controls and deletion

Disable a source to stop capturing its new events. Pause forwarding to also pause queued delivery;
a request already running may finish. Neither action erases previously queued events. Delete
individual records from the journal to remove them from the active database and stop future
attempts. Clearing app data or uninstalling removes local app data and resets the installation
identity. These actions do not remove copies already received by the webhook or downstream services.
Delete n8n execution history and downstream copies separately using those services' controls.

Project information and contact: [Message487 on GitHub](https://github.com/andre487/AndroidMessage487).
Do not post personal messages or credentials in public issues.
