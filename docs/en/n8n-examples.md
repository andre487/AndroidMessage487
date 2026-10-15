# Importable n8n examples

[English](../en/n8n-examples.md) | [Русский](../ru/n8n-examples.md)

Start with [n8n Cloud](n8n-cloud.md) or [self-hosted n8n](n8n-self-hosted.md), then choose
one level. The Android app sends the same webhook contract at every level; Telegram is optional.
Each JSON contains its scripts and can be imported without copying JavaScript from another file.
Examples use generic placeholders and synthetic data, not an exported personal installation.

## Choose a level

| Level | Import workflow | Result | Requirements |
| --- | --- | --- | --- |
| 1. Basic: text webhook | [01-webhook-text.json](../../examples/n8n/workflows/01-webhook-text.json) | Validate text and return ACK; no Telegram | Built-in nodes |
| 2. Basic + images | [02-webhook-images.json](../../examples/n8n/workflows/02-webhook-images.json) | Receive text and JPEG/PNG as n8n binary files; no Telegram | Built-in nodes |
| 3. Telegram: text | [03-telegram-text.json](../../examples/n8n/workflows/03-telegram-text.json) | Formatted text, split when long | Built-in Telegram node |
| 4. Telegram: standard images | [04-telegram-images-standard.json](../../examples/n8n/workflows/04-telegram-images-standard.json) | Text plus individual photos/files | Built-in nodes only |
| 5. Telegram: advanced | [05-telegram-images-advanced.json](../../examples/n8n/workflows/05-telegram-images-advanced.json) | Text and all images in one Rich Message; saved retry progress | Our node + Data Table; self-hosted installation |

[05-telegram-cleanup.json](../../examples/n8n/workflows/05-telegram-cleanup.json) is an optional
companion to level 5, not another receiving endpoint. It starts inactive and in **Dry Run**.
Examples target n8n 2.38.x. Levels 1–4 use built-in nodes and do not require installing our package.
The advanced example requires [Telegram Rich Messages](https://core.telegram.org/bots/api#sendrichmessage), including document blocks (10.3+).

## Import and connect

1. Download one JSON file using **Download raw file** on GitHub.
2. In an empty n8n workflow, select **⋯ → Import from File**. All examples start inactive.
3. Open **Webhook** and select/create your **Header Auth** credential:
   **Name** `Authorization`, **Value** `Bearer <your-private-token>`.
   Generate a token with a password manager or `openssl rand -hex 32`.
4. If another workflow uses the same path, change **Webhook → Path**. Do not import over an
   existing production workflow. Each example has a separate `message487/01-...` path.
5. For Telegram levels, complete the additional setup below.
6. Publish, then copy **Webhook → Production URL**, not the editor address or Test URL.
7. In Android **Connection**, set this URL, the same token **without `Bearer `**, a device
   label, and **n8n confirmation**. Save the connection. The Overview test sends text only.

The JSON has no credential IDs/names, chat IDs, server URLs, pinned executions, bot tokens,
API keys or project/table IDs. Missing credentials after import are intentional.
See the official [import guide](https://docs.n8n.io/workflows/export-import/).

## Levels 1 and 2: webhook without Telegram

Level 1 validates the event and returns `{status:"accepted", event_id:"..."}`. It intentionally
ignores image content if present; use level 2 to process images. Level 2 validates image metadata,
creates `image0`, `image1`, … in the **Read images → Binary** output, and returns the same ACK.
The JSON output keeps text and image metadata without repeating Base64. Text-only requests work too.

Neither example sends anything to Telegram, deduplicates server-side, or creates a durable inbox.
Execution history is an inspection tool, not a downstream delivery queue. To process events,
insert your own storage/action **before Respond**. For slow work, durably store first, ACK,
then process separately. An ACK removes the phone's payload after confirmed delivery.

## Levels 3 and 4: standard Telegram nodes

Follow [bot and credential setup](n8n-telegram.md). In **Configure**, replace `REPLACE_WITH_CHAT_ID`
with your fixed chat ID or `@channelusername`; do not take it from incoming requests.
Select your Telegram API credential in **every Telegram node**, in addition to Webhook Header Auth.

Level 3 sends only text. Level 4 adds images:

| Image | Level 4 behavior |
| --- | --- |
| `big_picture` attachment | Individual Send Photo |
| `gallery` attachments | Individual Send Photo for every image |
| Avatar / app icon | Individual Send Document with a role caption |
| Other/legacy attachment | Individual Send Document |

Text and images are separate messages in level 4. The built-in media-group operation does not
upload binary files as an album. Level 4 avoids external image hosting and custom packages;
choose level 5 for collages and media in the same message as text.

Both examples preserve all text by splitting at 3500 UTF-16 units without breaking surrogate
pairs; HTML inputs are escaped. The embedded formatter defaults to **UTC / en-GB**, with
English labels. Edit `timeZone`, `locale` and `messageTypes` in **Prepare Telegram text** if desired.
Keep Telegram **Parse Mode → HTML** and **On Error → Stop Workflow**.
These two levels have no server-side retry deduplication: an ACK loss can duplicate sends.

## Level 5: Rich Messages and retry progress

1. Install [N8nTelegramNode487 v0.2.1+](https://github.com/andre487/N8nTelegramNode487/releases)
   on your self-hosted n8n. Its MIT license and installation instructions are in that repository.
   The private installation exposes `CUSTOM.telegramAlbum487`; restart n8n after installation.
   Install on every main/worker process that executes workflows. No bot-token environment variable
   is needed: choose your existing Telegram API credential.
2. Import level 5. Select the Telegram credential in **Send rich message** and **Send text**.
3. Create a [Data Table](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.datatable/) in the **same n8n project** as the workflow, for example `message487_sent_parts`.
   Add one **String** column named exactly `part_key`; `id`, `createdAt`, `updatedAt` are system columns.
4. In **Configure**, replace both `REPLACE_WITH_CHAT_ID` and `REPLACE_WITH_SENT_PARTS_TABLE_ID`.
   Use the actual ID from your table's URL/selector, not its display name. Use a separate table
   for independently deployed forwarding workflows to avoid cross-workflow key collisions.
5. Publish. Old nodes showing `?` mean the private extension is not installed/loaded yet.

Media are embedded in the first text message:

| Image | Level 5 behavior |
| --- | --- |
| Big Picture | Photo block |
| MessagingStyle images | Collage; a single remaining image stays a photo |
| Avatar and app icon | Separate icon collage within the same message, with role captions |
| Other/legacy image | Document block within the same message |

No separate media messages are sent. If text exceeds the formatter's chunk size, later text
parts are separate messages without repeated media. Telegram clients choose preview sizes,
positions and visible captions; sending a 100 px source does not force a 100 px preview.
Photos may be processed by Telegram; document blocks preserve file delivery semantics.

The workflow uses `event_id:part_number` as its progress key. **Check already sent** skips
successful parts; **Remember part key** runs only after a successful send. **Respond** runs
only after all parts finish, including an already-delivered retry. Lookup replaces item data,
so **Restore pending part** restores text and binary files before delivery. Do not remove it.

This is not exactly-once delivery: a crash between Telegram acceptance and storing the key,
or concurrent requests for the same event, can duplicate a message. A table lookup/upsert is
not an atomic delivery lock. Serialize processing or add transactional claims if needed.
The phone's image-collection hash handles content deduplication independently: it includes
encoded bytes, roles and presentation, ignores collection order, and is combined with source/text.
The server keys delivery retries by event ID, not by image similarity.

### Optional key cleanup

Import the cleanup companion separately. In its **Configure**, set the **same table ID** as
level 5. The daily schedule selects keys older than 30 days. Start with **Dry Run** enabled,
inspect the selected rows, then disable Dry Run and publish if that retention matches your
retry horizon. Deleting keys allows old events to be sent again. Pending phone events can
survive longer than 30 days, so adjust the cutoff or leave cleanup disabled for your setup.
It does not delete notification bodies or images; those are not stored in the part-key table.

## Android image features and contract

**Sources → Graphics** controls JPEG (default, quality 80) or PNG, JPEG quality 0–100,
attachment count, longest side, per-image KiB and combined KiB. PNG preserves transparency;
JPEG uses white for transparent areas. Default limits are 3 attachments, 1024 px, 256 KiB each,
512 KiB combined, before Base64. Avatars and app icons have independent opt-ins, off by default;
they do not consume attachment count but share the byte budget. Disabling output limits keeps
format conversion and input/working-memory safeguards. Sources can opt in to discovering and
selecting new applications automatically. See [capture behavior](../../README.md#delivery-and-data)
and the Sources UI for details.

Each `images` entry has `mime_type`, `width`, `height`, `data_base64`, `kind` (`attachment`,
`avatar`, `app_icon`) and `presentation` (`big_picture`, `gallery`, `file`). Older images may
omit presentation; examples treat them as files. Gallery is our grouping of image attachments
from MessagingStyle in one notification, not an Android album ID. Big Picture and messaging
images can coexist in the payload. Text/SMS-only events omit `images`; schema version remains 1.

**Overview → Send text test** is always text-only. **Connection → Test message** offers
independent Big Picture, two-image gallery, avatar and app-icon switches; all off means text.
These selections do not enable collection opt-ins. Tests use synthetic cards and the app logo,
respect image settings, and are deliberately exempt from phone content deduplication.

## Verify with synthetic data

Use [text.json](../../examples/n8n/payloads/text.json) for every level and
[images.json](../../examples/n8n/payloads/images.json) for image-enabled levels. The PNGs are
synthetic colored rectangles. Set your own Production URL and token, then run from the repo root:

```sh
export MESSAGE487_WEBHOOK_URL='https://n8n.example.org/webhook/message487/YOUR-PATH'
# Set WEBHOOK_TOKEN locally to your private token, without the Bearer prefix.
curl --fail-with-body --max-time 10 "$MESSAGE487_WEBHOOK_URL" \
  -H "Authorization: Bearer ${WEBHOOK_TOKEN:?Set WEBHOOK_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/n8n/payloads/text.json
```

Change the filename to `images.json` for media. Use a **new event_id** for a new test;
repeat the exact ID to test level 5 retry suppression. Invalid core fields return HTTP 400;
missing/wrong authentication returns 401/403. Verify the exact ACK event ID in Android Journal.
Use Connection test switches to exercise all five image roles/presentations on a phone/emulator.

For level 5, repeat a delivered event: expect the same ACK and no new Telegram message.
Test text-only, each image type, mixed types, PNG/JPEG and a long escaped Unicode message.
Simulate a downstream failure: there must be no accepted ACK; a retry resumes unsent parts.
Disable pause and select sources to test real capture. Avoid selecting Telegram itself on the
phone that receives the bot messages, or it can create a forwarding loop.

## Limits, privacy and maintenance

The app has 10-second connect/read timeouts. Large multipart uploads or many sends can exceed
that; for heavy workloads add a durable server inbox/worker instead of acknowledging early.
Telegram applies photo/document, rate and Rich Message limits; preview layout is client-controlled.
Reverse proxies must accept chunked JSON uploads and sufficient body sizes; Base64 adds about one third.
Server errors, 408/425/429 and network failures retry; permanent errors/invalid ACK need manual action.
Changing Connection settings does not redirect old queued events, which retain their original destination.

Execution history can contain full text, Base64, binary files and authorization headers. Restrict
access and configure retention. Phone image files are private without extra application encryption;
text and token remain AES-GCM encrypted. Payload/image files are removed after confirmed delivery
or manual deletion. These examples contain no secrets, but configured exports may include credential
names/IDs and pinned data: remove them before sharing.

The JSON is generated, not edited independently. After changing shared scripts run
`python3 scripts/generate_n8n_examples.py`, then `--check`. CI verifies generated files,
validation, binary payloads, routing, progress keys and the two webhook endpoints in DevServer.
