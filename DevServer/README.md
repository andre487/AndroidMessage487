# Message487 development server

Run from the repository root with Docker Compose installed and a Docker engine running:

```sh
docker compose -f DevServer/compose.yaml up -d --wait
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
PYTHON=.venv/bin/python bundle exec fastlane android server_tests
```

Open the editor at <http://localhost:5678>. Local development login:

- Email: `developer@message487.test`
- Password: `Message487-Local-Only`

These are public test credentials. This configuration is for synthetic local development data.
All webhook endpoints require `Authorization: Bearer <token>`. The public local token is
the `data.value` in [header-auth.json](credentials/header-auth.json), without the `Bearer ` prefix. The published port is bound to host loopback.
The editor account and its bcrypt password hash are provisioned through n8n environment variables.

The image version, execution retention, and runtime settings live in [compose.yaml](compose.yaml).
The named volume stores the database and n8n-generated encryption key. Successes and failures are
visible in **Executions**, including submitted event bodies. The `error` workflow deliberately
returns an HTTP error; its n8n execution itself may still be marked successful.

## Portable workflow examples

The [five example levels](../docs/en/n8n-examples.md) ([на русском](../docs/ru/n8n-examples.md))
cover text/image webhooks without Telegram, basic Telegram, built-in image forwarding and the
advanced Rich Message workflow. Import the JSON from [examples/n8n/workflows](../examples/n8n/workflows),
not a configured export from a personal server. Credentials/chat/table IDs are intentionally absent.
The four workflows in this DevServer folder remain local fault-testing fixtures, not production templates.

DevServer also publishes the first two portable examples at `/webhook/message487/01-webhook-text`
and `/webhook/message487/02-webhook-images`, using the same local test token as `receive`.
The image receiver exposes decoded binary files in its execution output without any Telegram dependency.
Telegram templates are not automatically configured or published by DevServer.

## Android connection

The standard Android Emulator reaches the host through `10.0.2.2`. The debug app starts with the
receive endpoint configured. Enter the local token in **Webhook token** before saving. Its **Save and send test event** button submits synthetic data and
shows the event ID and confirmation result in **Journal**. Find the same ID in the workflow's execution input.
In the Webhook output, `body.device_id` is the installation UUID and `body.device_code` is the
editable device label. Both are preserved in execution history; older events may lack the label.
`body.source` contains the package identifier and `body.source_name` the application's display name.
Use **n8n confirmation** for this server; generic webhook mode only checks the HTTP status.

| Endpoint suffix under `/webhook/message487/` | Expected result |
| --- | --- |
| `receive` | Accepted event ID, or HTTP 400 for invalid input |
| `error` | HTTP 500 |
| `slow` | Response delayed beyond the client's timeout |
| `invalid-ack` | HTTP 200 with a mismatched event ID |

These are published webhooks, so **Listen for test event** is unnecessary. They confirm a test
execution only; there is no durable delivery queue, deduplication, or Telegram integration here.
The JSON fixtures define the accepted event contract. The Android client keeps a persistent
retry queue; the server does not deduplicate repeated requests.

## Capture checks on an emulator

Use synthetic data only. In the app, save the receive connection, enable SMS and notifications
in **Sources**, grant the requested permissions, and add `com.android.shell` to selected packages.
Then generate real Android events:

```sh
adb emu sms send +15551234567 'Message487 synthetic SMS'
adb shell 'cmd notification post -t "Message487 test" message487-test "Synthetic notification"'
```

Open **Journal** and match each accepted event ID with n8n **Executions → Webhook → Output → body**.
The SMS event includes `sender`; the notification includes `title`. Repeat the notification command
with identical content: it should create no new event. Change its text: a new event should appear.
A long SMS exceeding one segment should arrive as one event with its complete text.

For recovery testing, stop this Compose server, generate an event, and check that it remains queued
or waiting for retry. Restart the server and wait for Android background scheduling. The same event
ID should become accepted. The app must retain the event across process restarts. **Retry now** can
request another attempt after a transient failure; pause prevents delivery until resumed.

The `error` endpoint exercises automatic retries. `invalid-ack` requires a manual retry; changing
the saved URL will not redirect an already queued event. Remove unwanted test records in the journal.
Disable sources or pause before generating events that should not be forwarded, and verify there
is no new journal record. Keep your SMS app unselected to avoid forwarding both its notification
and the SMS broadcast during this check.

## Lifecycle

```sh
docker compose -f DevServer/compose.yaml logs -f n8n
docker compose -f DevServer/compose.yaml stop
docker compose -f DevServer/compose.yaml up -d --wait
```

The authorization upgrade reimports the four fixtures and their credential once on existing
volumes; subsequent bootstrap runs once per data volume. Restarts preserve editor changes. To explicitly replace the
bundled workflows with the checked-in versions, stop n8n before importing:

```sh
docker compose -f DevServer/compose.yaml stop n8n
docker compose -f DevServer/compose.yaml run --rm --no-deps --entrypoint /bin/sh n8n /bootstrap/import.sh
docker compose -f DevServer/compose.yaml up -d --wait
```

This overwrites the four fixture workflow IDs; other workflows remain. To discard **all** local
workflows, execution history and settings, delete this Compose project's volume explicitly:

```sh
docker compose -f DevServer/compose.yaml down -v
```

## References

- [n8n Server CLI](https://docs.n8n.io/deploy/host-n8n/configure-n8n/use-the-command-line)
- [Owner provisioning](https://docs.n8n.io/deploy/host-n8n/configure-n8n/manage-settings-using-environment-variables)
- [Emulator networking](https://developer.android.com/studio/run/emulator-networking-address)

## Notification images

The receive webhook accepts the optional `body.images` array without changing the ACK contract.
It stays in the JSON request and execution history; the webhook does not automatically convert
it to n8n binary data. Each entry contains `mime_type`, `width`, `height`, `data_base64`, and `kind`
(`attachment`, `avatar`, or `app_icon`). Avatars and app icons are opt-in on the phone.
Filter by `kind` to route them separately from message attachments.
`presentation` is `big_picture` for BigPictureStyle, `gallery` for MessagingStyle image
attachments, and `file` for other images. Older queued images default to `file`.
MessagingStyle has no album ID: gallery grouping covers the extracted attachments of one notification.
Old/text-only events omit the array. A text-only workflow may accept an image-bearing event
without processing its images, so add the image-processing steps before acknowledging if
image forwarding is required.

For image-bearing events, use **Split Out** on `body.images` (include the original event ID),
then [Convert to File](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.converttofile/)
with **Move Base64 String to File**. The Base64 Input Field is `body.images.data_base64` if
Split Out retains that field path; MIME Type is `{{ $json.body.images.mime_type }}`.
Set a `.jpg` or `.png` file name matching that MIME type. Use an If node before splitting
so events without images still reach their text/ACK path. Process every image successfully
before sending an ACK with the original event ID. The [importable image workflows](../docs/en/n8n-examples.md) already include this processing
and route text-only requests without losing the ACK path.

Use synthetic images for development: the complete Base64 body is retained in n8n execution
history, just like text. The app defaults to 3 attachments, 256 KiB each and 512 KiB combined
before Base64; these output limits are configurable and can be disabled. Base64 increases size
by approximately one third. Image-bearing requests use HTTP chunked transfer; reverse proxies
must accept chunked request bodies and have sufficient body-size and upload-time limits.


## Telegram image routing

Run [telegram-images.js](telegram-images.js) in a Code node after formatting the text and
before assigning retry keys. All images attach to the first text part as one Rich Message:

- Big Picture becomes a photo block.
- MessagingStyle images become one collage (or one photo if only one remains).
- Avatars and app icons form a separate collage inside that same message.
- Other/legacy attachments become document blocks inside the message.

Use **Telegram Album 487 v0.2.1+**, operation **Send Rich Message**, Rich Message
`{{ $json.rich_message }}`, Binary Fields `{{ Object.keys($binary).join(",") }}`.
Preserve `binary` when assigning part keys and restore the pending loop item after the
deduplication lookup. Text parts retain `event_id:part_number`. Remaining parts of long text
use standard Send Message without repeating attachments. Text-only events also use Send Message.
There are no separate photo, album or document delivery branches in this workflow.
Telegram enforces Rich Message and collage limits; the ten-photo `sendMediaGroup` limit does
not determine Rich Message splitting. Failed media delivery must not be acknowledged as accepted.

Only remember a part key after successful Telegram delivery. Send the event ACK only after
all text and media parts are complete. As with the existing text workflow, delivery and key
persistence are not atomic: a crash between them can still produce a duplicate.

The built-in Telegram node supports uploading individual binary photos/documents, but its
media-group operation accepts URLs/file IDs rather than binary uploads. Install the MIT-licensed
[N8nTelegramNode487](https://github.com/andre487/N8nTelegramNode487) private extension for binary Rich Message uploads.
It reuses the Telegram API credential; no separate bot-token environment variable is needed.
For a private installation, its workflow type is `CUSTOM.telegramAlbum487`. The extension
must be installed on n8n main/worker processes before publishing workflows that use it.

Image content deduplication happens in Android: the notification fingerprint includes the
order-independent collection hash (bytes, roles and presentation). n8n part keys instead prevent
re-delivery of the same `event_id`; they do not suppress different events with matching images.
