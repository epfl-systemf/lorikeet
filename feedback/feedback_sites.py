#!/usr/bin/env python3
"""Generate and locally host personalized Lorikeet feedback sites.

This module deliberately uses only Python's standard library so instructors can
run it on the same machine as the grading script without another build step.
"""

from __future__ import annotations

import argparse
import csv
import fcntl
import hashlib
import hmac
import html
import json
import mimetypes
import re
import secrets
import shutil
import sqlite3
import statistics
import sys
import tempfile
import threading
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from functools import wraps
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlparse


SCHEMA_VERSION = 2
CONTENT_SECURITY_POLICY = (
    "default-src 'self'; script-src 'self'; style-src 'self'; "
    "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
    "base-uri 'none'; form-action 'self'"
)
REPORT_RE = re.compile(r"^(?P<student>.+)-(?P<attempt>\d+)\.lint\.txt$")
DIFF_RE = re.compile(r"^(?P<student>.+)-(?P<attempt>\d+)-(?P<file>.+)\.diff$")
LOCATION_RE = re.compile(r"^(?P<path>.+):(?P<line>\d+):(?P<column>\d+)$")
HUNK_RE = re.compile(
    r"^@@ -(?P<old_start>\d+)(?:,(?P<old_count>\d+))? "
    r"\+(?P<new_start>\d+)(?:,(?P<new_count>\d+))? @@(?P<label>.*)$"
)

EVENT_NAMES = {
    "page_open",
    "section_view",
    "item_view",
    "item_dwell",
    "rewrite_view",
    "rewrite_toggle",
    "session_summary",
}
PROPERTY_ALLOWLIST: dict[str, set[str]] = {
    "page_open": {"feedback_count", "rewrite_count"},
    "section_view": {"section"},
    "item_view": {"kind"},
    "item_dwell": {"kind", "visible_seconds"},
    "rewrite_view": {"mode"},
    "rewrite_toggle": {"version"},
    "session_summary": {
        "active_seconds",
        "max_scroll_percent",
    },
}


@dataclass
class Issue:
    id: str
    rule: str
    message: str
    path: str
    line: int
    column: int
    code: str
    pointer: str = ""


@dataclass
class DiffLine:
    kind: str
    content: str
    old_line: int | None
    new_line: int | None


@dataclass
class DiffHunk:
    id: str
    label: str
    old_start: int
    new_start: int
    lines: list[DiffLine] = field(default_factory=list)


@dataclass
class Rewrite:
    id: str
    file: str
    old_path: str
    new_path: str
    hunks: list[DiffHunk]
    feedback: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class Submission:
    student_id: str
    attempt: int
    display_name: str = ""
    status: str = "checked"
    issues: list[Issue] = field(default_factory=list)
    rewrites: list[Rewrite] = field(default_factory=list)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def stable_id(*parts: str, length: int = 16) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:length]


def review_key(course_title: str, assignment_title: str, review_id: str) -> str:
    return stable_id(course_title, assignment_title, review_id, length=20)


def serialized_deployment(operation):
    """Prevent concurrent publishers from replacing the registry out of order."""

    @wraps(operation)
    def wrapped(args: argparse.Namespace) -> int:
        private_dir = Path(args.deployment).resolve() / ".private"
        private_dir.mkdir(parents=True, exist_ok=True)
        with (private_dir / "publish.lock").open("a+", encoding="utf-8") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            try:
                return operation(args)
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)

    return wrapped


def parse_lint_report(path: Path) -> list[Issue]:
    """Parse the human-readable report produced by grading/scripts/Check.scala."""
    lines = path.read_text(encoding="utf-8").splitlines()
    issues: list[Issue] = []
    rule = "Feedback"
    message = "Review this part of your solution."
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("[") and line.endswith("]"):
            rule = line[1:-1].strip() or "Feedback"
            if index + 1 < len(lines):
                raw_message = lines[index + 1].strip()
                message = re.sub(r"\s+\(\d+ occurrences?\)\s*$", "", raw_message)
                index += 2
                continue
        match = LOCATION_RE.match(line.strip())
        if match:
            code = lines[index + 1] if index + 1 < len(lines) else ""
            pointer = ""
            if index + 2 < len(lines) and "^" in lines[index + 2]:
                pointer = lines[index + 2]
            issue_id = stable_id(
                rule,
                match.group("path"),
                match.group("line"),
                match.group("column"),
                code,
            )
            issues.append(
                Issue(
                    id=f"issue-{issue_id}",
                    rule=rule,
                    message=message,
                    path=match.group("path"),
                    line=int(match.group("line")),
                    column=int(match.group("column")),
                    code=code,
                    pointer=pointer,
                )
            )
            index += 3 if pointer else 2
            continue
        index += 1
    return issues


def clean_diff_path(value: str) -> str:
    value = value.split("\t", 1)[0].strip()
    if value in {"/dev/null", ""}:
        return value
    # Check.scala currently emits absolute snapshot paths. Only expose filenames.
    return Path(value).name


def parse_unified_diff(path: Path, display_file: str | None = None) -> Rewrite:
    lines = path.read_text(encoding="utf-8").splitlines()
    old_path = "before"
    new_path = "suggestion"
    hunks: list[DiffHunk] = []
    current: DiffHunk | None = None
    old_line = 0
    new_line = 0

    for raw in lines:
        if raw.startswith("--- "):
            old_path = clean_diff_path(raw[4:])
            continue
        if raw.startswith("+++ "):
            new_path = clean_diff_path(raw[4:])
            continue
        hunk_match = HUNK_RE.match(raw)
        if hunk_match:
            old_line = int(hunk_match.group("old_start"))
            new_line = int(hunk_match.group("new_start"))
            current = DiffHunk(
                id=f"hunk-{stable_id(path.name, str(len(hunks)), raw)}",
                label=hunk_match.group("label").strip(),
                old_start=old_line,
                new_start=new_line,
            )
            hunks.append(current)
            continue
        if current is None or raw.startswith("\\ No newline at end of file"):
            continue
        prefix = raw[:1]
        content = raw[1:] if prefix in {" ", "+", "-"} else raw
        if prefix == "-":
            current.lines.append(DiffLine("remove", content, old_line, None))
            old_line += 1
        elif prefix == "+":
            current.lines.append(DiffLine("add", content, None, new_line))
            new_line += 1
        else:
            current.lines.append(DiffLine("context", content, old_line, new_line))
            old_line += 1
            new_line += 1

    file_name = display_file or new_path or old_path or path.stem
    return Rewrite(
        id=f"rewrite-{stable_id(path.name, file_name)}",
        file=file_name,
        old_path=old_path,
        new_path=new_path,
        hunks=hunks,
    )


def read_roster(path: Path | None) -> dict[tuple[str, int], tuple[str, str]]:
    if path is None:
        return {}
    roster: dict[tuple[str, int], tuple[str, str]] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"student_id", "attempt"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("roster CSV must contain student_id and attempt columns")
        for row in reader:
            student_id = (row.get("student_id") or "").strip()
            attempt = int((row.get("attempt") or "0").strip())
            display_name = (row.get("display_name") or "").strip()
            status = (row.get("status") or "checked").strip().lower()
            roster[(student_id, attempt)] = (display_name, status)
    return roster


def collect_submissions(
    reports_dir: Path, diffs_dir: Path, roster_path: Path | None = None
) -> list[Submission]:
    roster = read_roster(roster_path)
    submissions: dict[tuple[str, int], Submission] = {
        key: Submission(key[0], key[1], values[0], values[1])
        for key, values in roster.items()
    }

    if reports_dir.exists():
        for report in sorted(reports_dir.glob("*.lint.txt")):
            match = REPORT_RE.match(report.name)
            if not match:
                print(f"warning: ignoring unrecognized report name {report.name}", file=sys.stderr)
                continue
            key = (match.group("student"), int(match.group("attempt")))
            submission = submissions.setdefault(key, Submission(*key))
            submission.issues.extend(parse_lint_report(report))

    if diffs_dir.exists():
        for diff in sorted(diffs_dir.glob("*.diff")):
            match = DIFF_RE.match(diff.name)
            if not match:
                print(f"warning: ignoring unrecognized diff name {diff.name}", file=sys.stderr)
                continue
            key = (match.group("student"), int(match.group("attempt")))
            submission = submissions.setdefault(key, Submission(*key))
            submission.rewrites.append(parse_unified_diff(diff, match.group("file")))

    for submission in submissions.values():
        warning_only: list[Issue] = []
        for issue in submission.issues:
            matched_rewrite: Rewrite | None = None
            issue_file = Path(issue.path).name
            for rewrite in submission.rewrites:
                if Path(rewrite.file).name != issue_file:
                    continue
                changed_lines = {
                    line.old_line
                    for hunk in rewrite.hunks
                    for line in hunk.lines
                    if line.kind == "remove" and line.old_line is not None
                }
                if issue.line in changed_lines:
                    matched_rewrite = rewrite
                    break
            if matched_rewrite is None:
                warning_only.append(issue)
            else:
                matched_rewrite.feedback.append(
                    {
                        "rule": issue.rule,
                        "message": issue.message,
                        "path": issue.path,
                        "line": issue.line,
                        "column": issue.column,
                    }
                )
        submission.issues = warning_only

    return sorted(submissions.values(), key=lambda item: (item.student_id, item.attempt))


def load_or_create_secret(path: Path) -> bytes:
    if path.exists():
        raw = path.read_text(encoding="utf-8").strip()
        if len(raw) < 32:
            raise ValueError(f"secret in {path} is too short")
        return bytes.fromhex(raw)
    path.parent.mkdir(parents=True, exist_ok=True)
    secret = secrets.token_bytes(32)
    path.write_text(secret.hex() + "\n", encoding="utf-8")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return secret


def derive_token(secret: bytes, student_id: str, attempt: int, purpose: str) -> str:
    digest = hmac.new(
        secret,
        f"{purpose}\x1f{student_id}\x1f{attempt}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return digest[:32]


def json_for_script(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")


def render_student_page(payload: dict[str, Any], asset_version: str) -> str:
    title = html.escape(payload["assignmentTitle"])
    course = html.escape(payload["courseTitle"])
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <meta name="referrer" content="no-referrer">
  <title>{title} feedback · Lorikeet</title>
  <link rel="stylesheet" href="assets/site.css?v={asset_version}">
</head>
<body>
  <a class="skip-link" href="#app">Skip to feedback</a>
  <main id="top">
    <section class="hero" aria-labelledby="page-title">
      <p class="context">{course} · Attempt {payload['attempt']}</p>
      <h1 id="page-title">{title} feedback</h1>
      <p>Review the flagged patterns and suggested changes below.</p>
    </section>
    <div id="app" aria-live="polite"></div>
  </main>
  <footer>
    <p>Feedback generated by Lorikeet</p>
    <button class="text-button" id="privacy-button" type="button">Data collection</button>
  </footer>
  <dialog id="privacy-dialog" aria-labelledby="privacy-title">
    <form method="dialog">
      <button class="dialog-close" aria-label="Close">×</button>
      <h2 id="privacy-title">Data collection</h2>
      <div id="privacy-copy"></div>
      <button class="button secondary" value="close">Close</button>
    </form>
  </dialog>
  <script id="feedback-data" type="application/json">{json_for_script(payload)}</script>
  <script src="assets/site.js?v={asset_version}" defer></script>
</body>
</html>
"""


def render_landing_page(course_title: str, asset_version: str) -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="referrer" content="no-referrer">
  <title>Feedback · Lorikeet</title>
  <link rel="stylesheet" href="/assets/site.css?v={asset_version}">
</head>
<body>
  <main class="missing-page">
    <p class="context">{html.escape(course_title)}</p>
    <h1>Open your personal feedback link</h1>
    <p>Use the unique link shared by your course staff.</p>
  </main>
</body>
</html>
"""


def copy_assets(output: Path) -> str:
    asset_source = Path(__file__).with_name("assets")
    asset_target = output / "assets"
    asset_target.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    for name in ("site.css", "site.js"):
        content = (asset_source / name).read_bytes()
        digest.update(content)
        (asset_target / name).write_bytes(content)
    return digest.hexdigest()[:12]


def generate_sites(args: argparse.Namespace) -> int:
    reports = Path(args.reports).resolve()
    diffs = Path(args.diffs).resolve()
    output = Path(args.output).resolve()
    private_dir = output / ".private"
    private_dir.mkdir(parents=True, exist_ok=True)
    secret_path = (
        Path(args.secret_file).resolve()
        if args.secret_file
        else private_dir / "secret.key"
    )
    secret = load_or_create_secret(secret_path)
    submissions = collect_submissions(
        reports, diffs, Path(args.roster).resolve() if args.roster else None
    )
    if not submissions:
        raise ValueError("no submissions found; check --reports, --diffs, or provide --roster")

    asset_version = copy_assets(output)
    (output / "index.html").write_text(
        render_landing_page(args.course_title, asset_version), encoding="utf-8"
    )
    manifest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": utc_now(),
        "telemetry_mode": args.telemetry,
        "course_title": args.course_title,
        "assignment_title": args.assignment_title,
        "review_id": args.review_id,
        "review_key": review_key(
            args.course_title, args.assignment_title, args.review_id
        ),
        "sites": {},
    }
    links: list[dict[str, Any]] = []
    base_url = args.base_url.rstrip("/")
    token_scope = f"{args.course_title}\x1f{args.assignment_title}\x1f{args.review_id}"

    for submission in submissions:
        token = derive_token(
            secret, submission.student_id, submission.attempt, f"url-token:{token_scope}"
        )
        site_id = derive_token(
            secret, submission.student_id, submission.attempt, f"site-id:{token_scope}"
        )[:20]
        payload = {
            "schemaVersion": SCHEMA_VERSION,
            "siteId": site_id,
            "token": token,
            "courseTitle": args.course_title,
            "assignmentTitle": args.assignment_title,
            "reviewId": args.review_id,
            "attempt": submission.attempt,
            "status": submission.status,
            "telemetryMode": args.telemetry,
            "issues": [asdict(issue) for issue in submission.issues],
            "rewrites": [asdict(rewrite) for rewrite in submission.rewrites],
        }
        site_dir = output / "f" / token
        site_dir.mkdir(parents=True, exist_ok=True)
        (site_dir / "index.html").write_text(
            render_student_page(payload, asset_version), encoding="utf-8"
        )
        manifest["sites"][token] = {"site_id": site_id}
        links.append(
            {
                "student_id": submission.student_id,
                "attempt": submission.attempt,
                "display_name": submission.display_name,
                "site_id": site_id,
                "review_id": args.review_id,
                "url": f"{base_url}/f/{token}/",
                "warnings": len(submission.issues),
                "rewrites": len(submission.rewrites),
            }
        )

    (private_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    with (private_dir / "links.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(links[0].keys()))
        writer.writeheader()
        writer.writerows(links)
    print(f"Generated {len(links)} personalized site(s) in {output}")
    print(f"Private student links: {private_dir / 'links.csv'}")
    return 0


def rebuild_registry(deployment: Path) -> dict[str, Any]:
    """Atomically rebuild the public-token registry from immutable reviews."""
    deployment = deployment.resolve()
    private_dir = deployment / ".private"
    reviews_dir = deployment / "reviews"
    private_dir.mkdir(parents=True, exist_ok=True)
    reviews_dir.mkdir(parents=True, exist_ok=True)

    registry: dict[str, Any] = {
        "schema_version": 1,
        "updated_at": utc_now(),
        "reviews": {},
        "sites": {},
    }
    for manifest_path in sorted(reviews_dir.glob("*/.private/manifest.json")):
        if manifest_path.parents[1].name.startswith("."):
            continue
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        key = manifest.get("review_key")
        if not isinstance(key, str) or not re.fullmatch(r"[a-f0-9]{20}", key):
            raise ValueError(f"invalid review key in {manifest_path}")
        review_root = manifest_path.parents[1]
        expected_root = reviews_dir / key
        if review_root != expected_root:
            raise ValueError(f"review directory does not match its key: {review_root}")

        review_metadata = {
            "review_key": key,
            "root": review_root.relative_to(deployment).as_posix(),
            "generated_at": manifest.get("generated_at", ""),
            "telemetry_mode": manifest.get("telemetry_mode", "research"),
            "course_title": manifest.get("course_title", ""),
            "assignment_title": manifest.get("assignment_title", ""),
            "review_id": manifest.get("review_id", ""),
            "sites_generated": len(manifest.get("sites", {})),
        }
        registry["reviews"][key] = review_metadata
        for token, site in manifest.get("sites", {}).items():
            if token in registry["sites"]:
                raise ValueError(f"duplicate site token in review {key}")
            registry["sites"][token] = {
                "site_id": site["site_id"],
                "review_key": key,
                "root": review_metadata["root"],
                "telemetry_mode": review_metadata["telemetry_mode"],
            }

    temporary = private_dir / f"registry.{secrets.token_hex(6)}.tmp"
    temporary.write_text(json.dumps(registry, indent=2) + "\n", encoding="utf-8")
    temporary.replace(private_dir / "registry.json")

    asset_version = copy_assets(deployment)
    course_titles = {
        item["course_title"] for item in registry["reviews"].values()
    }
    landing_title = course_titles.pop() if len(course_titles) == 1 else "Course feedback"
    (deployment / "index.html").write_text(
        render_landing_page(landing_title, asset_version), encoding="utf-8"
    )
    return registry


@serialized_deployment
def publish_review(args: argparse.Namespace) -> int:
    """Stage and atomically add one immutable review to a deployment."""
    deployment = Path(args.deployment).resolve()
    reviews_dir = deployment / "reviews"
    reviews_dir.mkdir(parents=True, exist_ok=True)
    key = review_key(args.course_title, args.assignment_title, args.review_id)
    final_dir = reviews_dir / key
    if final_dir.exists():
        raise ValueError(
            f"review {args.review_id!r} already exists; use a new review ID"
        )

    secret_path = deployment / ".private" / "secret.key"
    load_or_create_secret(secret_path)
    staging_dir = Path(
        tempfile.mkdtemp(prefix=f".{key}.", dir=reviews_dir)
    ).resolve()
    published = False
    try:
        generation_args = argparse.Namespace(
            reports=args.reports,
            diffs=args.diffs,
            output=str(staging_dir),
            roster=args.roster,
            course_title=args.course_title,
            assignment_title=args.assignment_title,
            review_id=args.review_id,
            base_url=args.base_url,
            secret_file=str(secret_path),
            telemetry=args.telemetry,
        )
        generate_sites(generation_args)
        staging_dir.rename(final_dir)
        published = True
        registry = rebuild_registry(deployment)
    except Exception:
        if not published and staging_dir.exists():
            shutil.rmtree(staging_dir)
        raise

    print(f"Published review {args.review_id!r} as {key}")
    print(f"Registered reviews: {len(registry['reviews'])}")
    print(f"Student links: {final_dir / '.private' / 'links.csv'}")
    return 0


@serialized_deployment
def reindex_deployment(args: argparse.Namespace) -> int:
    deployment = Path(args.deployment).resolve()
    registry = rebuild_registry(deployment)
    print(
        f"Registered {len(registry['reviews'])} review(s) and "
        f"{len(registry['sites'])} site(s)"
    )
    return 0


def connect_database(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          schema_version INTEGER NOT NULL,
          site_id TEXT NOT NULL,
          session_id TEXT NOT NULL,
          event_name TEXT NOT NULL,
          server_ts TEXT NOT NULL,
          client_ts TEXT,
          item_id TEXT,
          properties_json TEXT NOT NULL,
          collection_basis TEXT NOT NULL,
          review_key TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS events_site_event ON events(site_id, event_name);
        CREATE INDEX IF NOT EXISTS events_session ON events(session_id, server_ts);
        CREATE TABLE IF NOT EXISTS metadata (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS reviews (
          review_key TEXT PRIMARY KEY,
          course_title TEXT NOT NULL,
          assignment_title TEXT NOT NULL,
          review_id TEXT NOT NULL,
          generated_at TEXT NOT NULL,
          telemetry_mode TEXT NOT NULL,
          sites_generated INTEGER NOT NULL
        );
        """
    )
    event_columns = {
        column[1] for column in connection.execute("PRAGMA table_info(events)")
    }
    if "collection_basis" not in event_columns:
        connection.execute(
            "ALTER TABLE events ADD COLUMN collection_basis TEXT NOT NULL DEFAULT 'ethics_approved'"
        )
    if "review_key" not in event_columns:
        connection.execute(
            "ALTER TABLE events ADD COLUMN review_key TEXT NOT NULL DEFAULT ''"
        )
    return connection


def clean_properties(event_name: str, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    allowed = PROPERTY_ALLOWLIST[event_name]
    cleaned: dict[str, Any] = {}
    for key in allowed:
        item = value.get(key)
        if isinstance(item, str) and len(item) <= 80:
            cleaned[key] = item
        elif isinstance(item, (int, float, bool)):
            cleaned[key] = item
    return cleaned


class FeedbackServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], root: Path, database: Path):
        self.root = root.resolve()
        self.database_path = database
        self.db_lock = threading.Lock()
        self.registry_lock = threading.Lock()
        self.registry_mtime_ns = -1
        self.manifest: dict[str, Any] = {"sites": {}}
        self.reviews: dict[str, dict[str, Any]] = {}
        self.telemetry_mode = "off"
        self.refresh_registry(force=True)
        super().__init__(address, FeedbackHandler)

    def refresh_registry(self, force: bool = False) -> None:
        registry_path = self.root / ".private" / "registry.json"
        manifest_path = self.root / ".private" / "manifest.json"
        source_path = registry_path if registry_path.exists() else manifest_path
        if not source_path.exists():
            raise ValueError(f"{self.root} is not a feedback deployment")
        source_mtime = source_path.stat().st_mtime_ns
        if not force and source_mtime == self.registry_mtime_ns:
            return

        with self.registry_lock:
            source_mtime = source_path.stat().st_mtime_ns
            if not force and source_mtime == self.registry_mtime_ns:
                return
            source = json.loads(source_path.read_text(encoding="utf-8"))
            if source_path == registry_path:
                sites = source.get("sites", {})
                reviews = source.get("reviews", {})
            else:
                key = source.get("review_key") or review_key(
                    str(source.get("course_title", "")),
                    str(source.get("assignment_title", "")),
                    str(source.get("review_id", "review-1")),
                )
                review = {
                    "review_key": key,
                    "root": ".",
                    "generated_at": source.get("generated_at", ""),
                    "telemetry_mode": source.get("telemetry_mode", "research"),
                    "course_title": source.get("course_title", ""),
                    "assignment_title": source.get("assignment_title", ""),
                    "review_id": source.get("review_id", ""),
                    "sites_generated": len(source.get("sites", {})),
                }
                reviews = {key: review}
                sites = {
                    token: {
                        "site_id": site["site_id"],
                        "review_key": key,
                        "root": ".",
                        "telemetry_mode": review["telemetry_mode"],
                    }
                    for token, site in source.get("sites", {}).items()
                }

            modes = {site.get("telemetry_mode", "research") for site in sites.values()}
            self.manifest = {"sites": sites}
            self.reviews = reviews
            self.telemetry_mode = modes.pop() if len(modes) == 1 else "mixed"
            self.registry_mtime_ns = source_mtime
        self.sync_review_metadata()

    def sync_review_metadata(self) -> None:
        metadata = {
            "schema_version": str(SCHEMA_VERSION),
            "registry_updated_at": utc_now(),
            "telemetry_mode": self.telemetry_mode,
            "sites_generated": str(len(self.manifest["sites"])),
            "reviews_generated": str(len(self.reviews)),
        }
        with self.db_lock:
            db = connect_database(self.database_path)
            try:
                db.executemany(
                    "INSERT OR REPLACE INTO metadata(key, value) VALUES (?, ?)",
                    metadata.items(),
                )
                db.executemany(
                    """INSERT OR REPLACE INTO reviews
                    (review_key, course_title, assignment_title, review_id,
                     generated_at, telemetry_mode, sites_generated)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    [
                        (
                            key,
                            str(review.get("course_title", "")),
                            str(review.get("assignment_title", "")),
                            str(review.get("review_id", "")),
                            str(review.get("generated_at", "")),
                            str(review.get("telemetry_mode", "research")),
                            int(review.get("sites_generated", 0)),
                        )
                        for key, review in self.reviews.items()
                    ],
                )
                db.commit()
            finally:
                db.close()

    def site_info(self, token: str) -> dict[str, Any] | None:
        self.refresh_registry()
        site = self.manifest["sites"].get(token)
        return dict(site) if site else None

    def site_root(self, token: str) -> Path | None:
        site = self.site_info(token)
        if site is None:
            return None
        candidate = (self.root / str(site.get("root", "."))).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError:
            return None
        return candidate

    def accept_event(self, body: dict[str, Any]) -> tuple[bool, str]:
        token = body.get("token")
        session_id = body.get("session_id")
        event_name = body.get("event_name")
        site = self.site_info(token) if isinstance(token, str) else None
        if site is None:
            return False, "unknown site"
        if not isinstance(session_id, str) or not re.fullmatch(r"[A-Za-z0-9-]{16,64}", session_id):
            return False, "invalid session"
        if event_name not in EVENT_NAMES:
            return False, "unknown event"
        telemetry_mode = site.get("telemetry_mode", "research")
        if telemetry_mode == "off":
            return True, "disabled"
        if telemetry_mode == "access":
            return True, "ignored"

        site_id = site["site_id"]
        key = site.get("review_key", "")
        with self.db_lock:
            db = connect_database(self.database_path)
            try:
                item_id = body.get("item_id")
                if not isinstance(item_id, str) or len(item_id) > 100:
                    item_id = None
                client_ts = body.get("client_ts")
                if not isinstance(client_ts, str) or len(client_ts) > 40:
                    client_ts = None
                properties = clean_properties(event_name, body.get("properties"))
                db.execute(
                    """INSERT INTO events
                    (schema_version, site_id, session_id, event_name, server_ts,
                     client_ts, item_id, properties_json, collection_basis,
                     review_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        SCHEMA_VERSION,
                        site_id,
                        session_id,
                        event_name,
                        utc_now(),
                        client_ts,
                        item_id,
                        json.dumps(properties, separators=(",", ":")),
                        "ethics_approved",
                        key,
                    ),
                )
                db.commit()
            finally:
                db.close()
        return True, "recorded"

    def record_access(self, token: str) -> None:
        """Record access from the HTML request itself, independent of JavaScript."""
        site = self.site_info(token)
        if site is None or site.get("telemetry_mode", "research") == "off":
            return
        site_id = site["site_id"]
        with self.db_lock:
            db = connect_database(self.database_path)
            try:
                db.execute(
                    """INSERT INTO events
                    (schema_version, site_id, session_id, event_name, server_ts,
                     client_ts, item_id, properties_json, collection_basis,
                     review_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        SCHEMA_VERSION,
                        site_id,
                        f"access-{secrets.token_hex(12)}",
                        "site_access",
                        utc_now(),
                        None,
                        None,
                        "{}",
                        "ethics_approved",
                        site.get("review_key", ""),
                    ),
                )
                db.commit()
            finally:
                db.close()


class FeedbackHandler(BaseHTTPRequestHandler):
    server: FeedbackServer

    def log_message(self, format: str, *args: Any) -> None:
        # Avoid putting capability URLs or student network addresses in logs.
        status = args[1] if len(args) > 1 else ""
        print(f"feedback-server response={status}", file=sys.stderr)

    def end_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header(
            "Content-Security-Policy",
            CONTENT_SECURITY_POLICY,
        )
        super().end_headers()

    def send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def do_POST(self) -> None:
        if self.path != "/api/events":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        origin = self.headers.get("Origin")
        host = self.headers.get("Host")
        if origin and urlparse(origin).netloc != host:
            self.send_json(HTTPStatus.FORBIDDEN, {"error": "origin rejected"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > 16_384:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid request size"})
            return
        try:
            body = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid JSON"})
            return
        ok, outcome = self.server.accept_event(body)
        if not ok:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": outcome})
            return
        self.send_json(HTTPStatus.ACCEPTED, {"status": outcome})

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        request_path = parsed.path
        access_token: str | None = None
        target: Path | None = None
        if request_path == "/healthz":
            self.server.refresh_registry()
            self.send_json(
                HTTPStatus.OK,
                {
                    "status": "ok",
                    "telemetry": self.server.telemetry_mode,
                    "reviews": len(self.server.reviews),
                    "sites": len(self.server.manifest["sites"]),
                },
            )
            return
        if request_path == "/":
            target = self.server.root / "index.html"
        elif re.fullmatch(r"/assets/(site\.css|site\.js)", request_path):
            target = self.server.root / Path(request_path.lstrip("/"))
        else:
            page_match = re.fullmatch(r"/f/([a-f0-9]{32})/?", request_path)
            asset_match = re.fullmatch(
                r"/f/([a-f0-9]{32})/assets/(site\.css|site\.js)",
                request_path,
            )
            token = (
                page_match.group(1)
                if page_match
                else asset_match.group(1) if asset_match else None
            )
            site_root = self.server.site_root(token) if token else None
            if token is None or site_root is None:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            if page_match:
                target = site_root / "f" / token / "index.html"
                access_token = token
            else:
                target = site_root / "assets" / asset_match.group(2)
        if not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if access_token is not None:
            try:
                self.server.record_access(access_token)
            except sqlite3.Error as error:
                print(f"feedback-server telemetry_error={error}", file=sys.stderr)
        content = target.read_bytes()
        media_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", f"{media_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header(
            "Cache-Control",
            (
                "public, max-age=3600"
                if "/assets/" in request_path
                else "no-store"
            ),
        )
        self.end_headers()
        self.wfile.write(content)


def serve_sites(args: argparse.Namespace) -> int:
    root = Path(args.directory).resolve()
    manifest = root / ".private" / "manifest.json"
    registry = root / ".private" / "registry.json"
    if not manifest.exists() and not registry.exists():
        raise ValueError(f"{root} is not a generated feedback directory")
    database = (
        Path(args.database).resolve()
        if args.database
        else root / ".private" / "telemetry.sqlite3"
    )
    server = FeedbackServer((args.host, args.port), root, database)
    print(f"Serving feedback at http://{args.host}:{args.port}")
    print(f"Telemetry database: {database}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server")
    finally:
        server.server_close()
    return 0


def event_rows(database: Path) -> list[dict[str, Any]]:
    db = connect_database(database)
    try:
        columns = [column[1] for column in db.execute("PRAGMA table_info(events)")]
        rows = []
        for values in db.execute("SELECT * FROM events ORDER BY id"):
            row = dict(zip(columns, values))
            row["properties"] = json.loads(row.pop("properties_json"))
            rows.append(row)
        return rows
    finally:
        db.close()


def read_metadata(database: Path) -> dict[str, str]:
    db = connect_database(database)
    try:
        return dict(db.execute("SELECT key, value FROM metadata"))
    finally:
        db.close()


def read_reviews(database: Path) -> list[dict[str, Any]]:
    db = connect_database(database)
    try:
        columns = [column[1] for column in db.execute("PRAGMA table_info(reviews)")]
        return [
            dict(zip(columns, values))
            for values in db.execute("SELECT * FROM reviews ORDER BY generated_at")
        ]
    finally:
        db.close()


def engagement_metrics(rows: list[dict[str, Any]], sites_generated: int) -> dict[str, Any]:
    opened = {row["site_id"] for row in rows if row["event_name"] == "site_access"}
    browser_opened = {row["site_id"] for row in rows if row["event_name"] == "page_open"}
    sessions = {
        (row["site_id"], row["session_id"])
        for row in rows
        if row["event_name"] == "page_open"
    }
    active_seconds = [
        float(row["properties"].get("active_seconds", 0))
        for row in rows
        if row["event_name"] == "session_summary"
    ]
    dwell_seconds = [
        float(row["properties"].get("visible_seconds", 0))
        for row in rows
        if row["event_name"] == "item_dwell"
    ]
    return {
        "events": len(rows),
        "sites_generated": sites_generated or None,
        "sites_accessed": len(opened),
        "access_rate": round(len(opened) / sites_generated, 4)
        if sites_generated
        else None,
        "personalized_html_requests": sum(
            row["event_name"] == "site_access" for row in rows
        ),
        "browser_sites_opened": len(browser_opened),
        "browser_open_rate": round(len(browser_opened) / sites_generated, 4)
        if sites_generated
        else None,
        "sessions": len(sessions),
        "median_active_seconds": (
            round(statistics.median(active_seconds), 1) if active_seconds else None
        ),
        "items_viewed": sum(row["event_name"] == "item_view" for row in rows),
        "median_item_visible_seconds": (
            round(statistics.median(dwell_seconds), 1) if dwell_seconds else None
        ),
        "rewrites_explored": sum(
            row["event_name"] == "rewrite_view" for row in rows
        ),
        "rewrite_toggles": sum(
            row["event_name"] == "rewrite_toggle" for row in rows
        ),
    }


def summarize_events(
    rows: list[dict[str, Any]],
    metadata: dict[str, str] | None = None,
    reviews: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    metadata = metadata or {}
    sites_generated = int(metadata.get("sites_generated", "0") or 0)
    summary = {
        "generated_at": utc_now(),
        "study": metadata,
        **engagement_metrics(rows, sites_generated),
    }
    if reviews:
        summary["reviews"] = {
            review["review_key"]: {
                "study": review,
                **engagement_metrics(
                    [
                        row
                        for row in rows
                        if row.get("review_key") == review["review_key"]
                    ],
                    int(review["sites_generated"]),
                ),
            }
            for review in reviews
        }
    return summary


def export_telemetry(args: argparse.Namespace) -> int:
    database = Path(args.database).resolve()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    rows = event_rows(database)
    event_fields = [
        "id",
        "schema_version",
        "site_id",
        "session_id",
        "event_name",
        "server_ts",
        "client_ts",
        "item_id",
        "collection_basis",
        "review_key",
        "properties",
    ]
    with (output / "events.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=event_fields)
        writer.writeheader()
        for row in rows:
            row = dict(row)
            row["properties"] = json.dumps(row["properties"], separators=(",", ":"))
            writer.writerow({key: row.get(key) for key in event_fields})
    summary = summarize_events(rows, read_metadata(database), read_reviews(database))
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(rows)} event(s) to {output}")
    return 0


def print_summary(args: argparse.Namespace) -> int:
    database = Path(args.database).resolve()
    summary = summarize_events(
        event_rows(database), read_metadata(database), read_reviews(database)
    )
    print(json.dumps(summary, indent=2))
    return 0


def add_review_arguments(
    parser: argparse.ArgumentParser, *, review_id_required: bool
) -> None:
    parser.add_argument("--reports", required=True, help="directory containing *.lint.txt")
    parser.add_argument("--diffs", required=True, help="directory containing *.diff")
    parser.add_argument("--roster", help="optional CSV: student_id,attempt,display_name")
    parser.add_argument("--course-title", default="Intro to Software Construction")
    parser.add_argument("--assignment-title", default="Programming assignment")
    parser.add_argument(
        "--review-id",
        required=review_id_required,
        default=None if review_id_required else "review-1",
        help="immutable identifier distinguishing reviews of the same assignment",
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:4173")
    parser.add_argument(
        "--telemetry",
        choices=("off", "access", "research"),
        default="research",
        help="off, server-side access only, or automatic research interaction telemetry",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate = subparsers.add_parser("generate", help="generate personalized sites")
    add_review_arguments(generate, review_id_required=False)
    generate.add_argument("--output", required=True, help="generated site directory")
    generate.add_argument(
        "--secret-file", help="stable HMAC secret; defaults inside private output"
    )
    generate.set_defaults(func=generate_sites)

    publish = subparsers.add_parser(
        "publish", help="atomically add a review to a running deployment"
    )
    add_review_arguments(publish, review_id_required=True)
    publish.add_argument("--deployment", required=True)
    publish.set_defaults(func=publish_review)

    reindex = subparsers.add_parser(
        "reindex", help="rebuild a deployment registry from published reviews"
    )
    reindex.add_argument("--deployment", required=True)
    reindex.set_defaults(func=reindex_deployment)

    serve = subparsers.add_parser("serve", help="serve sites and collect telemetry")
    serve.add_argument("--directory", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=4173)
    serve.add_argument("--database", help="defaults to <directory>/.private/telemetry.sqlite3")
    serve.set_defaults(func=serve_sites)

    export = subparsers.add_parser("export", help="export pseudonymous telemetry")
    export.add_argument("--database", required=True)
    export.add_argument("--output", required=True)
    export.set_defaults(func=export_telemetry)

    summary = subparsers.add_parser("summary", help="print aggregate telemetry summary")
    summary.add_argument("--database", required=True)
    summary.set_defaults(func=print_summary)
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
