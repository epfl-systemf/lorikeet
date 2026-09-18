"""Student-facing WSGI app. Run one Gunicorn worker; see DEPLOYMENT.md."""
import json
import logging
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit

from generate_feedback import append_event


def create_app(output, logs, public_origin):
    output = Path(output).resolve()
    logs = Path(logs).resolve()
    origin = urlsplit(public_origin)
    if (origin.scheme not in ('http', 'https') or not origin.netloc
            or origin.path or origin.query or origin.fragment
            or origin.username or origin.password):
        raise ValueError('LORIKEET_PUBLIC_ORIGIN must be an origin such as https://cs-214.epfl.ch')
    if logs == output or output in logs.parents:
        raise ValueError('Logs must be stored outside the generated pages directory')
    token = secrets.token_urlsafe(32)

    def application(environ, start_response):
        def respond(status, data, content_type='application/json; charset=utf-8'):
            body = json.dumps(data).encode() if isinstance(data, dict) else data
            start_response(status, [('Content-Type', content_type),
                                    ('Content-Length', str(len(body))),
                                    ('Cache-Control', 'no-store'),
                                    ('X-Content-Type-Options', 'nosniff')])
            return [b'' if environ['REQUEST_METHOD'] == 'HEAD' else body]

        path = environ.get('PATH_INFO', '/')
        method = environ['REQUEST_METHOD']
        try:
            if method in ('GET', 'HEAD'):
                if path == '/api/log-token':
                    return respond('200 OK', {'token': token})
                filename = 'overview.html' if path == '/' else path.removeprefix('/')
                if re.fullmatch(r'[A-Za-z0-9._-]+\.html', filename):
                    page = (output / filename).resolve()
                    if page.parent == output and page.is_file():
                        return respond('200 OK', page.read_bytes(), 'text/html; charset=utf-8')
                return respond('404 Not Found', {'error': 'Not found'})
            if method != 'POST':
                return respond('405 Method Not Allowed', {'error': 'Method not allowed'})
            if path != '/api/log-events':
                return respond('404 Not Found', {'error': 'Not found'})
            if environ.get('HTTP_ORIGIN') != public_origin:
                return respond('403 Forbidden', {'error': 'Invalid origin'})
            supplied_token = environ.get('HTTP_X_LOG_TOKEN', '')
            if not secrets.compare_digest(supplied_token, token):
                return respond('403 Forbidden', {'error': 'Refresh the log connection'})
            if environ.get('CONTENT_TYPE', '').split(';')[0].strip() != 'application/json':
                return respond('415 Unsupported Media Type', {'error': 'Expected JSON'})
            size = int(environ.get('CONTENT_LENGTH') or '0')
            if not 0 < size <= 32_000:
                return respond('400 Bad Request', {'error': 'Invalid log event size'})
            payload = json.loads(environ['wsgi.input'].read(size))
            return respond('200 OK', append_event(logs, payload))
        except (ValueError, TypeError, KeyError) as error:
            return respond('400 Bad Request', {'error': str(error)})
        except OSError:
            logging.exception('Feedback storage failed')
            return respond('500 Internal Server Error', {'error': 'Storage unavailable; please retry'})

    return application


def build_app():
    here = Path(__file__).resolve().parent
    return create_app(
        os.environ.get('LORIKEET_OUTPUT_DIR', str(here / 'generated')),
        os.environ.get('LORIKEET_LOG_DIR', str(here / 'logs')),
        os.environ.get('LORIKEET_PUBLIC_ORIGIN', 'https://cs-214.epfl.ch'),
    )
