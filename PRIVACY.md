# Privacy Policy

Last updated: September 8, 2026.

This policy describes the current Message487 development preview. It sends synthetic connection
test events to a webhook you configure. It does not yet read SMS or other applications' notifications.

## On your device

The app stores your webhook URL, editable device code, confirmation preference, and a randomly generated installation
ID in its private preferences. These preferences do not have additional application-level encryption.
Android cloud backup and device transfer are disabled for app data.

Recent test results contain event IDs, HTTP status codes, request durations and outcome labels.
They are held in memory and disappear when the app process ends. Message bodies and server
response bodies are not written to diagnostic logs by the app.

## Network requests

When you press **Save and send test event**, your selected endpoint receives a synthetic message,
event ID, installation ID, your device code, timestamp, schema version, event type, the source app's
package identifier and its display name.
Its operator can also see connection metadata such as your IP address. The endpoint and any
downstream services process requests under their own policies. An n8n instance may retain event
bodies in its execution history; the bundled development server does so.

The app declares visibility of apps with launcher activities so it can look up a source
application's display name by package identifier. It does not request access to all installed
packages or collect or upload an inventory of apps. If a source name is unavailable, its package
identifier is used instead. The current test sender looks up Message487's own name.

Release builds require HTTPS. Debug builds also permit HTTP to the Android emulator host and
localhost for development. The app contains no advertising, analytics SDKs or automatic crash
reporting, and does not automatically send data to the developer.

## Deletion

Clearing app data or uninstalling removes local preferences and resets the installation identity.
It does not delete records on your webhook server. Delete n8n execution history and downstream
copies separately using the controls of those services.

Project information and contact: [Message487 on GitHub](https://github.com/andre487/AndroidMessage487).
Do not post personal messages or credentials in public issues.
