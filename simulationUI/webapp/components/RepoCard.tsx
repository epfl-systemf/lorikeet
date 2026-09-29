import {
  IconCircleCheck,
  IconCircleDashedCheck,
  IconExclamationCircle,
  IconExternalLink,
  IconHourglassEmpty,
  IconPlayerPlay,
} from "@tabler/icons-react";
import { useState } from "react";
import {
  submitRefactorJob,
  pollJobUntilComplete,
  Job,
  ProjectResult,
} from "@/lib/api";
import styles from "./RepoCard.module.scss";
import IconButton from "./IconButton";

export interface RepoItem {
  url: string;
  name: string;
  owner: string;
}

// Status
export type RunStatus = "idle" | "running" | "completed" | "failed";

export default function RepoCard({
  repo,
  ruleText,
  onCardClick,
  globalJob,
  globalJobTime,
}: {
  repo: RepoItem;
  ruleText?: string;
  onCardClick: (result: ProjectResult | null) => void;
  globalJob?: Job | null;
  globalJobTime: number | null;
}) {
  const [localStatus, setLocalStatus] = useState<RunStatus>("idle");
  const [lastRunRuleText, setLastRunRuleText] = useState<string | undefined>(
    undefined,
  );
  const [localRunResult, setLocalRunResult] = useState<ProjectResult | null>(
    null,
  );
  const [localJobTime, setLocalJobTime] = useState<number | null>(null);

  const inGlobal = globalJob?.request.projectPaths.find((s) => s == repo.url);
  const globalRepoResult = globalJob?.results?.[repo.url];

  const isGlobal =
    globalJob && inGlobal && (globalJobTime || 0) > (localJobTime || 0);

  const currentResult: ProjectResult | null = isGlobal
    ? globalRepoResult
      ? globalRepoResult
      : null
    : localRunResult;
  const currentStatus: RunStatus = isGlobal
    ? globalRepoResult
      ? globalRepoResult.result === "SUCCESS"
        ? "completed"
        : "failed"
      : globalJob.status == "FAILED"
        ? "failed"
        : "running"
    : localStatus;

  const handleRun = async (e: React.MouseEvent) => {
    e.stopPropagation();

    if (currentStatus === "running") return;
    if (!ruleText || ruleText.trim() === "") {
      alert("No rule present in the editor.");
      return;
    }

    try {
      setLocalStatus("running");
      setLastRunRuleText(ruleText);
      setLocalJobTime(Date.now());

      const job = await submitRefactorJob({
        rule: ruleText,
        projectPaths: [repo.url],
      });

      const completedJob = await pollJobUntilComplete(job.id, (job: Job) => {});
      const result = completedJob.results[repo.url] || null;

      setLocalStatus(
        completedJob.status === "COMPLETED" ? "completed" : "failed",
      );
      setLocalRunResult(result);
    } catch (e) {
      console.error(e);
      setLocalStatus("failed");
    }
  };

  const hasRuleChangedSinceRun =
    currentStatus === "completed" && isGlobal
      ? ruleText !== globalJob.request.rule
      : ruleText !== lastRunRuleText;

  return (
    <div
      key={repo.url}
      className={styles.repoCard}
      onClick={() => onCardClick(currentResult)}
    >
      <div className={styles.repoCardHeader}>
        <div className={`${styles.iconMain} ${styles[currentStatus]}`}>
          <IconButton
            onClick={handleRun}
            icon={(() => {
              switch (currentStatus) {
                case "idle":
                  return <IconPlayerPlay stroke={2} />;
                case "running":
                  return <IconHourglassEmpty stroke={2} />;
                case "completed":
                  return hasRuleChangedSinceRun ? (
                    <IconCircleDashedCheck stroke={2} />
                  ) : (
                    <IconCircleCheck stroke={2} />
                  );
                case "failed":
                  return <IconExclamationCircle stroke={2} />;
              }
            })()}
            title={(() => {
              switch (currentStatus) {
                case "idle":
                  return "Run rule";
                case "running":
                  return "Rule is running";
                case "completed":
                  return hasRuleChangedSinceRun
                    ? "Rule changed since last run"
                    : "Run completed successfully";
                case "failed":
                  return "Run failed";
              }
            })()}
          />
        </div>
        <a
          href={repo.url}
          target="_blank"
          rel="noopener noreferrer"
          className="externalLink"
          onClick={(e) => e.stopPropagation()}
        >
          <IconExternalLink stroke={2} />
        </a>
      </div>

      <div className="repoCardBody">
        <p>{repo.owner}</p>
        <h3>{repo.name}</h3>
      </div>
    </div>
  );
}
