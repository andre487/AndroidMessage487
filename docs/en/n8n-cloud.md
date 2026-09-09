# Connect Message487 to n8n Cloud

[English](../en/n8n-cloud.md) | [Русский](../ru/n8n-cloud.md)

You need a browser, an n8n Cloud account and a phone with [Message487 installed](apk-installation.md).
Every step uses the interface: no server installation, terminal or coding is required.
It is easiest to open n8n on a computer and the app on your phone.

At the end, a test message from the app will appear in n8n's history. You can add
Telegram forwarding after checking the connection.

## 1. Open your n8n

Sign up for [n8n Cloud](https://docs.n8n.io/deploy/use-n8n-cloud/start-your-free-trial)
and open your instance's editor. If you already have an account, sign in.
Cloud provides an HTTPS address. Check [n8n pricing](https://n8n.io/pricing/)
for current trial conditions, prices and execution limits.

In n8n, an automation is called a **workflow** and its individual steps are called
**nodes**. We will import a ready-made workflow with two nodes.

## 2. Import the ready-made workflow

1. Use **Create Workflow** to create a new, empty workflow.
2. Open **⋯ → Import from URL** in the editor's upper-right corner.
3. Paste this address and confirm the import:

   ```text
   https://raw.githubusercontent.com/andre487/AndroidMessage487/main/DevServer/workflows/receive.json
   ```

4. You should see two connected nodes: **Webhook → Respond**. The first receives
   the message; the second confirms receipt to the phone.
5. Name the workflow, for example `Message487 — my phone`.

If URL import is unavailable, open the [workflow file](../../DevServer/workflows/receive.json)
on GitHub, download it using **Download raw file**, then select
**⋯ → Import from File** in n8n. You do not need to edit the file contents.
[Official import guide](https://docs.n8n.io/build/manage-workflows/export-and-import).

A warning about unconfigured credentials after import is expected: you will set
up your own secret next. The **Respond** node is already configured; leave it as it is.

## 3. Set a secret for your phone connection

The token is a separate secret password connecting the app to this workflow.
It is neither your n8n account password nor a Telegram bot token.

1. Open the password generator in your password manager. Generate at least 32
   random letters and digits with no spaces. Save it as `Message487 webhook`
   so you can copy it to your phone later.
2. Double-click **Webhook** and make sure **Authentication** is **Header Auth**.
3. Under **Credential for Header Auth**, select **Create new credential**.
   Give the new record a recognizable name, such as `Message487 phone`.
   Inside that record, fill in these two fields:

   | n8n field | What to enter |
   | --- | --- |
   | **Name** | `Authorization` |
   | **Value** | The word `Bearer`, one ordinary space, then your generated token |

4. Click **Save** and make sure the new credential is selected in Webhook.
5. Keep the imported Webhook settings: **HTTP Method → POST** and
   **Respond → Using 'Respond to Webhook' Node**. If another published workflow
   already uses the same **Path**, give this one a different path, such as `message487/second-phone`.

Spell it **`Bearer`**, not `Bearier`. Do not add quotes or angle brackets.
n8n needs the `Bearer ` prefix; the app's token field **does not**: the app adds it
for you. Do not select the public test credential `Message487 local webhook`.
[Official Header Auth guide](https://docs.n8n.io/integrations/builtin/credentials/webhook/).

## 4. Publish and copy the address

1. Return to the workflow canvas and click **Publish**. Confirm publication if prompted.
   Older n8n versions use an **Active** switch instead.
2. Open **Webhook** again, select **Production URL** and copy the complete address.
3. Transfer that address to your phone, for example using a synced note.

Copy the address from the Webhook node, not the browser's address bar.
You do not need **Test URL** or **Listen for test event** for this setup.
Publish again after editing nodes.
[Official webhook URL guide](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.webhook/).

## 5. Connect the app and send a test

Open the **Connection** tab at the bottom of Message487 and fill in:

| App field | What to enter |
| --- | --- |
| **Webhook URL** | The complete **Production URL** copied from n8n |
| **Webhook token** | Your generated token **without `Bearer `** |
| **Device code** | A recognizable phone name, such as `my-phone` |
| **n8n confirmation** | Leave enabled |

Tap **Save and send test event**. Open **Journal**, then the new test event: success means
**Accepted by webhook** and **HTTP 200**. This test does not yet require notification
access or SMS permission.

## 6. Find the message in n8n

1. In your browser, open the workflow's **Executions** tab: this is its run history.
2. Select the latest execution after tapping the test button.
3. Select **Webhook**, open **Output** on the right, switch to **JSON** if needed,
   then expand **body**. It contains the message text (`text`) and phone name
   (`device_code`). Its `event_id` matches the event ID in the app journal.

The imported workflow already saves successful executions. If history is empty,
open **⋯ → Settings** and check **Save successful production executions**:
saving must be enabled. Then send a **new** test from the phone.
[Official workflow settings](https://docs.n8n.io/build/manage-workflows/configure-workflow-settings).

Message contents and headers containing the token may be stored in Cloud history.
Consider this when selecting apps to forward and granting access to your n8n.

## 7. Enable your sources

Open **Sources** in the app. For notifications, grant Android notification access
and select apps; for SMS, enable SMS capture and grant its permission.
Test with a new notification or SMS: old messages from your phone's history are not
forwarded retroactively. Selecting both SMS capture and your SMS app's notifications
can deliver the same SMS twice.

The ready-made workflow currently only receives messages and acknowledges them to
the phone. Continue with [Telegram forwarding](n8n-telegram.md) to send them to a chat.
“Accepted by webhook” alone does not confirm Telegram delivery.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| **401/403** in the journal | Check `Authorization`, the spelling of `Bearer`, and one space before the token in n8n. The app needs the same token without the prefix. Make sure Webhook uses your new credential. |
| **404** | Publish the workflow and copy **Production URL** again. Do not use the editor page address or **Test URL**. |
| **HTTP 200** but invalid confirmation | Check the **Webhook → Respond** connection and Webhook response mode from step 3. Keep the imported Respond settings. |
| No message visible in the editor | Open **Executions**, not just the workflow canvas; check history settings in step 6. |
| Network error or waiting | Check the phone's internet access and your n8n Cloud availability. Ensure the instance is running and its plan limit has not been exhausted. |
| Tests arrive but notifications do not | Check **Sources**, selected apps, Android permissions and the forwarding pause state. Then create a new notification. |

After fixing the address or token, tap **Save and send test event** again. Existing events
keep their original connection settings; retrying them does not test the new address
or token. You can delete unwanted old records from the journal.

The app's [diagnostic log](diagnostics.md) can help with further troubleshooting.
See the [technical guide](n8n-self-hosted.md) for message formats and acknowledgement details.
