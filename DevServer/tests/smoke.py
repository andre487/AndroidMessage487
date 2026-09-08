import json
import socket
import urllib.error
import urllib.request
import uuid


def post(scenario, payload, timeout=5, base_url='http://127.0.0.1:5678'):
    request = urllib.request.Request(
        f'{base_url}/webhook/message487/{scenario}',
        data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json'},
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


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
        'Passed: test/notification/SMS receive, validation, HTTP error, invalid ACK, timeout'
    )


if __name__ == '__main__':
    main()
