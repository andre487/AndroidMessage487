# Пересылка уведомлений из Message487 в Telegram

[English](../en/n8n-telegram.md) | [Русский](../ru/n8n-telegram.md)

Сначала подключите приложение по инструкции для [n8n Cloud](n8n-cloud.md)
или [собственного сервера](n8n-self-hosted.md) и проверьте тестовое событие.
Дальнейшие шаги одинаковы для обоих вариантов.
Telegram-токен хранится в **Credentials n8n**; в Android-приложение его вводить не нужно.
Сообщения будут доступны выбранному Telegram-чату и могут сохраняться в истории n8n.

## 1. Создайте бота и credential

1. В Telegram откройте официального [@BotFather](https://t.me/BotFather).
2. Отправьте `/newbot` и задайте имя и username бота.
3. Сохраните выданный токен в новом credential типа **Telegram** в n8n, поле
   **Access Token**. Не вставляйте токен в текст узла, webhook URL или экспорт workflow.
4. Откройте личный чат с новым ботом и нажмите **Start** либо отправьте `/start`.
   Это нужно, чтобы бот мог писать вам.

Справка: [создание бота](https://core.telegram.org/bots/features#botfather),
[Telegram credentials в n8n](https://docs.n8n.io/integrations/builtin/credentials/telegram/).

## 2. Узнайте chat_id

Для нового бота, к которому ещё не подключён Telegram Trigger, сохраните следующий
код в локальный файл `telegram-chat-id.py` и запустите `python3 telegram-chat-id.py`.
Он запросит токен скрытым вводом и прочитает входящие обновления, не отправляя сообщений.
Перед запуском отправьте боту `/start` со своего Telegram-аккаунта.

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

Скопируйте ID нужного чата в настройку узла Telegram. Для группы добавьте бота в
группу, отправьте `/start@имя_бота` и возьмите ID группы из обновлений; сохраняйте
знак минус, если он есть. Для публичного канала можно использовать `@username`
канала, добавив бота администратором с правом публикации сообщений.

`getUpdates` несовместим с уже установленным webhook Telegram. Если этот бот
используется в Telegram Trigger, возьмите `message.chat.id` из входных данных
его execution. Не удаляйте webhook работающего бота ради получения ID.
Для отправки из Message487 **Telegram Trigger не нужен**: входной триггер — Webhook.
Официальная справка: [getUpdates](https://core.telegram.org/bots/api#getupdates).

## 3. Импортируйте нужный уровень пересылки

| Пример | Импорт |
| --- | --- |
| Только текст | [03-telegram-text.json](../../examples/n8n/workflows/03-telegram-text.json) |
| Картинки на стандартных Telegram nodes | [04-telegram-images-standard.json](../../examples/n8n/workflows/04-telegram-images-standard.json) |
| Rich Messages со всеми вложениями и прогрессом повторов | [05-telegram-images-advanced.json](../../examples/n8n/workflows/05-telegram-images-advanced.json) |

В [полной инструкции по примерам](n8n-examples.md) описаны все пять уровней, включая два webhook
без Telegram, контракт картинок, коллажи, дополнительную очистку ключей и ограничения.
После импорта выберите Header Auth в Webhook и Telegram API credential в каждом Telegram node.
В **Configure** задайте свой фиксированный `chat_id`. Расширенному варианту также нужны наш
MIT-пакет и Data Table в том же проекте с колонкой String `part_key`.
В экспортах нет личных credentials, chat ID, IDs таблиц и данных executions.

Все примеры проверяют запрос перед отправкой и подтверждают только успешную доставку.
Уровень 3 игнорирует картинки, уровень 4 отправляет фото/файлы отдельно стандартными nodes,
уровень 5 прикрепляет Big Picture, галерею и значки к первому Rich Message с текстом.
У длинного текста остаются следующие текстовые части. В **Connection → Test message** можно
выбирать тестовые изображения независимо от настроек сбора, а Overview всегда проверяет только текст.
Публичный formatter использует UTC и английские подписи; при необходимости измените locale/timeZone.

## 4. Проверьте доставку и повторы

Опубликуйте workflow, укажите Production URL и токен в Android и отправьте текстовый тест.
Для вариантов с изображениями используйте переключатели Connection. Сверьте `event_id` в Journal,
Executions и ACK. Проверьте каждый тип картинок и смешанный набор. Повторите исходный event ID для
проверки ключей уровня 5. Уровни 3–4 не подавляют повторы; уровень 5 уменьшает их число, но не даёт
exactly-once при параллельных запросах или креше между отправкой и сохранением ключа.

Оставьте **On Error → Stop Workflow**. Учитывайте HTTP 429 и повторную доставку после сетевых/серверных
ошибок. Большие файлы и много отправок могут превысить read timeout телефона 10 секунд: для тяжёлых
сценариев нужна надёжная входящая очередь и worker, а не ранний ACK.
Не выбирайте Telegram источником уведомлений на получающем телефоне, чтобы не создать петлю.
Executions могут содержать текст, binary и токены в заголовках: настройте retention.
