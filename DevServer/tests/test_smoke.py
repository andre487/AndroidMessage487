import http.server
import json
import threading
import unittest

import smoke


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.server.received = (self.path, self.headers['Content-Type'], payload)
        self.send_response(500 if self.path.endswith('/error') else 200)
        self.end_headers()
        if self.path.endswith('/malformed'):
            self.wfile.write(b'not JSON')
        else:
            self.wfile.write(json.dumps({'event_id': payload['event_id']}).encode())

    def log_message(self, *_args):
        pass


class SmokeTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_posts_unicode_json_and_returns_ack(self):
        event = {'event_id': 'fixture', 'text': 'Тестовое SMS\n123'}
        self.assertEqual(
            (200, {'event_id': 'fixture'}),
            smoke.post('receive', event, base_url=self.base_url),
        )
        self.assertEqual(
            ('/webhook/message487/receive', 'application/json', event),
            self.server.received,
        )

    def test_returns_http_failure_for_scenario_assertions(self):
        self.assertEqual(
            (500, {'event_id': 'fixture'}),
            smoke.post('error', {'event_id': 'fixture'}, base_url=self.base_url),
        )

    def test_malformed_response_fails_instead_of_passing_smoke(self):
        with self.assertRaises(json.JSONDecodeError):
            smoke.post('malformed', {'event_id': 'fixture'}, base_url=self.base_url)
