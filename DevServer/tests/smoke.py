import json
import socket
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DEV_TOKEN = json.loads(
    (Path(__file__).resolve().parents[1] / 'credentials/header-auth.json').read_text()
)[0]['data']['value'].removeprefix('Bearer ')


def post(
    scenario, payload, timeout=5, base_url='http://127.0.0.1:5678', token=DEV_TOKEN
):
    headers = {'Content-Type': 'application/json'}
    if token is not None:
        headers['Authorization'] = f'Bearer {token}'
    request = urllib.request.Request(
        f'{base_url}/webhook/message487/{scenario}',
        data=json.dumps(payload).encode(),
        headers=headers,
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        with error:
            body = error.read().decode()
        try:
            body = json.loads(body)
        except json.JSONDecodeError:
            pass
        return error.code, body


def main():
    event = {
        'schema_version': 1,
        'event_id': str(uuid.uuid4()),
        'device_id': 'dev-server-smoke-test',
        'device_code': 'smoke-test',
        'source': 'life.andre.message487',
        'source_name': 'Message487',
        'message_type': 'test',
        'text': 'Synthetic smoke test',
    }
    for scenario in ('receive', 'error', 'slow', 'invalid-ack'):
        for token in (None, '', 'wrong-token'):
            code, _ = post(scenario, event, token=token)
            assert code in (401, 403), (scenario, code)
    for message_type in ('test', 'notification', 'sms'):
        event.update(message_type=message_type, event_id=str(uuid.uuid4()))
        if message_type == 'notification':
            event['title'] = 'Synthetic notification'
        if message_type == 'sms':
            event.pop('title', None)
            event['sender'] = '+15551234567'
        code, body = post('receive', event)
        assert code == 200 and body == {
            'status': 'accepted',
            'event_id': event['event_id'],
        }, (code, body)
    code, body = post('receive', {})
    assert code == 400 and body['status'] == 'rejected', (code, body)
    code, body = post('error', event)
    assert code == 500, (code, body)
    code, body = post('invalid-ack', event)
    assert code == 200 and body['event_id'] != event['event_id'], (code, body)
    try:
        post('slow', event, timeout=1)
    except (TimeoutError, socket.timeout):
        pass
    else:
        raise AssertionError('Slow endpoint did not time out')
    print(
        'Passed: mandatory authorization on all endpoints, test/notification/SMS receive, validation, HTTP error, invalid ACK, timeout'
    )


if __name__ == '__main__':
    main()
