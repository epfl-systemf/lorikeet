#!/usr/bin/env python3
"""Generate offline feedback pages from Lorikeet lint reports and unified diffs.

Python 3.9+, standard library only. Paths default to this script's directory.
"""
import argparse
import hashlib
import html
import json
import re
import textwrap
import secrets
import os
import tempfile
import csv
import threading
import uuid
from datetime import datetime, timezone
from collections import Counter
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent


def project_rule_names():
    # Skip quoted patterns and comments so names inside Scala code are not rules.
    tokens = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|\#[^\n]*|\bname\s*=\s*("(?:\\.|[^"\\])*")')
    names = {}
    excluded = {'.git', 'node_modules', 'target', '.scala-build'}
    for path in sorted(HERE.parent.rglob('*.lorikeet.conf')):
        if excluded.intersection(path.relative_to(HERE.parent).parts):
            continue
        for token in tokens.finditer(path.read_text(encoding='utf-8')):
            if token.group(1):
                names[json.loads(token.group(1))] = None
    if not names:
        raise ValueError('No rules found in project .lorikeet.conf files')
    return list(names)


def align_project_templates(templates):
    _, existing = validate_templates(templates)
    aligned = {}
    for name in project_rule_names():
        template = dict(existing.get(name) or resolve_rule({'name': name}, {}))
        template['title'] = name
        template.pop('aliases', None)
        aligned[name] = template
    return validate_templates(aligned)


def parse_lint(text):
    issues = []
    name = message = None
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if re.fullmatch(r"\[[^\]]+\]", line):
            name = line[1:-1]
            i += 1
            if i >= len(lines):
                raise ValueError("Missing description after rule heading")
            message = re.sub(r"\s*\(\d+ occurrences\)$", "", lines[i]).strip()
        elif re.fullmatch(r".+:\d+:\d+", line):
            if name is None or i + 1 >= len(lines):
                raise ValueError("Issue location without rule or code")
            path, row, col = line.rsplit(":", 2)
            code = lines[i + 1]
            pointer = lines[i + 2] if i + 2 < len(lines) else ""
            width = len(pointer.strip()) if re.fullmatch(r"\s*\^+", pointer) else 1
            issues.append(dict(name=name, message=message, path=path,
                               line=int(row), column=int(col), code=code,
                               width=width))
            i += 2 if width > 1 or "^" in pointer else 1
        elif line.strip():
            raise ValueError("Unexpected lint report line: " + line)
        i += 1
    return issues


def parse_diff(text):
    """Keep original line numbers and whole changed blocks; never infer AST edits."""
    original = {}
    blocks = []
    old = new = None
    old_left = new_left = 0
    block = None

    def flush():
        nonlocal block
        if block is not None:
            blocks.append(block)
            block = None

    for line in text.splitlines():
        match = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
        if match:
            if old_left or new_left:
                raise ValueError("Truncated diff hunk")
            flush()
            old, count_old, new, count_new = match.groups()
            old, new = int(old), int(new)
            old_left = int(count_old) if count_old is not None else 1
            new_left = int(count_new) if count_new is not None else 1
        elif line.startswith("\\ No newline"):
            continue
        elif old is None or (old_left == 0 and new_left == 0):
            flush()
            if not (line.startswith(("--- ", "+++ ", "diff ", "index ")) or not line):
                raise ValueError("Unexpected diff content: " + line)
        elif line.startswith(" "):
            flush()
            original[old] = line[1:]
            old += 1
            new += 1
            old_left -= 1
            new_left -= 1
        elif line.startswith(("-", "+")):
            if block is None:
                block = dict(start=old, before=[], after=[])
            if line.startswith("-"):
                original[old] = line[1:]
                block["before"].append(line[1:])
                old += 1
                old_left -= 1
            else:
                block["after"].append(line[1:])
                new += 1
                new_left -= 1
        else:
            raise ValueError("Unexpected diff hunk line: " + line)
        if old_left < 0 or new_left < 0:
            raise ValueError("Diff hunk line counts do not match")
    flush()
    if old_left or new_left:
        raise ValueError("Truncated diff hunk")
    return original, blocks


def load_templates(path):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate template field or rule: ' + key)
            result[key] = value
        return result

    templates = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_keys)
    return align_project_templates(templates)


def validate_templates(templates):
    if not isinstance(templates, dict) or not templates:
        raise ValueError('Templates must be a nonempty object')
    lookup = {}
    for name, template in templates.items():
        if not isinstance(template, dict):
            raise ValueError('Expected a text template for ' + name)
        for field in ('title', 'location', 'what_to_improve_title', 'explanation', 'message',
                      'suggested_rewrite_title', 'rewrite_help', 'rewrite_code', 'no_rewrite'):
            if not isinstance(template.get(field), str):
                raise ValueError(name + ': missing text field ' + field)
        aliases = template.get('aliases', [])
        if not isinstance(aliases, list) or not all(isinstance(alias, str) for alias in aliases):
            raise ValueError(name + ': aliases must be a list of rule names')
        for alias in [name] + aliases:
            if alias in lookup:
                raise ValueError('Rule name belongs to multiple templates: ' + alias)
            lookup[alias] = template
        fill_template(template, {key: '' for key in (
            'file', 'line', 'column', 'rule_name', 'rule_message', 'rewrite', 'original_code')})
    return templates, lookup


def resolve_rule(issue, lookup):
    return lookup.get(issue['name'], dict(
        title=issue['name'],
        location='{{file}}:{{line}}:{{column}}',
        what_to_improve_title='{{rule_name}}', explanation='', message='{{rule_message}}',
        suggested_rewrite_title='Suggested code', rewrite_help='',
        rewrite_code='{{rewrite}}', no_rewrite=''))


def fill_template(template, values):
    """One substitution pass: braces inside student code remain literal text."""
    def replace(match):
        key = match.group(1)
        if key not in values:
            raise ValueError('Unknown template placeholder: ' + key)
        return str(values[key])
    return {key: re.sub(r'\{\{\s*(\w+)\s*\}\}', replace, value)
            for key, value in template.items() if isinstance(value, str)}


def escape(value):
    return html.escape(str(value), quote=True)


def paragraph(text, css='feedback-help'):
    return '<p class="' + css + '">' + escape(text) + '</p>' if text else ''


def render_document(mockup, title, main, interactive=True):
    # Reuse the user's mockup document, including its complete CSS and JavaScript.
    result, count = re.subn(r'<main\b[^>]*>.*?</main>', lambda _: main, mockup, count=1, flags=re.S)
    if count != 1:
        raise ValueError('The mockup must contain a main element')
    result = re.sub(r'<title>.*?</title>', lambda _: '<title>' + escape(title) + '</title>', result, count=1, flags=re.S)
    if not interactive:
        return re.sub(r'<script\b[^>]*>.*?</script>', '', result, flags=re.S)
    # Supplement the mockup's existing click/rating handlers for keyboard and same-line issues.
    extra = '''<script>
    document.querySelectorAll('.code-line.issue').forEach(line => {
      line.addEventListener('keydown', event => {
        if(event.key === 'Enter' || event.key === ' '){event.preventDefault();line.click();}
      });
    });
    document.querySelectorAll('[data-feedback-target]').forEach(button => {
      button.addEventListener('click', () => showFeedback(button.dataset.feedbackTarget));
    });
    </script>'''
    return result.replace('</body>', extra + '</body>')


TRACKING_SCRIPT = r"""
(() => {
  const report = JSON.parse(document.getElementById('feedback-log-data').textContent);
  const session = crypto.randomUUID();
  const prefix = 'lorikeet.feedback.' + report.report_id + '.';
  const ledger = new Map();
  const online = location.protocol === 'http:' || location.protocol === 'https:';
  const apiBase = new URL(location.pathname.includes('/generated/') ? '../api/' : './api/', location.href);
  let token = null, sending = false;
  function persist(record){
    ledger.set(record.event.event_id, record);
    try{localStorage.setItem(prefix + record.event.event_id, JSON.stringify(record));}
    catch{/* Keep the event in memory when browser storage is unavailable. */}
  }
  try{
    for(let i=0;i<localStorage.length;i++){
      const key=localStorage.key(i);
      if(key.startsWith(prefix)){
        try{const record=JSON.parse(localStorage.getItem(key));if(record.event?.event_id)ledger.set(record.event.event_id,record);}catch{}
      }
    }
  }catch{/* Browser storage may be unavailable. */}
  async function request(url, options={}){
    const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),5000);
    try{return await fetch(new URL(url.replace(/^\/api\//, ''), apiBase),{...options,signal:controller.signal,cache:'no-store'});}
    finally{clearTimeout(timeout);}
  }
  async function flush(){
    if(!online || sending)return;
    sending=true;
    try{
      if(!token){const response=await request('/api/log-token');if(!response.ok)throw Error('Log connection failed');token=(await response.json()).token;}
      for(const record of ledger.values()){
        if(record.synced)continue;
        const response=await request('/api/log-events',{method:'POST',headers:{'Content-Type':'application/json','X-Log-Token':token},body:JSON.stringify(record.event),keepalive:true});
        if(!response.ok){if(response.status===403)token=null;throw Error('Log save failed');}
        record.synced=true;persist(record);
      }
    }catch{/* Leave unsent events queued for the next connection attempt. */}
    finally{sending=false;}
  }
  function record(type, domId, rating=null){
    const issue=report.issues[domId];if(!issue)return;
    const timestamp=new Date().toISOString();
    const event={event_id:crypto.randomUUID(),session_id:session,report_id:report.report_id,submission:report.submission,run:report.run,event_type:type,timestamp,issue,rating};
    persist({event,synced:false});void flush();
  }
  const show=showFeedback;
  showFeedback=function(id){show(id);record('feedback_view',id);};
  document.querySelectorAll('.feedback [data-rating]').forEach(button=>button.addEventListener('click',()=>{
    const card=button.closest('.feedback');record('feedback_rating',card.id,button.dataset.rating);
  }));
  Object.keys(report.issues).forEach(id=>record('issue_loaded',id));
  window.addEventListener('online',flush);
  window.addEventListener('pagehide',flush);
  setInterval(flush,5000);
})();
"""


def build_report(report, sample_dir, lookup, mockup):
    issues = parse_lint(report.read_text(encoding='utf-8'))
    run = report.parent.name.removeprefix('grading_reports_')
    submission = report.name.removesuffix('.lint.txt')
    diff_dir = report.parent.parent / ('grading_diffs_' + run)
    paths = sorted({issue['path'] for issue in issues})
    panels = []
    for index, issue in enumerate(issues):
        issue['id'] = 'feedback-' + str(index)
        issue['feedback'] = resolve_rule(issue, lookup)
    for path in paths:
        basename = Path(path).name
        diff = diff_dir / (submission + '-' + basename + '.diff')
        unique = sum(Path(p).name == basename for p in paths) == 1
        lines, blocks = parse_diff(diff.read_text(encoding='utf-8')) if diff.is_file() and unique else ({}, [])
        file_issues = [issue for issue in issues if issue['path'] == path]
        for issue in file_issues:
            lines.setdefault(issue['line'], issue['code'])
            matches = [block for block in blocks if block['start'] <= issue['line'] < block['start'] + len(block['before'])]
            issue['change'] = matches[0] if len(matches) == 1 else None
        rows = []
        previous = None
        for number, code in sorted(lines.items()):
            if previous is not None and number > previous + 1:
                rows.append('<div class="hint">… lines omitted …</div>')
            previous = number
            matches = [issue for issue in file_issues if issue['line'] == number]
            attrs = ' class="code-line"'
            marker = '<div></div>'
            if matches:
                attrs = ' class="code-line issue" tabindex="0" role="button" aria-label="' + escape('Line ' + str(number) + ': ' + ', '.join(i['feedback']['title'] for i in matches)) + '"'
                attrs += ' aria-pressed="false" data-feedback-ids="' + escape(' '.join(i['id'] for i in matches)) + '"'
                marker = '<span class="marker" data-target="' + matches[0]['id'] + '" aria-label="Issue"></span>'
            rows.append('<div' + attrs + '>' + marker + '<div class="ln">' + str(number) + '</div><div class="code-text">' + escape(code) + '</div></div>')
        heading = 'Code' if len(paths) == 1 else escape(path)
        panels.append('<section class="panel"><div class="panel-title">' + heading + '</div><div class="code">' + ''.join(rows) + '</div><div class="hint">Line numbers match your original code.</div></section>')
    rating_match = re.search(r'<div class="rating">.*?</div>', mockup, re.S)
    if rating_match is None:
        raise ValueError('The mockup must contain its rating controls')
    cards = []
    for issue in issues:
        change = issue['change']
        replacement = ''
        if change:
            replacement = textwrap.dedent('\n'.join(change['after'])) if change['after'] else '(This code is removed.)'
        rule = fill_template(issue['feedback'], dict(
            file=issue['path'], line=issue['line'], column=issue['column'],
            rule_name=issue['name'], rule_message=issue['message'], rewrite=replacement,
            original_code=issue['code']))
        body = '<h3 class="feedback-subtitle">' + escape(rule['what_to_improve_title']) + '</h3>'
        message = rule['message']
        # Preserve the mockup's explanatory paragraph + rule message arrangement.
        body += paragraph(rule['explanation'], 'feedback-help' if message and message != rule['explanation'] else 'message')
        if message and message != rule['explanation']:
            body += paragraph(message, 'message')
        if issue['change']:
            change = issue['change']
            body += '<h3 class="feedback-subtitle">' + escape(rule['suggested_rewrite_title']) + '</h3>'
            overlapping = [i for i in issues if i['path'] == issue['path'] and i['change'] is change]
            help_text = rule['rewrite_help']
            if len(change['before']) > 1 or len(overlapping) > 1:
                end = change['start'] + len(change['before']) - 1
                help_text = 'Updated block for lines {}–{} from this run; it may include multiple changes:'.format(change['start'], end)
            body += paragraph(help_text)
            body += '<pre class="rewrite-result">' + escape(rule['rewrite_code']) + '</pre>'
        else:
            body += paragraph(rule['no_rewrite'])
        siblings = [i for i in issues if i['path'] == issue['path'] and i['line'] == issue['line']]
        if len(siblings) > 1:
            body += '<div>' + ''.join('<button class="rate" data-feedback-target="' + i['id'] + '">' + escape(i['feedback']['title']) + '</button>' for i in siblings) + '</div>'
        body += rating_match.group()
        cards.append('<section class="feedback" id="' + issue['id'] + '"><div class="feedback-body">' + body + '</div></section>')
    title = submission + ' · ' + run
    header_hint = '<div class="code-guide">Click a line to see its suggestions.</div>' if issues else ''
    left = panels[0] if len(panels) == 1 else '<div>' + ''.join(panels) + '</div>'
    empty = 'Click a line to see its suggestions.' if issues else 'There are no suggestions for this submission.'
    main = '<main class="shell"><div class="header"><h1>Feedback</h1>' + header_hint + '</div><div class="layout">' + left + '<aside class="panel feedback-panel"><div class="panel-title">Suggestions</div><div class="empty" id="empty">' + empty + '</div>' + ''.join(cards) + '</aside></div></main>'
    relative = report.relative_to(sample_dir).as_posix()
    filename = re.sub(r'[^A-Za-z0-9._-]', '-', run + '-' + submission) + '-' + hashlib.sha256(relative.encode()).hexdigest()[:8] + '.html'
    metadata = dict(report_id=filename.removesuffix('.html'), submission=submission, run=run,
                    issues={i['id']: dict(id=hashlib.sha256(json.dumps(
                        [i['name'], i['path'], i['line'], i['column'], i['message'], i['id']]).encode()).hexdigest()[:20],
                        rule=i['name'], file=i['path'], line=i['line'], column=i['column']) for i in issues})
    payload = json.dumps(metadata, ensure_ascii=True).replace('<', '\\u003c')
    page = render_document(mockup, title, main)
    tracking = '<script type="application/json" id="feedback-log-data">' + payload + '</script><script>' + TRACKING_SCRIPT + '</script>'
    return filename, title, page.replace('</body>', tracking + '</body>'), len(issues)


def build_overview(entries, mockup):
    rows = []
    for entry in entries:
        counts = entry['counts']
        rows.append('<tr><td><a href="' + escape(entry['filename']) + '">' + escape(entry['submission']) +
                    '</a></td><td>' + escape(entry['run']) + '</td><td>' + str(sum(counts.values())) +
                    '</td><td>' + escape(', '.join(sorted(counts)) or 'None') + '</td></tr>')
    overview = ('<main class="shell"><div class="header"><h1>Overview</h1></div>'
                '<section class="panel" style="overflow:auto"><table><thead><tr>'
                '<th>Submission</th><th>Run</th><th>Issues</th><th>Rules</th>'
                '</tr></thead><tbody>' + ''.join(rows) + '</tbody></table></section></main>')
    page = render_document(mockup, 'Overview', overview, interactive=False)
    return page.replace('</head>', '<style>table{width:100%;border-collapse:collapse;font-size:14px}'
                        'th,td{text-align:left;padding:14px 16px;border-bottom:1px solid var(--line)}'
                        'th{color:var(--muted);font-weight:600}tbody tr:last-child td{border-bottom:0}'
                        'a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}'
                        '</style></head>')


def generate(args):
    templates, lookup = load_templates(args.rules)
    mockup = args.mockup.read_text(encoding='utf-8')
    reports = sorted(args.data.glob('**/grading_reports_*/*.lint.txt'))
    if not reports:
        raise ValueError('No grading_reports_*/*.lint.txt files found under ' + str(args.data))
    pages = [build_report(report, args.data, lookup, mockup) for report in reports]
    args.output.mkdir(parents=True, exist_ok=True)
    entries = []
    for report, (filename, title, page, count) in zip(reports, pages):
        (args.output / filename).write_text(page, encoding='utf-8')
        entries.append(dict(filename=filename, submission=report.name.removesuffix('.lint.txt'),
                            run=report.parent.name.removeprefix('grading_reports_'),
                            counts=Counter(issue['name'] for issue in parse_lint(report.read_text(encoding='utf-8')))))
    page = build_overview(entries, mockup)
    (args.output / 'overview.html').write_text(page, encoding='utf-8')
    for obsolete in ('index.html', 'rules.html'):
        (args.output / obsolete).unlink(missing_ok=True)
    print('Generated {} reports ({} issues). Open {}'.format(len(pages), sum(p[3] for p in pages), args.output / 'overview.html'))


def atomic_write(path, content):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if path.exists():
            temporary.chmod(path.stat().st_mode & 0o777)
        temporary.replace(path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


LOG_LOCK = threading.Lock()
LOG_EVENT_FIELDS = {
    'event_id', 'session_id', 'report_id', 'submission', 'run',
    'event_type', 'timestamp', 'issue', 'rating',
}
LOG_SUMMARY_FIELDS = (
    'session_id', 'report_id', 'submission', 'run', 'issue_id', 'rule',
    'file', 'line', 'column', 'viewed', 'first_viewed_at', 'last_viewed_at',
    'view_count', 'rated', 'rating', 'rated_at',
)


def log_text(value, field, limit):
    if (not isinstance(value, str) or not value.strip() or len(value) > limit
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ValueError(f'{field} must be nonempty text of at most {limit} characters')
    return value


def log_timestamp(value, field='timestamp'):
    if not isinstance(value, str) or len(value) > 40 or 'T' not in value:
        raise ValueError(f'{field} must be an ISO 8601 UTC timestamp')
    try:
        parsed = datetime.fromisoformat(value[:-1] + '+00:00' if value.endswith('Z') else value)
    except ValueError as exc:
        raise ValueError(f'{field} must be an ISO 8601 UTC timestamp') from exc
    if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError(f'{field} must be an ISO 8601 UTC timestamp')
    return parsed.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def log_validate_event(payload):
    if not isinstance(payload, dict) or set(payload) != LOG_EVENT_FIELDS:
        raise ValueError('Event fields do not match the feedback log format')
    result = dict(payload)
    event_id = log_text(payload['event_id'], 'event_id', 36)
    try:
        result['event_id'] = str(uuid.UUID(event_id))
    except ValueError as exc:
        raise ValueError('event_id must be a UUID') from exc
    for field, limit in (('session_id', 200), ('report_id', 300), ('submission', 500), ('run', 300)):
        result[field] = log_text(payload[field], field, limit)
    event_type = payload['event_type']
    if event_type not in ('issue_loaded', 'feedback_view', 'feedback_rating'):
        raise ValueError('Unknown feedback event type')
    if event_type == 'feedback_rating':
        if payload['rating'] not in ('positive', 'negative'):
            raise ValueError('A rating event needs a positive or negative rating')
    elif payload['rating'] is not None:
        raise ValueError('Only rating events may include a rating')
    result['timestamp'] = log_timestamp(payload['timestamp'])
    issue = payload['issue']
    if not isinstance(issue, dict) or set(issue) != {'id', 'rule', 'file', 'line', 'column'}:
        raise ValueError('Issue fields do not match the feedback log format')
    issue = dict(issue)
    for field, limit in (('id', 200), ('rule', 500), ('file', 2000)):
        issue[field] = log_text(issue[field], 'issue.' + field, limit)
    for field in ('line', 'column'):
        if type(issue[field]) is not int or not 1 <= issue[field] <= 10000000:
            raise ValueError(f'issue.{field} must be a positive integer')
    result['issue'] = issue
    return result


def log_read_events(path):
    """Recover an incomplete final append; never silently ignore earlier corruption."""
    if not path.exists():
        return []
    data = path.read_bytes()
    events = []
    offset = 0
    lines = data.splitlines(keepends=True)
    for index, line in enumerate(lines):
        try:
            event = json.loads(line)
            received = log_timestamp(event['server_received_at'], 'server_received_at')
            payload = {key: value for key, value in event.items() if key != 'server_received_at'}
            event = log_validate_event(payload)
            event['server_received_at'] = received
        except (ValueError, TypeError, KeyError, UnicodeDecodeError) as exc:
            if index == len(lines) - 1 and not line.endswith(b'\n'):
                with path.open('r+b') as stream:
                    stream.truncate(offset)
                    stream.flush()
                    os.fsync(stream.fileno())
                break
            raise ValueError(f'Invalid stored feedback event on line {index + 1}') from exc
        events.append(event)
        offset += len(line)
    # A complete last event without its newline can also result from an interrupted append.
    if events and data and not data.endswith(b'\n') and offset == len(data):
        with path.open('ab') as stream:
            stream.write(b'\n')
            stream.flush()
            os.fsync(stream.fileno())
    return events


def log_summary_rows(events):
    rows = {}
    rating_order = {}
    for event in events:
        issue = event['issue']
        key = (event['session_id'], event['report_id'], issue['id'])
        if key not in rows:
            rows[key] = dict(
                session_id=event['session_id'], report_id=event['report_id'],
                submission=event['submission'], run=event['run'], issue_id=issue['id'],
                rule=issue['rule'], file=issue['file'], line=issue['line'], column=issue['column'],
                viewed='false', first_viewed_at='', last_viewed_at='', view_count=0,
                rated='false', rating='', rated_at='',
            )
        row = rows[key]
        timestamp = event['timestamp']
        if event['event_type'] == 'feedback_view':
            row['viewed'] = 'true'
            row['view_count'] += 1
            row['first_viewed_at'] = min(row['first_viewed_at'] or timestamp, timestamp)
            row['last_viewed_at'] = max(row['last_viewed_at'], timestamp)
        elif event['event_type'] == 'feedback_rating':
            order = (timestamp, event['server_received_at'], event['event_id'])
            if key not in rating_order or order > rating_order[key]:
                rating_order[key] = order
                row['rated'] = 'true'
                row['rating'] = event['rating']
                row['rated_at'] = timestamp
    return [rows[key] for key in sorted(rows)]


def log_write_summary(path, events):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode='w', encoding='utf-8', newline='', dir=path.parent,
                prefix='.feedback-summary-', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            writer = csv.DictWriter(stream, fieldnames=LOG_SUMMARY_FIELDS)
            writer.writeheader()
            writer.writerows(log_summary_rows(events))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def append_event(log_dir, payload):
    """Validate and save one event; exact retries are idempotent, even after restart.

    Creates feedback_events.jsonl and feedback_summary.csv below log_dir.
    ValueError indicates invalid input or reuse of an event ID for different data.
    OSError indicates a filesystem failure and should be treated as retryable.
    """
    event = log_validate_event(payload)
    directory = Path(log_dir)
    directory.mkdir(parents=True, exist_ok=True)
    with LOG_LOCK:
        event_path = directory / 'feedback_events.jsonl'
        events = log_read_events(event_path)
        duplicate = False
        identity = (event['session_id'], event['report_id'], event['issue']['id'])
        for stored in events:
            stored_payload = {key: value for key, value in stored.items() if key != 'server_received_at'}
            if stored['event_id'] == event['event_id']:
                if stored_payload != event:
                    raise ValueError('event_id was already used for a different event')
                duplicate = True
            stored_identity = (stored['session_id'], stored['report_id'], stored['issue']['id'])
            if identity == stored_identity and any(
                    stored[field] != event[field] for field in ('submission', 'run', 'issue')):
                raise ValueError('Issue metadata changed within this session and report')
        if not duplicate:
            event['server_received_at'] = datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
            serialized = json.dumps(event, ensure_ascii=False, separators=(',', ':')) + '\n'
            with event_path.open('a', encoding='utf-8') as stream:
                stream.write(serialized)
                stream.flush()
                os.fsync(stream.fileno())
            events.append(event)
        # Also rebuild on a retry, repairing a summary write interrupted after the event append.
        log_write_summary(directory / 'feedback_summary.csv', events)
    return {'saved': True, 'event_id': event['event_id']}



def make_server(args):
    token = secrets.token_urlsafe(32)
    log_token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def send(self, status, data, content_type='application/json; charset=utf-8'):
            body = json.dumps(data, ensure_ascii=False).encode() if isinstance(data, dict) else data.encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(body)

        def trusted(self):
            port = self.server.server_port
            hosts = {'127.0.0.1:' + str(port), 'localhost:' + str(port)}
            return self.headers.get('Host') in hosts

        def do_GET(self):
            if not self.trusted():
                return self.send(403, {'error': 'Invalid local host'})
            try:
                if self.path == '/api/log-token':
                    return self.send(200, {'token': log_token})
                if self.path in ('/api/logs/events', '/api/logs/summary'):
                    name = 'feedback_events.jsonl' if self.path.endswith('events') else 'feedback_summary.csv'
                    log_file = args.logs / name
                    if log_file.is_file():
                        return self.send(200, log_file.read_text(encoding='utf-8'),
                                         'text/plain; charset=utf-8' if name.endswith('jsonl') else 'text/csv; charset=utf-8')
                    return self.send(404, {'error': 'No feedback interactions have been recorded yet.'})
                if self.path == '/api/templates':
                    raw = args.rules.read_bytes()
                    templates, _ = align_project_templates(json.loads(raw))
                    return self.send(200, {'templates': templates, 'revision': hashlib.sha256(raw).hexdigest()})
                if self.path in ('/', '/editor'):
                    raw = args.rules.read_bytes()
                    templates, _ = align_project_templates(json.loads(raw))
                    config = json.dumps({'token': token, 'templates': templates,
                                         'revision': hashlib.sha256(raw).hexdigest()}, ensure_ascii=True).replace('<', '\\u003c')
                    rows = ''.join('<section><label for="description-' + str(i) + '">' + escape(name) +
                                   '</label><textarea id="description-' + str(i) + '" data-rule="' + escape(name) +
                                   '">' + escape(rule['explanation']) + '</textarea></section>'
                                   for i, (name, rule) in enumerate(templates.items()))
                    page = (HERE / 'rule_description_editor.html').read_text(encoding='utf-8')
                    page = page.replace('__RULE_ROWS__', rows).replace('__EDITOR_CONFIG__', config)
                    return self.send(200, page, 'text/html; charset=utf-8')
                # Only expose generated HTML pages, not arbitrary local files.
                if re.fullmatch(r'/generated/[A-Za-z0-9._-]+\.html', self.path):
                    page = args.output / self.path.rsplit('/', 1)[1]
                    if page.is_file():
                        return self.send(200, page.read_text(encoding='utf-8'), 'text/html; charset=utf-8')
                self.send(404, {'error': 'Not found'})
            except (OSError, ValueError) as error:
                self.send(500, {'error': str(error)})

        def do_POST(self):
            origin = self.headers.get('Origin')
            if (not self.trusted() or
                    (origin is not None and origin != 'http://' + self.headers.get('Host', ''))):
                return self.send(403, {'error': 'Please open the page from its local address.'})
            if self.path == '/api/log-events':
                if self.headers.get('X-Log-Token') != log_token:
                    return self.send(403, {'error': 'Refresh the log connection.'})
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if size <= 0 or size > 32_000:
                        return self.send(400, {'error': 'Invalid log event size'})
                    payload = json.loads(self.rfile.read(size))
                    return self.send(200, append_event(args.logs, payload))
                except OSError as error:
                    return self.send(500, {'error': str(error)})
                except (ValueError, KeyError, TypeError) as error:
                    return self.send(400, {'error': str(error)})
            if self.headers.get('X-Editor-Token') != token:
                return self.send(403, {'error': 'Please open the editor from its local address.'})
            if self.path != '/api/templates':
                return self.send(404, {'error': 'Not found'})
            saved_revision = None
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size <= 0 or size > 2_000_000:
                    return self.send(400, {'error': 'Invalid request size'})
                payload = json.loads(self.rfile.read(size))
                templates, _ = validate_templates(payload['templates'])
                raw = args.rules.read_bytes()
                if payload.get('revision') != hashlib.sha256(raw).hexdigest():
                    return self.send(409, {'error': 'The file was changed elsewhere. Copy your unsaved edits, then reload this page.'})
                updated = (json.dumps(templates, ensure_ascii=False, indent=2) + '\n').encode()
                atomic_write(args.rules.with_suffix(args.rules.suffix + '.bak'), raw)
                atomic_write(args.rules, updated)
                saved_revision = hashlib.sha256(updated).hexdigest()
                generate(args)
                self.send(200, {'revision': saved_revision, 'message': 'Saved. Feedback pages updated.'})
            except (OSError, ValueError, KeyError, TypeError) as error:
                if saved_revision:
                    self.send(200, {'revision': saved_revision, 'message': 'Templates saved, but feedback generation failed: ' + str(error)})
                else:
                    self.send(400, {'error': str(error)})

    return HTTPServer(('127.0.0.1', args.port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=HERE.parent)
    parser.add_argument('--rules', type=Path, default=HERE / 'rule_templates.json')
    parser.add_argument('--mockup', type=Path, default=HERE / 'feedback_mockup.html')
    parser.add_argument('--output', type=Path, default=HERE / 'generated')
    parser.add_argument('--logs', type=Path, default=HERE / 'logs')
    parser.add_argument('--serve', action='store_true', help='Start the local template editor')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    try:
        generate(args)
        if args.serve:
            with make_server(args) as server:
                print('Rule description editor: http://127.0.0.1:{}/'.format(server.server_port), flush=True)
                server.serve_forever()
    except KeyboardInterrupt:
        pass
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
