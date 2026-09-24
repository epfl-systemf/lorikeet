import json
import re
import tempfile
import unittest
from pathlib import Path

import generate_feedback


class TimelineTest(unittest.TestCase):
    def test_trimmed_blank_diff_context_is_recovered(self):
        original, blocks = generate_feedback.parse_diff(
            "--- before\n+++ after\n@@ -1,2 +1,2 @@\n-old\n+new"
        )

        self.assertEqual(original, {1: "old", 2: ""})
        self.assertEqual(blocks, [{"start": 1, "before": ["old"], "after": ["new"]}])
        with self.assertRaisesRegex(ValueError, "Truncated diff hunk"):
            generate_feedback.parse_diff(
                "--- before\n+++ after\n@@ -1,2 +1,2 @@\n-old\n+new\n"
            )

    def test_observations_follow_rewrites_and_duplicates_are_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "grading_reports_demo"
            histories = root / "grading_histories_demo"
            originals = root / "grading_originals_demo"
            reports.mkdir()
            histories.mkdir()
            originals.mkdir()
            report = reports / "student-0.lint.txt"
            report.write_text(
                """[Rewrite]
rewrite it (1 occurrences)

src/Sample.scala:1:1
if ready then true else false
^

[Var Usage]
avoid mutation (2 occurrences)

src/Sample.scala:2:1
var found = false
^

src/Sample.scala:3:1
var current = false
^
""",
                encoding="utf-8",
            )
            histories.joinpath("student-0-a.history.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "file": "src/Sample.scala",
                        "limit": 10,
                        "truncated": False,
                        "initial": "if ready then true else false\nvar found = false\nvar current = false",
                        "steps": [
                            {
                                "rule": "Rewrite",
                                "description": "rewrite it",
                                "start": 0,
                                "end": 29,
                                "line": 1,
                                "column": 1,
                                "before": "if ready then true else false",
                                "after": "ready",
                                "code": "ready\nvar found = false\nvar current = false",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            originals.joinpath("student-0-Sample.scala").write_text(
                "if  ready then true else false\nvar found=false\nvar current=false",
                encoding="utf-8",
            )

            mockup = generate_feedback.HERE.joinpath("feedback_mockup.html").read_text(
                encoding="utf-8"
            )
            _, _, page, count, _ = generate_feedback.build_report(
                report,
                root,
                {},
                mockup,
                include_scalafmt=True,
            )
            payload = json.loads(
                re.search(
                    r'<script type="application/json" id="timeline-data">(.*?)</script>', page
                ).group(1)
            )
            steps = payload["histories"][0]["steps"]
            self.assertEqual(
                [step["kind"] for step in steps],
                ["rewrite", "rewrite", "observation"],
            )
            self.assertEqual(count, 3)
            self.assertEqual(steps[0]["rule"], "Scalafmt")
            self.assertEqual(
                steps[0]["code"],
                "if ready then true else false\nvar found = false\nvar current = false",
            )
            self.assertEqual(
                payload["histories"][0]["initial"],
                "if  ready then true else false\nvar found=false\nvar current=false",
            )
            self.assertEqual([step["rule"] for step in steps].count("Var Usage"), 1)
            self.assertEqual(steps[2]["code"], steps[1]["code"])
            self.assertEqual(
                steps[2]["code"][steps[2]["start"] : steps[2]["end"]],
                "var found = false",
            )

            highlight = mockup.index('busy=true;render({start:step.start,end:step.end},true);')
            rewrite = mockup.index('current++;busy=false;render(null,true);', highlight)
            self.assertLess(highlight, rewrite)
            self.assertIn('codeView.scrollTop=previousTop;', mockup)
            self.assertIn('render(null,true)', mockup)
            self.assertIn('class="line-number"', mockup)
            self.assertIn('data-expand-${direction}', mockup)
            self.assertIn('focus-line', mockup)


if __name__ == "__main__":
    unittest.main()
