import argparse
import csv
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import feedback_sites as sites


LINT = """[Var Usage]
Do not use vars - use vals instead (1 occurrences)

src/main/scala/find.scala:4:3
   var counter: Int = 0
   ^^^^^^^^^^^^^^^^^^^^

[If Simplification]
Prefer the Boolean expression (1 occurrences)

src/main/scala/find.scala:7:5
     if ready then false else !ready
     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
"""

DIFF = """--- /tmp/student.pre-find.scala
+++ /tmp/student.post-find.scala
@@ -4,4 +4,4 @@ object Find:
   val untouched = true

   def condition(ready: Boolean): Boolean =
-    if ready then false else !ready
+    !ready && !ready
"""


class ParserTests(unittest.TestCase):
    def test_lint_report_keeps_locations_and_code(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "student-2.lint.txt"
            path.write_text(LINT, encoding="utf-8")
            issues = sites.parse_lint_report(path)

        self.assertEqual([item.rule for item in issues], ["Var Usage", "If Simplification"])
        self.assertEqual(issues[0].line, 4)
        self.assertEqual(issues[0].message, "Do not use vars - use vals instead")
        self.assertIn("var counter", issues[0].code)

    def test_unified_diff_supports_diff_and_interactive_source_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "student-2-find.scala.diff"
            path.write_text(DIFF, encoding="utf-8")
            rewrite = sites.parse_unified_diff(path, "find.scala")

        self.assertEqual(rewrite.file, "find.scala")
        self.assertEqual(len(rewrite.hunks), 1)
        kinds = [line.kind for line in rewrite.hunks[0].lines]
        self.assertIn("remove", kinds)
        self.assertIn("add", kinds)
        self.assertNotIn("/tmp/", rewrite.old_path)


class GenerationTests(unittest.TestCase):
    def test_generation_uses_roster_for_successful_submissions_and_hides_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "reports"
            diffs = root / "diffs"
            output = root / "site"
            reports.mkdir()
            diffs.mkdir()
            (reports / "s-100-2.lint.txt").write_text(LINT, encoding="utf-8")
            (diffs / "s-100-2-find.scala.diff").write_text(DIFF, encoding="utf-8")
            roster = root / "roster.csv"
            roster.write_text(
                "student_id,attempt,status,display_name\n"
                "s-100,2,issues,A Student\n"
                "s-200,1,success,\n",
                encoding="utf-8",
            )
            args = argparse.Namespace(
                reports=str(reports),
                diffs=str(diffs),
                output=str(output),
                roster=str(roster),
                course_title="Construction",
                assignment_title="Find",
                review_id="review-2",
                base_url="http://localhost:4173",
                secret_file=None,
                telemetry="research",
            )
            self.assertEqual(sites.generate_sites(args), 0)
            manifest = json.loads((output / ".private" / "manifest.json").read_text())
            with (output / ".private" / "links.csv").open() as link_file:
                links = list(csv.DictReader(link_file))

            self.assertEqual(len(manifest["sites"]), 2)
            self.assertEqual(manifest["review_id"], "review-2")
            self.assertEqual(len(links), 2)
            self.assertNotIn("s-100", json.dumps(manifest))
            for token in manifest["sites"]:
                page = (output / "f" / token / "index.html").read_text()
                self.assertNotIn("s-100", page)
                self.assertNotIn("s-200", page)
            success_page = next(
                (output / "f" / token / "index.html").read_text()
                for token, metadata in manifest["sites"].items()
                if metadata["site_id"]
                == next(
                    row["site_id"] for row in links if row["student_id"] == "s-200"
                )
            )
            self.assertIn('"status":"success"', success_page)
            self.assertNotIn('class="topbar"', success_page)

            issue_page = next(
                (output / "f" / token / "index.html").read_text()
                for token, metadata in manifest["sites"].items()
                if metadata["site_id"]
                == next(
                    row["site_id"] for row in links if row["student_id"] == "s-100"
                )
            )
            marker = '<script id="feedback-data" type="application/json">'
            payload = json.loads(issue_page.split(marker, 1)[1].split("</script>", 1)[0])
            self.assertEqual([issue["rule"] for issue in payload["issues"]], ["Var Usage"])
            self.assertEqual(payload["rewrites"][0]["feedback"][0]["rule"], "If Simplification")
            self.assertNotIn("If Simplification", [issue["rule"] for issue in payload["issues"]])


class InterfaceTests(unittest.TestCase):
    def test_interface_keeps_only_the_two_rewrite_views_and_no_consent_ui(self):
        javascript = (
            Path(__file__).resolve().parents[1] / "assets" / "site.js"
        ).read_text(encoding="utf-8")

        self.assertIn(">Diff</button>", javascript)
        self.assertIn(">Interactive change</button>", javascript)
        self.assertNotIn(">Before</button>", javascript)
        self.assertNotIn(">Suggested</button>", javascript)
        self.assertNotIn("consent", javascript.lower())
        self.assertNotIn("count-summary", javascript)


class TelemetryTests(unittest.TestCase):
    def test_research_events_are_automatic_and_drop_unapproved_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".private").mkdir()
            token = "a" * 32
            manifest = {
                "schema_version": 2,
                "telemetry_mode": "research",
                "review_id": "review-1",
                "sites": {token: {"site_id": "pseudonym-1"}},
            }
            (root / ".private" / "manifest.json").write_text(json.dumps(manifest))
            database = root / ".private" / "events.sqlite3"
            server = sites.FeedbackServer(("127.0.0.1", 0), root, database)
            base = {
                "token": token,
                "session_id": "12345678-1234-1234-1234-123456789abc",
                "client_ts": "2026-08-24T10:00:00Z",
                "item_id": "issue-safe-id",
            }
            try:
                server.accept_event({**base, "event_name": "page_open", "properties": {}})
                server.accept_event(
                    {
                        **base,
                        "event_name": "item_dwell",
                        "properties": {
                            "kind": "warning",
                            "visible_seconds": 4.2,
                            "source_code": "private",
                        },
                    }
                )
                server.record_access(token)
            finally:
                server.server_close()

            connection = sqlite3.connect(database)
            rows = connection.execute(
                "SELECT event_name, properties_json FROM events ORDER BY id"
            ).fetchall()
            connection.close()
            self.assertEqual([row[0] for row in rows], ["page_open", "item_dwell", "site_access"])
            self.assertNotIn("source_code", rows[1][1])
            self.assertIn("visible_seconds", rows[1][1])


class IncrementalDeploymentTests(unittest.TestCase):
    def publish_args(
        self,
        root: Path,
        reports: Path,
        diffs: Path,
        roster: Path,
        review_id: str,
    ) -> argparse.Namespace:
        return argparse.Namespace(
            deployment=str(root / "deployment"),
            reports=str(reports),
            diffs=str(diffs),
            roster=str(roster),
            course_title="Construction",
            assignment_title="Find",
            review_id=review_id,
            base_url="http://localhost:4173",
            telemetry="research",
        )

    def test_running_server_discovers_atomically_published_reviews(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "reports"
            diffs = root / "diffs"
            reports.mkdir()
            diffs.mkdir()
            (reports / "s-100-2.lint.txt").write_text(LINT, encoding="utf-8")
            (diffs / "s-100-2-find.scala.diff").write_text(DIFF, encoding="utf-8")
            roster = root / "roster.csv"
            roster.write_text(
                "student_id,attempt,status,display_name\n"
                "s-100,2,issues,\n",
                encoding="utf-8",
            )
            deployment = root / "deployment"
            sites.publish_review(
                self.publish_args(root, reports, diffs, roster, "review-a")
            )
            with self.assertRaisesRegex(ValueError, "already exists"):
                sites.publish_review(
                    self.publish_args(root, reports, diffs, roster, "review-a")
                )
            registry = json.loads(
                (deployment / ".private" / "registry.json").read_text()
            )
            first_token = next(iter(registry["sites"]))

            database = deployment / ".private" / "telemetry.sqlite3"
            server = sites.FeedbackServer(("127.0.0.1", 0), deployment, database)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base_url = f"http://127.0.0.1:{server.server_port}"
                with urlopen(f"{base_url}/f/{first_token}/") as response:
                    self.assertEqual(response.status, 200)
                    page = response.read().decode("utf-8")
                with urlopen(
                    f"{base_url}/f/{first_token}/assets/site.js"
                ) as response:
                    self.assertEqual(response.status, 200)
                self.assertIn("assets/site.js?v=", page)

                sites.publish_review(
                    self.publish_args(root, reports, diffs, roster, "review-b")
                )
                updated_registry = json.loads(
                    (deployment / ".private" / "registry.json").read_text()
                )
                second_token = next(
                    token
                    for token in updated_registry["sites"]
                    if token != first_token
                )
                with urlopen(f"{base_url}/f/{second_token}/") as response:
                    self.assertEqual(response.status, 200)
                with urlopen(f"{base_url}/healthz") as response:
                    health = json.load(response)
                self.assertEqual(health["reviews"], 2)
                self.assertEqual(health["sites"], 2)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

            rows = sites.event_rows(database)
            self.assertEqual(
                len({row["review_key"] for row in rows if row["event_name"] == "site_access"}),
                2,
            )
            summary = sites.summarize_events(
                rows, sites.read_metadata(database), sites.read_reviews(database)
            )
            self.assertEqual(len(summary["reviews"]), 2)


if __name__ == "__main__":
    unittest.main()
