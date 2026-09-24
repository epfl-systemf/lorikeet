#!/usr/bin/env python3
"""Generate offline feedback timelines from Lorikeet histories, lints, and diffs.

Python 3.9+, standard library only. Runtime data defaults to grading/output.
"""
import argparse
import csv
import hashlib
import hmac
import html
import io
import json
import re
import secrets
import os
import shutil
import tempfile
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
    # Check.scala historically trimmed trailing blank context lines from diffs.
    while not text.endswith("\n") and old_left == new_left and old_left > 0:
        original[old] = ""
        old += 1
        new += 1
        old_left -= 1
        new_left -= 1
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
        what_to_improve_title='What to improve', explanation='', message='{{rule_message}}',
        suggested_rewrite_title='Suggested rewrite', rewrite_help='',
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


def render_document(mockup, title, main, interactive=True):
    result, count = re.subn(r'<main\b[^>]*>.*?</main>', lambda _: main, mockup, count=1, flags=re.S)
    if count != 1:
        raise ValueError('The mockup must contain a main element')
    result = re.sub(r'<title>.*?</title>', lambda _: '<title>' + escape(title) + '</title>', result, count=1, flags=re.S)
    if not interactive:
        return re.sub(r'<script\b[^>]*>.*?</script>', '', result, flags=re.S)
    return result


TRACKING_SCRIPT = r"""
(() => {
  const report = JSON.parse(document.getElementById('feedback-log-data').textContent);
  const session = crypto.randomUUID();
  const prefix = 'lorikeet.feedback.' + report.report_id + '.';
  const ledger = new Map();
  const online = location.protocol === 'http:' || location.protocol === 'https:';
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
    try{return await fetch(url,{...options,signal:controller.signal,cache:'no-store'});}
    finally{clearTimeout(timeout);}
  }
  async function flush(){
    if(!online || sending)return;
    sending=true;
    try{
      if(!report.event_endpoint&&!token){const response=await request('/api/log-token');if(!response.ok)throw Error('Log connection failed');token=(await response.json()).token;}
      for(const record of ledger.values()){
        if(record.synced)continue;
        const headers={'Content-Type':'application/json'};
        if(token)headers['X-Log-Token']=token;
        const response=await request(report.event_endpoint||'/api/log-events',{method:'POST',headers,body:JSON.stringify(record.event),keepalive:true});
        if(!response.ok){if(!report.event_endpoint&&response.status===403)token=null;throw Error('Log save failed');}
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
  document.addEventListener('timelinechange', event=>record('feedback_view',event.detail.id));
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-rating]');if(!button)return;
    const id=button.closest('[data-feedback-id]')?.dataset.feedbackId;if(!id)return;
    button.parentElement.querySelectorAll('[data-rating]').forEach(peer=>peer.classList.toggle('selected',peer===button));
    record('feedback_rating',id,button.dataset.rating);
  });
  Object.keys(report.issues).forEach(id=>record('issue_loaded',id));
  window.addEventListener('online',flush);
  window.addEventListener('pagehide',flush);
  setInterval(flush,5000);
})();
"""


def fallback_history(path, lines, blocks, issues):
    source = []
    previous = None
    for number, code in sorted(lines.items()):
        if previous is not None and number > previous + 1:
            source.append('// … lines omitted …')
        source.append(code)
        previous = number
    initial = '\n'.join(source)
    code = initial
    steps = []
    for block in blocks:
        before = '\n'.join(block['before'])
        after = '\n'.join(block['after'])
        start = code.find(before) if before else len(code)
        if start < 0:
            continue
        matching = [issue for issue in issues
                    if block['start'] <= issue['line'] < block['start'] + max(1, len(block['before']))]
        issue = matching[0] if matching else {'name': 'Rewrite', 'message': ''}
        code = code[:start] + after + code[start + len(before):]
        steps.append(dict(rule=issue['name'], description=issue['message'], start=start,
                          end=start + len(before), line=block['start'], column=1,
                          before=before, after=after, code=code))
    return dict(schemaVersion=1, file=path, limit=len(steps), truncated=False,
                initial=initial, steps=steps)


def same_file(left, right):
    left, right = Path(left).parts, Path(right).parts
    shorter, longer = sorted((left, right), key=len)
    return longer[-len(shorter):] == shorter


def locate_issue(code, issue):
    """Locate a reported source line in the final version, closest to its old line."""
    needle = issue['code'].strip()
    offset = 0
    matches = []
    for number, line in enumerate(code.splitlines(keepends=True), 1):
        content = line.rstrip('\r\n')
        if content.strip() == needle:
            start = offset + len(content) - len(content.lstrip())
            matches.append((abs(number - issue['line']), start, offset + len(content)))
        offset += len(line)
    return min(matches)[1:] if matches else None


def add_scalafmt_step(history, original):
    formatted = history['initial']
    if original == formatted:
        return
    start = 0
    while start < min(len(original), len(formatted)) and original[start] == formatted[start]:
        start += 1
    suffix = 0
    while (suffix < len(original) - start and suffix < len(formatted) - start
           and original[-suffix - 1] == formatted[-suffix - 1]):
        suffix += 1
    original_end = len(original) - suffix
    formatted_end = len(formatted) - suffix
    line_start = original.rfind('\n', 0, start) + 1
    history['initial'] = original
    history['steps'].insert(0, dict(
        rule='Scalafmt', description='Format the submitted Scala source',
        start=start, end=original_end, line=original.count('\n', 0, start) + 1,
        column=start - line_start + 1, before=original[start:original_end],
        after=formatted[start:formatted_end], code=formatted))


def build_report(report, sample_dir, lookup, mockup, include_scalafmt=False,
                 public_id=None):
    issues = parse_lint(report.read_text(encoding='utf-8'))
    run = report.parent.name.removeprefix('grading_reports_')
    submission = report.name.removesuffix('.lint.txt')
    root = report.parent.parent
    histories = []
    history_dir = root / ('grading_histories_' + run)
    for path in sorted(history_dir.glob(submission + '-*.history.json')):
        history = json.loads(path.read_text(encoding='utf-8'))
        if (history.get('schemaVersion') != 1 or not isinstance(history.get('initial'), str)
                or not isinstance(history.get('steps'), list)):
            raise ValueError('Invalid rewrite history: ' + str(path))
        if include_scalafmt:
            original_path = (root / ('grading_originals_' + run) /
                             (submission + '-' + Path(history['file']).name))
            if not original_path.is_file():
                raise ValueError('Missing original source for Scalafmt step: ' + str(original_path))
            add_scalafmt_step(history, original_path.read_text(encoding='utf-8'))
        histories.append(history)

    paths = sorted({issue['path'] for issue in issues})
    for history in histories:
        history['file'] = next(
            (path for path in paths if same_file(path, history['file'])), history['file']
        )
    diff_dir = root / ('grading_diffs_' + run)
    for path in paths:
        if any(same_file(path, history['file']) for history in histories):
            continue
        basename = Path(path).name
        diff = diff_dir / (submission + '-' + basename + '.diff')
        if diff.is_file() and sum(Path(p).name == basename for p in paths) == 1:
            lines, blocks = parse_diff(diff.read_text(encoding='utf-8'))
        else:
            lines, blocks = {}, []
        file_issues = [issue for issue in issues if issue['path'] == path]
        for issue in file_issues:
            lines.setdefault(issue['line'], issue['code'])
        histories.append(fallback_history(path, lines, blocks, file_issues))

    feedback_items = {}
    step_number = 0
    for history in histories:
        for step in history['steps']:
            step['kind'] = 'rewrite'
            step['id'] = 'rewrite-' + str(step_number)
            step_number += 1
            template = resolve_rule({'name': step['rule'], 'message': step.get('description', '')}, lookup)
            values = dict(file=history['file'], line=step['line'], column=step['column'],
                          rule_name=step['rule'], rule_message=step.get('description', ''),
                          rewrite=step['after'], original_code=step['before'])
            rendered = fill_template(template, values)
            step.update(title=rendered['title'], explanation=rendered['explanation'],
                        message=rendered['message'], location=rendered['location'])
            feedback_items[step['id']] = dict(rule=step['rule'], file=history['file'],
                                               line=step['line'], column=step['column'])

    seen = set()
    observation_number = 0
    for issue in issues:
        history = next((item for item in histories if same_file(issue['path'], item['file'])), None)
        if history is None:
            continue
        key = (issue['name'], history['file'])
        if key in seen or any(step['rule'] == issue['name'] for step in history['steps']):
            continue
        seen.add(key)
        final_code = history['steps'][-1]['code'] if history['steps'] else history['initial']
        location = locate_issue(final_code, issue)
        if location is None:
            continue
        start, end = location
        issue['id'] = 'observation-' + str(observation_number)
        observation_number += 1
        template = fill_template(resolve_rule(issue, lookup), dict(
            file=issue['path'], line=issue['line'], column=issue['column'],
            rule_name=issue['name'], rule_message=issue['message'], rewrite='',
            original_code=issue['code']))
        history['steps'].append(dict(
            kind='observation', id=issue['id'], rule=issue['name'],
            description=issue['message'], title=template['title'],
            explanation=template['explanation'], message=template['message'],
            location=template['location'], start=start, end=end,
            line=issue['line'], column=issue['column'], before=issue['code'].strip(),
            after=issue['code'].strip(), code=final_code))
        feedback_items[issue['id']] = dict(rule=issue['name'], file=issue['path'],
                                            line=issue['line'], column=issue['column'])

    histories = [history for history in histories if history['steps']]

    timelines = ''.join(
        '<section class="timeline" data-history-index="' + str(index) + '">'
        '<div class="timeline-heading"><div><span class="eyebrow">Feedback timeline</span><h2>' +
        escape(Path(history['file']).name) + '</h2><p class="location">' + escape(history['file']) +
        '</p></div><span class="step-count" data-step-count></span></div>'
        '<div class="workspace"><div class="code-panel"><div class="code-toolbar">'
        '<span data-version-label>Original</span><span class="context-tools"><span data-context-label></span>'
        '<span>Scala · Prism</span></span></div>'
        '<div class="code-view"><table class="code-table" role="presentation"><tbody data-code></tbody>'
        '</table></div></div>'
        '<aside class="change-card" data-change-card></aside></div>'
        '<nav class="timeline-nav" aria-label="Feedback navigation">'
        '<button type="button" class="secondary" data-back>← Back</button>'
        '<div class="progress-wrap"><progress data-progress max="' + str(len(history['steps'])) +
        '" value="0"></progress><span data-progress-label></span></div>'
        '<button type="button" data-forward>Next feedback →</button></nav>'
        '<section class="history" data-history hidden><div class="history-title"><span class="eyebrow">History</span>'
        '<h3>Changes and observations</h3></div><ol data-history-list></ol></section>' +
        ('<p class="limit-warning">The rewrite limit of ' + str(history['limit']) +
         ' was reached; more patterns may still match.</p>' if history.get('truncated') else '') +
        '</section>' for index, history in enumerate(histories))

    empty = '' if histories else '<section class="empty-state"><h2>No feedback</h2><p>No configured pattern matched this submission.</p></section>'
    title = submission + ' · ' + run
    main = ('<main class="shell"><header class="page-header"><div><span class="eyebrow">Lorikeet feedback</span>'
            '<h1>See your code evolve</h1><p>Step through rewrites, then review observations on the final code.</p>'
            '</div><div class="run-label">' + escape(submission) + '<span>' + escape(run) +
            '</span></div></header>' + timelines + empty + '</main>')

    relative = report.relative_to(sample_dir).as_posix()
    filename = (public_id.rsplit('/', 1)[-1] + '.html' if public_id else
                re.sub(r'[^A-Za-z0-9._-]', '-', run + '-' + submission) + '-' +
                hashlib.sha256(relative.encode()).hexdigest()[:8] + '.html')
    metadata = dict(report_id=public_id or filename.removesuffix('.html'),
                    submission=submission, run=run,
                    issues={key: dict(id=hashlib.sha256(json.dumps([key, value]).encode()).hexdigest()[:20], **value)
                            for key, value in feedback_items.items()})
    if public_id:
        metadata['event_endpoint'] = '/api/events'
    timeline_data = json.dumps({'histories': histories}, ensure_ascii=True).replace('<', '\\u003c')
    log_data = json.dumps(metadata, ensure_ascii=True).replace('<', '\\u003c')
    prism = ''.join((HERE / 'vendor' / name).read_text(encoding='utf-8')
                    for name in ('prism-core.min.js', 'prism-clike.min.js',
                                 'prism-java.min.js', 'prism-scala.min.js'))
    scripts = ('<script>' + prism + '</script><script type="application/json" id="timeline-data">' +
               timeline_data + '</script><script type="application/json" id="feedback-log-data">' +
               log_data + '</script><script>' + TRACKING_SCRIPT + '</script>')
    page = render_document(mockup, title, main)
    counts = Counter(item['rule'] for item in feedback_items.values())
    return (filename, title, page.replace('</body>', scripts + '</body>'),
            len(feedback_items), counts)


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


def report_files(args):
    reports = sorted(args.data.glob('**/grading_reports_*/*.lint.txt'))
    if args.run:
        reports = [report for report in reports
                   if report.parent.name == 'grading_reports_' + args.run]
    if not reports:
        suffix = ' for run ' + args.run if args.run else ''
        raise ValueError('No grading reports found under {}{}'.format(args.data, suffix))
    return reports


def deployment_secret(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        secret = path.read_bytes()
    else:
        secret = secrets.token_bytes(32)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(secret)
    path.chmod(0o600)
    if len(secret) < 32:
        raise ValueError('Deployment secret must contain at least 32 bytes: ' + str(path))
    return secret


def publish(args, lookup, mockup, reports):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.publish_lab):
        raise ValueError('Publish lab must use 1-64 letters, numbers, underscores, or hyphens')
    if not args.run:
        raise ValueError('--publish-lab requires --run')

    secret = deployment_secret(args.deployment_root / 'private' / 'hmac.key')
    public_root = args.deployment_root / 'public'
    public_root.mkdir(parents=True, exist_ok=True)
    manifests = args.deployment_root / 'private' / 'links'
    manifests.mkdir(parents=True, exist_ok=True)
    rows = []

    with tempfile.TemporaryDirectory(prefix='.' + args.publish_lab + '-', dir=public_root) as temporary:
        lab_output = Path(temporary)
        for report in reports:
            submission = report.name.removesuffix('.lint.txt')
            token = hmac.new(secret, (args.publish_lab + '\0' + submission).encode(),
                             hashlib.sha256).hexdigest()
            public_id = args.publish_lab + '/' + token
            filename, _, page, _, _ = build_report(
                report, args.data, lookup, mockup, args.include_scalafmt, public_id)
            (lab_output / filename).write_text(page, encoding='utf-8')
            rows.append((submission, '/r/' + public_id))

        target = public_root / args.publish_lab
        previous = public_root / ('.' + args.publish_lab + '-previous')
        if previous.exists() and not target.exists():
            previous.rename(target)
        elif previous.exists():
            shutil.rmtree(previous)
        if target.exists():
            target.rename(previous)
        try:
            lab_output.rename(target)
        except BaseException:
            if previous.exists() and not target.exists():
                previous.rename(target)
            raise
        if previous.exists():
            shutil.rmtree(previous)

    manifest = manifests / (args.publish_lab + '.csv')
    content = io.StringIO(newline='')
    writer = csv.writer(content)
    writer.writerow(('submission', 'url'))
    writer.writerows(sorted(rows))
    atomic_write(manifest, content.getvalue().encode())
    print('Published {} reports for {}. Private links: {}'.format(
        len(rows), args.publish_lab, manifest))


def generate(args):
    templates, lookup = load_templates(args.rules)
    mockup = args.mockup.read_text(encoding='utf-8')
    reports = report_files(args)
    if args.publish_lab:
        return publish(args, lookup, mockup, reports)
    pages = [build_report(report, args.data, lookup, mockup, args.include_scalafmt)
             for report in reports]
    args.output.mkdir(parents=True, exist_ok=True)
    entries = []
    for report, (filename, title, page, count, counts) in zip(reports, pages):
        (args.output / filename).write_text(page, encoding='utf-8')
        entries.append(dict(filename=filename, submission=report.name.removesuffix('.lint.txt'),
                            run=report.parent.name.removeprefix('grading_reports_'),
                            counts=counts))
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
    grading_output = HERE.parent / 'grading' / 'output'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=grading_output)
    parser.add_argument('--rules', type=Path, default=HERE / 'rule_templates.json')
    parser.add_argument('--mockup', type=Path, default=HERE / 'feedback_mockup.html')
    parser.add_argument('--output', type=Path, default=grading_output / 'feedback')
    parser.add_argument('--logs', type=Path, default=grading_output / 'feedback_logs')
    parser.add_argument('--run', help='Only generate one grading run timestamp')
    parser.add_argument('--publish-lab', help='Publish one lab with opaque student URLs')
    parser.add_argument('--deployment-root', type=Path,
                        default=grading_output / 'deployment')
    parser.add_argument('--include-scalafmt', action='store_true',
                        help='Show formatting saved by Check.scala as the first timeline rewrite')
    parser.add_argument('--serve', action='store_true', help='Start the local template editor')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    if args.publish_lab and args.serve:
        parser.error('--publish-lab cannot be combined with the local review server')
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
