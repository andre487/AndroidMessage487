"""Build portable importable examples from the same scripts used by their tests."""

import argparse
import json
import struct
import uuid
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'examples/n8n'


def source(name):
    return (ROOT / 'DevServer' / name).read_text()


def public_script(name):
    return (
        source(name)
        .replace("timeZone = 'Europe/Moscow'", "timeZone = 'UTC'")
        .replace("locale = 'ru-RU'", "locale = 'en-GB'")
        .replace(
            "notification: 'Уведомление', test: 'Тест'",
            "notification: 'Notification', test: 'Test'",
        )
        .replace("? 'Аватар' : 'Значок приложения'", "? 'Avatar' : 'Application icon'")
    )


class Workflow:
    def __init__(self, slug, title):
        self.slug = slug
        self.data = {
            'name': f'Message487 · {title}',
            'active': False,
            'nodes': [],
            'connections': {},
            'settings': {'executionOrder': 'v1', 'timezone': 'UTC'},
        }

    def add(self, name, kind, parameters, x, y=0, version=2, **extra):
        node = {
            'id': str(
                uuid.uuid5(uuid.NAMESPACE_URL, f'message487-example:{self.slug}:{name}')
            ),
            'name': name,
            'type': kind if '.' in kind else f'n8n-nodes-base.{kind}',
            'typeVersion': version,
            'position': [x, y],
            'parameters': parameters,
            **extra,
        }
        self.data['nodes'].append(node)
        return node

    def connect(self, name, *branches):
        self.data['connections'][name] = {
            'main': [
                [{'node': dest, 'type': 'main', 'index': 0} for dest in branch]
                for branch in branches
            ]
        }

    def code(self, name, script, x, y=0):
        return self.add(name, 'code', {'jsCode': script}, x, y)

    def condition(self, name, expression, x, y=0):
        return self.add(
            name,
            'if',
            {
                'conditions': {
                    'options': {
                        'caseSensitive': True,
                        'leftValue': '',
                        'typeValidation': 'strict',
                        'version': 2,
                    },
                    'conditions': [
                        {
                            'id': str(uuid.uuid5(uuid.NAMESPACE_URL, name)),
                            'leftValue': expression,
                            'rightValue': True,
                            'operator': {
                                'type': 'boolean',
                                'operation': 'true',
                                'singleValue': True,
                            },
                        }
                    ],
                    'combinator': 'and',
                },
                'options': {},
            },
            x,
            y,
            version=2.2,
        )

    def configure(self, table=False, chat=True):
        values = {}
        if chat:
            values['chat_id'] = 'REPLACE_WITH_CHAT_ID'
        if table:
            values['sent_parts_table_id'] = 'REPLACE_WITH_SENT_PARTS_TABLE_ID'
        return self.add(
            'Configure',
            'set',
            {
                'assignments': {
                    'assignments': [
                        {
                            'id': str(uuid.uuid5(uuid.NAMESPACE_URL, key)),
                            'name': key,
                            'value': value,
                            'type': 'string',
                        }
                        for key, value in values.items()
                    ]
                },
                'options': {},
            },
            640,
            version=3.4,
        )

    def receiver(self, images):
        self.add(
            'Webhook',
            'webhook',
            {
                'httpMethod': 'POST',
                'path': f'message487/{self.slug}',
                'authentication': 'headerAuth',
                'responseMode': 'responseNode',
                'options': {},
            },
            0,
            version=2.1,
            webhookId=str(uuid.uuid5(uuid.NAMESPACE_URL, self.slug)),
        )
        self.code(
            'Validate event',
            f'const processImages = {str(images).lower()};\n'
            + source('workflow-validation.js'),
            220,
        )
        self.condition('Valid event', '={{ $json.valid }}', 440)
        self.add(
            'Reject',
            'respondToWebhook',
            {
                'respondWith': 'json',
                'responseBody': '={{ { status: "rejected", event_id: $json.event_id, reason: $json.reason } }}',
                'options': {'responseCode': 400},
            },
            640,
            300,
            version=1.4,
        )
        self.connect('Webhook', ['Validate event'])
        self.connect('Validate event', ['Valid event'])
        self.connect('Valid event', [], ['Reject'])

    def ack(self, x):
        self.add(
            'Respond',
            'respondToWebhook',
            {
                'respondWith': 'json',
                'responseBody': "={{ { status: 'accepted', event_id: $('Webhook').first().json.body.event_id } }}",
                'options': {'responseCode': 200},
            },
            x,
            -200,
            version=1.4,
        )

    def telegram(self, name, operation, x, y=0):
        parameters = {
            'resource': 'message',
            'operation': operation,
            'chatId': "={{ $('Configure').first().json.chat_id }}",
            'additionalFields': {},
        }
        if operation == 'sendMessage':
            parameters['text'] = '={{ $json.telegram_text }}'
            parameters['additionalFields'] = {
                'appendAttribution': False,
                'parse_mode': 'HTML',
                'disable_web_page_preview': True,
            }
        else:
            parameters.update(binaryData=True, binaryPropertyName='data')
            parameters['additionalFields'] = {'caption': '={{ $json.caption }}'}
        return self.add(name, 'telegram', parameters, x, y, version=1.2)


def table_id():
    return {
        '__rl': True,
        'mode': 'id',
        'value': "={{ $('Configure').first().json.sent_parts_table_id }}",
    }


def examples():
    result = {}
    for images, slug, title in [
        (False, '01-webhook-text', '01 · Webhook text'),
        (True, '02-webhook-images', '02 · Webhook images'),
    ]:
        w = Workflow(slug, title)
        w.receiver(images)
        if images:
            w.code('Read images', source('workflow-images.js'), 640)
            w.connect('Valid event', ['Read images'], ['Reject'])
            w.connect('Read images', ['Respond'])
        else:
            w.connect('Valid event', ['Respond'], ['Reject'])
        w.ack(880)
        result[slug] = w.data

    w = Workflow('03-telegram-text', '03 · Telegram text')
    w.receiver(False)
    w.configure()
    w.code('Prepare Telegram text', public_script('telegram-format.js'), 860)
    w.telegram('Send text', 'sendMessage', 1080)
    w.ack(1300)
    for a, b in [
        ('Configure', 'Prepare Telegram text'),
        ('Prepare Telegram text', 'Send text'),
        ('Send text', 'Respond'),
    ]:
        w.connect(a, [b])
    w.connect('Valid event', ['Configure'], ['Reject'])
    result[w.slug] = w.data

    for advanced, slug, title in [
        (False, '04-telegram-images-standard', '04 · Telegram images · standard nodes'),
        (
            True,
            '05-telegram-images-advanced',
            '05 · Telegram images · rich messages and retry progress',
        ),
    ]:
        w = Workflow(slug, title)
        w.receiver(True)
        w.configure(table=advanced)
        w.code('Prepare Telegram text', public_script('telegram-format.js'), 860)
        w.code(
            'Prepare image parts',
            (
                public_script('telegram-images.js')
                if advanced
                else source('telegram-standard-images.js')
            ),
            1080,
        )
        w.add(
            'Loop Over Parts',
            'splitInBatches',
            {'batchSize': 1, 'options': {}},
            1500,
            version=3,
        )
        w.ack(1720)
        w.connect('Valid event', ['Configure'], ['Reject'])
        w.connect('Configure', ['Prepare Telegram text'])
        w.connect('Prepare Telegram text', ['Prepare image parts'])
        w.connect(
            'Loop Over Parts',
            ['Respond'],
            ['Check already sent' if advanced else 'Is text'],
        )
        if advanced:
            w.code(
                'Create Part Keys',
                "const eventId = $('Webhook').first().json.body.event_id;\nreturn $input.all().map((item, index) => ({ ...item, json: { ...item.json, part_key: `${eventId}:${index + 1}` }, pairedItem: { item: index } }));",
                1300,
            )
            w.connect('Prepare image parts', ['Create Part Keys'])
            w.connect('Create Part Keys', ['Loop Over Parts'])
            filters = {
                'conditions': [
                    {
                        'keyName': 'part_key',
                        'condition': 'eq',
                        'keyValue': '={{ $json.part_key }}',
                    }
                ]
            }
            w.add(
                'Check already sent',
                'dataTable',
                {
                    'resource': 'row',
                    'operation': 'get',
                    'dataTableId': table_id(),
                    'matchType': 'allConditions',
                    'filters': filters,
                    'limit': 1,
                },
                1720,
                100,
                version=1.1,
                alwaysOutputData=True,
            )
            w.condition(
                'Already sent',
                '={{ $json.id !== undefined && $json.id !== null }}',
                1940,
                100,
            )
            w.code(
                'Restore pending part',
                "const item = $('Loop Over Parts').item;\nreturn [{ json: { ...item.json }, ...(item.binary ? { binary: item.binary } : {}) }];",
                2160,
                100,
            )
            w.condition(
                'Is rich message',
                '={{ $json.telegram_operation === "sendRichMessage" }}',
                2380,
                100,
            )
            w.add(
                'Send rich message',
                'CUSTOM.telegramAlbum487',
                {
                    'operation': 'sendRichMessage',
                    'chatId': "={{ $('Configure').first().json.chat_id }}",
                    'binaryProperties': "={{ Object.keys($binary).join(',') }}",
                    'richMessage': '={{ $json.rich_message }}',
                },
                2600,
                0,
                version=1,
            )
            w.telegram('Send text', 'sendMessage', 2600, 220)
            key = "={{ $('Loop Over Parts').item.json.part_key }}"
            w.add(
                'Remember part key',
                'dataTable',
                {
                    'resource': 'row',
                    'operation': 'upsert',
                    'dataTableId': table_id(),
                    'matchType': 'allConditions',
                    'filters': {
                        'conditions': [
                            {'keyName': 'part_key', 'condition': 'eq', 'keyValue': key}
                        ]
                    },
                    'columns': {
                        'mappingMode': 'defineBelow',
                        'value': {'part_key': key},
                        'matchingColumns': ['part_key'],
                        'schema': [
                            {
                                'id': 'part_key',
                                'displayName': 'part_key',
                                'required': False,
                                'defaultMatch': False,
                                'display': True,
                                'type': 'string',
                                'readOnly': False,
                                'removed': False,
                            }
                        ],
                        'attemptToConvertTypes': False,
                        'convertFieldsToString': False,
                    },
                    'options': {},
                },
                2820,
                100,
                version=1.1,
            )
            w.connect('Check already sent', ['Already sent'])
            w.connect('Already sent', ['Loop Over Parts'], ['Restore pending part'])
            w.connect('Restore pending part', ['Is rich message'])
            w.connect('Is rich message', ['Send rich message'], ['Send text'])
            for name in ['Send rich message', 'Send text']:
                w.connect(name, ['Remember part key'])
            w.connect('Remember part key', ['Loop Over Parts'])
        else:
            w.connect('Prepare image parts', ['Loop Over Parts'])
            w.condition('Is text', '={{ !$json.telegram_operation }}', 1720, 100)
            w.condition(
                'Is photo', '={{ $json.telegram_operation === "sendPhoto" }}', 1940, 220
            )
            w.telegram('Send text', 'sendMessage', 2160, 0)
            w.telegram('Send photo', 'sendPhoto', 2160, 160)
            w.telegram('Send file', 'sendDocument', 2160, 320)
            w.connect('Is text', ['Send text'], ['Is photo'])
            w.connect('Is photo', ['Send photo'], ['Send file'])
            for name in ['Send text', 'Send photo', 'Send file']:
                w.connect(name, ['Loop Over Parts'])
        result[slug] = w.data

    w = Workflow('05-telegram-cleanup', '05 · Optional sent-key cleanup')
    w.add(
        'Daily',
        'scheduleTrigger',
        {
            'rule': {
                'interval': [{'field': 'cronExpression', 'expression': '0 3 * * *'}]
            }
        },
        400,
        version=1.2,
    )
    w.configure(table=True, chat=False)
    w.add(
        'Delete old keys',
        'dataTable',
        {
            'resource': 'row',
            'operation': 'deleteRows',
            'dataTableId': table_id(),
            'matchType': 'allConditions',
            'filters': {
                'conditions': [
                    {
                        'keyName': 'createdAt',
                        'condition': 'lt',
                        'keyValue': '={{ $now.minus({ days: 30 }).toISO() }}',
                    }
                ]
            },
            'options': {'dryRun': True},
        },
        880,
        version=1.1,
    )
    w.connect('Daily', ['Configure'])
    w.connect('Configure', ['Delete old keys'])
    result[w.slug] = w.data
    return result


def png(width, height, color):
    def chunk(kind, data):
        return (
            struct.pack('!I', len(data))
            + kind
            + data
            + struct.pack('!I', zlib.crc32(kind + data))
        )

    return (
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress((b'\0' + bytes(color) * width) * height))
        + chunk(b'IEND', b'')
    )


def payloads():
    import base64

    event = {
        'schema_version': 1,
        'event_id': 'example-text-001',
        'device_id': 'example-installation',
        'device_code': 'example-phone',
        'message_type': 'test',
        'occurred_at': '2026-01-01T12:00:00Z',
        'source': 'org.example.demo',
        'source_name': 'Example App',
        'text': 'Synthetic connection test',
    }
    images = []
    for kind, presentation, color in [
        ('attachment', 'big_picture', (45, 90, 180)),
        ('attachment', 'gallery', (40, 140, 90)),
        ('attachment', 'gallery', (150, 60, 160)),
        ('avatar', 'file', (230, 150, 30)),
        ('app_icon', 'file', (40, 150, 200)),
    ]:
        images.append(
            {
                'mime_type': 'image/png',
                'width': 64,
                'height': 48,
                'kind': kind,
                'presentation': presentation,
                'data_base64': base64.b64encode(png(64, 48, color)).decode(),
            }
        )
    return {
        'text': event,
        'images': {**event, 'event_id': 'example-images-001', 'images': images},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--check',
        action='store_true',
        help='Fail if committed examples differ from generated ones',
    )
    args = parser.parse_args()
    files = {
        OUT / 'workflows' / f'{name}.json': data for name, data in examples().items()
    }
    files.update(
        {OUT / 'payloads' / f'{name}.json': data for name, data in payloads().items()}
    )
    for path, data in files.items():
        text = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
        if args.check:
            if not path.exists() or path.read_text() != text:
                raise SystemExit(f'Outdated example: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    print(f'{len(files)} example files {"verified" if args.check else "generated"}')


if __name__ == '__main__':
    main()
