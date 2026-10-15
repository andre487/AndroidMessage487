# Forward Message487 notifications to Telegram

[English](../en/n8n-telegram.md) | [Русский](../ru/n8n-telegram.md)

First follow the [n8n Cloud](n8n-cloud.md) or [self-hosted n8n](n8n-self-hosted.md)
guide and confirm a test event. The following steps are the same for both setups.
Keep the bot token in **n8n Credentials**; it is not needed in Android. Forwarded
messages are available to the selected Telegram chat and may remain in n8n history.

## 1. Create a bot and credential

1. Open the official [@BotFather](https://t.me/BotFather) in Telegram.
2. Send `/newbot`, then choose a name and username.
3. Save the issued token in a new **Telegram** credential in n8n, in **Access Token**.
   Do not put it in node text, the webhook URL or exported workflow files.
4. Open a private chat with the new bot and press **Start** or send `/start` so it
   can send you messages.

References: [creating bots](https://core.telegram.org/bots/features#botfather) and
[n8n Telegram credentials](https://docs.n8n.io/integrations/builtin/credentials/telegram/).

## 2. Find the chat ID

For a new bot that is not connected to a Telegram Trigger, save this code locally as
`telegram-chat-id.py` and run `python3 telegram-chat-id.py`. It prompts for the token
without echoing it and reads updates without sending messages. Send `/start` to the
bot from your Telegram account before running it.

```python
import getpass
import json
import urllib.error
import urllib.request

bot_token = getpass.getpass('Telegram bot token: ').strip()
request = urllib.request.Request(
    f'https://api.telegram.org/bot{bot_token}/getUpdates',
    data=b'{"timeout":0,"limit":100}',
    headers={'Content-Type': 'application/json'},
)
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
except (urllib.error.URLError, TimeoutError):
    raise SystemExit('Could not read updates; check token, network and existing webhook') from None

chats = {}
for update in result.get('result', []):
    message = update.get('message') or update.get('channel_post') or {}
    chat = message.get('chat') or {}
    if 'id' in chat:
        chats[chat['id']] = chat.get('type', 'unknown')
for chat_id, chat_type in chats.items():
    print(f'chat_id={chat_id} type={chat_type}')
if not chats:
    print('No chats found. Send /start to the bot and run again.')
```

Copy the desired ID into the Telegram node. For a group, add the bot and send
`/start@your_bot_username`, then read the group ID from updates. Preserve any minus
sign. For a public channel, you can use `@channelusername`; add the bot as an
administrator with permission to post messages.

`getUpdates` cannot be used while a Telegram webhook is installed. If the bot is
already connected to Telegram Trigger, read `message.chat.id` from its execution
instead. Do not delete a working bot's webhook just to obtain an ID. This forwarding
workflow does not need Telegram Trigger: its incoming trigger is Webhook.
See [getUpdates](https://core.telegram.org/bots/api#getupdates).

## 3. Import the forwarding level you need

| Example | Import |
| --- | --- |
| Text only | [03-telegram-text.json](../../examples/n8n/workflows/03-telegram-text.json) |
| Images, built-in Telegram nodes | [04-telegram-images-standard.json](../../examples/n8n/workflows/04-telegram-images-standard.json) |
| Rich Messages with all media and retry progress | [05-telegram-images-advanced.json](../../examples/n8n/workflows/05-telegram-images-advanced.json) |

See the [complete setup and verification guide](n8n-examples.md), including the two webhook-only
levels, image contract, gallery/icon collages, optional cleanup and limitations.
After import, select your Header Auth credential in Webhook and Telegram API credential in
every Telegram node. Set your fixed `chat_id` in **Configure**. Advanced additionally needs
our MIT-licensed node and a project Data Table with a String column `part_key`.
The exports contain no personal credentials, chat IDs, table IDs or execution data.

All examples validate events before sending and acknowledge only after successful delivery.
Level 3 ignores images. Level 4 sends photos/files separately using built-in nodes.
Level 5 attaches Big Picture, gallery and icons to the first text Rich Message.
Long text still has subsequent text-only parts. Text and image tests are independent from
capture settings in Android **Connection → Test message**; the Overview test stays text-only.
The public formatter defaults to UTC and English labels; customize its locale/time zone if needed.

## 4. Verify and handle retries

Publish, enter the Production URL and token in Android, then send a text test. For image-enabled
levels use the Connection test switches. Match `event_id` in Journal, n8n Executions and ACK.
Test each image type and a mixed set. Repeat an identical ID to verify level 5 progress keys.
Levels 3–4 do not deduplicate retries. Level 5 reduces repeats but cannot guarantee exactly-once
across concurrent requests or a crash between sending and persisting a key.

Keep **On Error → Stop Workflow**. HTTP 429 needs rate-limit handling; network and server
errors can cause phone retries. Large uploads/many sends may exceed the phone's 10-second
read timeout: use a durable server inbox/worker for heavy workloads instead of an early ACK.
Do not select Telegram as a notification source on the receiving phone, to avoid a forwarding loop.
Execution history may contain text, binary data and tokens in request headers: configure retention.
