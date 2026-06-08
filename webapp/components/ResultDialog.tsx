import { ProjectResult } from "@/lib/api";
import DiffViewer from "./DiffViewer";
import { IconCircle, IconCircleCheck, IconCircleX } from "@tabler/icons-react";
import { parse } from "path";
import { parseGithubUrl } from "@/lib/utils";
import styles from "./ResultDialog.module.scss";

export default function ResultDialog({
  projectResult,
}: {
  projectResult: ProjectResult;
}) {
  const githubUrl = parseGithubUrl(projectResult.path);
  const displayPath = githubUrl
    ? `${githubUrl.owner} / ${githubUrl.name}`
    : projectResult.path;
  return (
    <div className={styles.resultDialog}>
      <span className={styles.resultDialogTitle}>
        {projectResult.result === "SUCCESS" ? (
          <IconCircleCheck style={{ color: "green" }} />
        ) : (
          <IconCircleX style={{ color: "red" }} />
        )}
        <h3>{displayPath}</h3>
      </span>
      <div className={styles.resultDialogContent}>
        {projectResult.report && (
          <div>
            <h4 className={styles.resultDialogSubtitle}>Report</h4>
            <pre className={styles.report}>{projectResult.report}</pre>
          </div>
        )}
        {projectResult.diff && (
          <div>
            <h4 className={styles.resultDialogSubtitle}>Diff</h4>
            <DiffViewer rawDiff={projectResult.diff} />
          </div>
        )}
      </div>
    </div>
  );
}
