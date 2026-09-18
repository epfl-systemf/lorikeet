import csv
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

from serve_feedback import create_app


class PublicServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.pages = self.root / 'generated'
        self.pages.mkdir()
        (self.pages / 'overview.html').write_text('<a href="report.html">Example</a>')
        (self.pages / 'report.html').write_text('<h1>Feedback</h1>')
        self.logs = self.root / 'logs'
        self.origin = 'https://cs-214.epfl.ch'
        self.app = create_app(self.pages, self.logs, self.origin)

    def request(self, path, method='GET', payload=None, **headers):
        body = json.dumps(payload).encode() if payload is not None else b''
        environ = dict(PATH_INFO=path, REQUEST_METHOD=method,
                       CONTENT_LENGTH=str(len(body)), CONTENT_TYPE='application/json',
                       **headers)
        environ['wsgi.input'] = BytesIO(body)
        response = {}
        def start(status, fields):
            response['status'] = int(status.split()[0])
            response['headers'] = dict(fields)
        response['body'] = b''.join(self.app(environ, start))
        return response

    def event(self):
        return dict(event_id=str(uuid.uuid4()), session_id='session', report_id='report',
                    submission='lint-0', run='example', event_type='feedback_view',
                    timestamp='2026-09-18T12:00:00Z', rating=None,
                    issue=dict(id='issue-0', rule='Var Usage', file='find.scala', line=4, column=3))

    def token(self):
        return json.loads(self.request('/api/log-token')['body'])['token']

    def post(self, event, token=None, origin=None):
        return self.request('/api/log-events', 'POST', event,
                            HTTP_X_LOG_TOKEN=token or self.token(),
                            HTTP_ORIGIN=origin or self.origin)

    def test_overview_and_relative_link(self):
        response = self.request('/')
        self.assertEqual(response['status'], 200)
        self.assertIn(b'href="report.html"', response['body'])
        self.assertEqual(self.request('/report.html')['status'], 200)
        self.assertEqual(self.request('/report.html', 'HEAD')['body'], b'')

    def test_private_routes_and_traversal_are_not_served(self):
        (self.root / 'private.html').write_text('secret')
        (self.pages / 'symlink.html').symlink_to(self.root / 'private.html')
        for path in ('/editor', '/api/templates', '/api/logs/events', '/api/logs/summary',
                     '/../private.html', '/symlink.html'):
            self.assertEqual(self.request(path)['status'], 404, path)
        self.assertEqual(self.request('/api/templates', 'POST', {})['status'], 404)

    def test_logs_persist_and_retry_is_deduplicated_after_restart(self):
        event = self.event()
        self.assertEqual(self.post(event)['status'], 200)
        self.app = create_app(self.pages, self.logs, self.origin)
        self.assertEqual(self.post(event)['status'], 200)
        events = (self.logs / 'feedback_events.jsonl').read_text().splitlines()
        self.assertEqual(len(events), 1)
        self.assertIn('server_received_at', json.loads(events[0]))
        with (self.logs / 'feedback_summary.csv').open() as stream:
            row = next(csv.DictReader(stream))
        self.assertEqual(row['view_count'], '1')

    def test_concurrent_views_and_rating(self):
        token = self.token()
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.post(self.event(), token), range(8)))
        self.assertTrue(all(result['status'] == 200 for result in results))
        rating = self.event()
        rating.update(event_type='feedback_rating', rating='positive')
        self.assertEqual(self.post(rating, token)['status'], 200)
        with (self.logs / 'feedback_summary.csv').open() as stream:
            row = next(csv.DictReader(stream))
        self.assertEqual(row['view_count'], '8')
        self.assertEqual(row['rating'], 'positive')

    def test_invalid_origin_token_and_payload(self):
        self.assertEqual(self.post(self.event(), origin='https://other.example')['status'], 403)
        self.assertEqual(self.post(self.event(), token='wrong')['status'], 403)
        self.assertEqual(self.post({'bad': 'event'})['status'], 400)
        self.assertFalse(self.logs.exists())


if __name__ == '__main__':
    unittest.main()
