# Personalized feedback sites

`feedback_sites.py` turns the lint reports and unified diffs emitted by
`grading/scripts/Check.scala` into private-link student websites. It also serves
the sites locally and can store pseudonymous telemetry in SQLite.

The student experience has two deliberately distinct kinds of feedback:

1. **Warnings** explain patterns for which the tool has no automatic change.
2. **Rewrites** appear only in the rewrite section. Each has a Diff tab and an
   Interactive change tab whose switch alternates between original and suggested
   code. A lint location covered by changed diff lines is attached to the
   rewrite and removed from the warning list.

No Python packages are required; Python 3.10 or newer is sufficient.

## Inspecting and testing feedback before release

Treat a feedback review as course material: test the rules and the generated
student experience before running them over the whole cohort.

1. Build a deliberately varied sample of submissions. Include clean solutions,
   compile failures, unusually short and long solutions, different attempts,
   and examples of every implementation strategy observed while grading. Add
   submissions near the boundary of each proposed pattern; a random sample
   alone is unlikely to expose the most important false positives.
2. Run candidate patterns only on that sample. Inspect every lint location and
   every changed diff line. Check that the message is actionable, the matched
   source range is correct, and a rewrite preserves behavior and still compiles.
3. Look specifically for overlapping findings. A finding covered by a changed
   diff line should appear only as a rewrite; warning-only findings should remain
   in the warnings section.
4. Generate a preview with `--telemetry off`. Open at least one clean,
   compile-error, warning-only, rewrite-only, and mixed site. Test the Diff and
   Interactive change views at desktop and narrow viewport widths.
5. Revise the rule configuration and repeat until the sample is acceptable.
   Then run the frozen rules over the full cohort and spot-check both a random
   cohort sample and at least one submission for every rule that matched.
6. Assign a new immutable `--review-id` for the released review. Do not reuse a
   review ID after distributing its links; corrections should be a new review
   so both content and telemetry remain attributable.

The repository includes three non-identifying fixtures under
`student-lab-submissions/2024/find/submissions`: a clean submission, a compile
failure, and a submission containing one warning plus one rewrite. With the
sample `find` scaffold, run the end-to-end grading check from the repository
root:

```bash
scala-cli run grading/scripts/Check.scala --server=false
```

The command prints the exact timestamped report, diff, and results-roster paths
to pass to `generate`. Before release, the following checks should all pass:

```bash
python3 -m unittest discover -s feedback/tests -v
node --check feedback/assets/site.js
scala-cli compile grading/scripts/Check.scala --server=false
```

## Instructor workflow

Run the grading script as usual. It now writes a
`grading_results_<timestamp>.csv` roster in addition to the report and diff
directories. The roster is important because successful submissions have no
lint report or diff of their own.

Generate the sites:

```bash
python3 feedback/feedback_sites.py generate \
  --reports grading_reports_2026.08.05_14.55.17 \
  --diffs grading_diffs_2026.08.05_14.55.17 \
  --roster grading_results_2026.08.05_14.55.17.csv \
  --output generated_feedback \
  --course-title "Intro to Software Construction" \
  --assignment-title "Find" \
  --review-id "week-03-review-1" \
  --base-url http://feedback-machine.local:4173 \
  --telemetry research
```

For older grading runs, provide a CSV with these columns:

```csv
student_id,attempt,status,display_name
123456,0,issues,
234567,1,success,
```

`status` may be `success`, `issues`, `compile_error`, or `missing_files`.
`display_name` is optional and is currently retained only in the private links
file; student pages do not need to disclose an identity.

Start the local server:

```bash
python3 feedback/feedback_sites.py serve \
  --directory generated_feedback \
  --host 0.0.0.0 \
  --port 4173
```

Distribute the URLs from `generated_feedback/.private/links.csv`. The root page
does not list students, `.private` is never served, and access logs omit the URL
path so capability tokens do not leak into the console. Use HTTPS through a
course reverse proxy if students connect over anything other than a trusted
local network.

The secret in `.private/secret.key` makes URLs stable when regenerating the same
course, assignment, review ID, student, and attempt into the same directory.
Changing `--review-id` creates a distinct static site and pseudonymous site ID
for a later review of the same assignment. Back the secret up separately if
stable links matter. Treat the entire `.private` directory as confidential and
never publish it as static content.

## Telemetry modes

- `off`: disables collection for test or development deployments.
- `access`: records only personalized HTML requests on the server.
- `research` (default): records access and automatic interaction telemetry. The
  student interface provides a data-use disclosure but no telemetry control.

Access is recorded in `site_access` when the local server serves a personalized
HTML file. It therefore works even if browser JavaScript is blocked. Research
mode additionally records page and section exposure, the first time an item is
at least 50% visible, accumulated visible time for each item, active page time,
maximum scroll depth, rewrite-tab selection, and original/suggested toggles.
These signals are implicit except for the rewrite controls required by the
learning interface. Events exclude source code, free text, student identifiers,
IP addresses, user agents, and device fingerprints. The private links CSV is
the only identity crosswalk.

The summary reports both server-observed `sites_accessed` and JavaScript-backed
`browser_sites_opened`. The distinction is useful because an LMS, mail client,
or link scanner can request a URL without a student actually reading it. Treat
`site_access` as delivery/access evidence and corroborate it with `page_open`,
item exposure, and dwell events when analyzing engagement.

## Persistent deployment and incremental reviews

Use `generate` for a disposable preview of one review. Use `publish` for a
long-running course server. A deployment holds one shared secret, one telemetry
database, and any number of immutable assignment reviews:

```text
feedback-deployment/
├── .private/
│   ├── registry.json          # atomically replaced routing index
│   ├── secret.key             # stable pseudonymous URL key
│   └── telemetry.sqlite3      # events from every review
└── reviews/
    └── <review-key>/          # complete static output for one review
```

Publish a review directly from the host with:

```bash
python3 feedback/feedback_sites.py publish \
  --deployment feedback-deployment \
  --reports grading_reports_2026.08.05_14.55.17 \
  --diffs grading_diffs_2026.08.05_14.55.17 \
  --roster grading_results_2026.08.05_14.55.17.csv \
  --course-title "Intro to Software Construction" \
  --assignment-title "Find" \
  --review-id "find-2026-review-1" \
  --base-url https://feedback.example.edu \
  --telemetry research
```

Publication is serialized with a filesystem lock. Files are generated in a
staging directory, the finished review is renamed into place, and then
`registry.json` is atomically replaced. A running server checks that registry's
modification time on each page or event request. Consequently, a newly
published assignment becomes available without a server restart, while all old
student URLs continue to resolve to their original content.

The same course/assignment/review ID cannot be published twice. Use a new
review ID for corrections or a second feedback pass. This immutability keeps
content, distributed links, and telemetry attribution aligned. If a publication
is interrupted after its review directory is moved but before registration,
rebuild the registry with:

```bash
python3 feedback/feedback_sites.py reindex \
  --deployment feedback-deployment
```

The SQLite event table includes `review_key`, and summaries contain both global
metrics and a per-review breakdown. Back up the entire deployment directory;
in particular, losing `secret.key` prevents reproducing existing URLs, while
losing the private links files removes the student-identity crosswalk.

### Container deployment

Infrastructure files live in `feedback/infra`. They run the Python server as an
unprivileged host UID/GID, mount persistent data separately from the image,
drop Linux capabilities, use a read-only container filesystem, configure a
health check, and restart the service automatically.

Prepare a host directory and configuration:

```bash
cp feedback/infra/.env.example feedback/infra/.env
mkdir -p /srv/lorikeet-feedback
chown 1000:1000 /srv/lorikeet-feedback
```

Edit `.env` to set the absolute data path and deployment UID/GID. Publish the
first review before starting the server:

```bash
export REPORTS_DIR=$PWD/grading_reports_2026.08.05_14.55.17
export DIFFS_DIR=$PWD/grading_diffs_2026.08.05_14.55.17
export ROSTER_FILE=$PWD/grading_results_2026.08.05_14.55.17.csv
export COURSE_TITLE="Intro to Software Construction"
export ASSIGNMENT_TITLE="Find"
export REVIEW_ID="find-2026-review-1"
export FEEDBACK_BASE_URL="https://feedback.example.edu"
feedback/infra/publish-review.sh

feedback/infra/deploy.sh
```

For TLS termination with Caddy and a DNS name pointing at the server:

```bash
ENABLE_TLS=1 feedback/infra/deploy.sh
```

For every later assignment or review, set the new report paths, assignment
title, and unique review ID, then rerun `publish-review.sh`. The script mounts
grading artifacts read-only into a one-shot container and writes only to the
persistent deployment directory. The already-running `feedback` container does
not need rebuilding or restarting. This command is also the intended CI/CD
integration boundary: a grading job can transfer its three artifact paths to
the host and invoke the publisher after instructor approval.

Get a quick aggregate view:

```bash
python3 feedback/feedback_sites.py summary \
  --database generated_feedback/.private/telemetry.sqlite3
```

Export pseudonymous events and a JSON summary:

```bash
python3 feedback/feedback_sites.py export \
  --database generated_feedback/.private/telemetry.sqlite3 \
  --output feedback_export
```

The event schema is versioned. `server_ts` should be the canonical event time;
`client_ts` is diagnostic because student clocks can be wrong. For analysis,
define outcomes and exclusion rules before looking at treatment effects, and
join the identity crosswalk only in an access-controlled analysis environment.

## Research and course policy

The implementation creates useful data, but it does not itself make a study
ethical or publishable. Before collecting research-mode data:

- obtain and document the institution's ethics/IRB approval for automatic
  collection, including any approved consent waiver and student disclosure;
- keep telemetry separate from grading decisions, especially given the
  instructor–student power relationship;
- document purpose, legal basis, retention period, who can access the data,
  withdrawal procedure, and deletion procedure;
- collect a comparison or baseline only when approved and pedagogically fair;
- keep the crosswalk separate, use a short retention window, and report only
  aggregates that cannot identify students or unusually small groups.

Page access and interface engagement do not establish learning. A credible
paper should pair these measures with approved learning outcomes—for example a
later code task scored blind to telemetry exposure—and account for assignment,
prior experience, and repeated observations from the same student.

## Tests

```bash
python3 -m unittest discover -s feedback/tests -v
```
