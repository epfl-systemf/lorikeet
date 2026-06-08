"use client";

import Modal from "@/components/Modal";
import RepoCard, { RepoItem } from "@/components/RepoCard";
import ResultDialog from "@/components/ResultDialog";
import {
  Job,
  pollJobUntilComplete,
  ProjectResult,
  submitRefactorJob,
} from "@/lib/api";
import { parseGithubUrl } from "@/lib/utils";
import Editor from "@monaco-editor/react";
import { IconPlayerPlay, IconPlus } from "@tabler/icons-react";
import { useState } from "react";

export default function Home() {
  const [repos, setRepos] = useState<RepoItem[]>([]);
  const [editorValue, setEditorValue] = useState<string>("rules = []\n");

  // Dialog state
  const [activeSingleResult, setActiveSingleResult] =
    useState<ProjectResult | null>(null);
  const [activeGlobalJob, setActiveGlobalJob] = useState<Job | null>(null);
  const [isGlobalRunning, setIsGlobalRunning] = useState(false);
  const [showGlobalSummary, setShowGlobalSummary] = useState(false);

  const handleAddRepo = () => {
    const url = prompt(
      "Enter a GitHub repository URL (e.g., https://github.com/scala/scala3):",
    );
    if (!url) return;

    const parsed = parseGithubUrl(url);
    if (!parsed) {
      alert(
        "Invalid GitHub URL. Please make sure it follows the format: github.com/owner/repo",
      );
      return;
    }

    const newRepo: RepoItem = {
      url: url.trim(),
      name: parsed.name,
      owner: parsed.owner,
    };

    setRepos([...repos, newRepo]);
  };

  const handleRunAll = async () => {
    if (repos.length === 0) return alert("Please add at least one repository.");
    if (!editorValue || editorValue.trim() === "")
      return alert("No rule present in the editor.");

    try {
      setIsGlobalRunning(true);
      const job = await submitRefactorJob({
        rule: editorValue,
        projectPaths: repos.map((r) => r.url),
      });

      setActiveGlobalJob(job);

      const completedJob = await pollJobUntilComplete(job.id);
      setActiveGlobalJob(completedJob);
      setShowGlobalSummary(true);
    } catch (e) {
      console.error(e);
      alert("Global run failed.");
    } finally {
      setIsGlobalRunning(false);
    }
  };

  return (
    <div className="container">
      <div className="editorSection">
        <Editor
          defaultLanguage="hocon"
          theme="vs-dark"
          loading="Loading Editor..."
          value={editorValue}
          onChange={(code: string | undefined) =>
            code ? setEditorValue(code) : null
          }
        />
      </div>
      <div className="dashboardSection">
        <div className="dashboardHeader">
          <h2 className="title">Repositories</h2>
          <div className="actionButtons">
            {repos.length > 0 && (
              <button
                onClick={handleRunAll}
                disabled={isGlobalRunning}
                className="runAllButton"
              >
                <IconPlayerPlay size={18} />
                <span>
                  {isGlobalRunning ? "Running on All..." : "Run All Repos"}
                </span>
              </button>
            )}
            {activeGlobalJob && (
              <button
                onClick={() => setShowGlobalSummary(true)}
                className="summaryButton"
              >
                View Last Run Summary
              </button>
            )}
          </div>
        </div>

        <div className="repoGrid">
          {repos.map((repo) => (
            <RepoCard
              key={repo.url}
              repo={repo}
              ruleText={editorValue}
              globalJob={activeGlobalJob}
              onCardClick={(result) => {
                if (!result) {
                  alert(
                    "This repository hasn't been evaluated yet. Click its play button to evaluate.",
                  );
                  return;
                }
                setActiveSingleResult(result);
              }}
            />
          ))}
          <button onClick={handleAddRepo} className="addButton">
            <IconPlus stroke={2} />
            <span>Add GitHub Repo</span>
          </button>
        </div>
      </div>

      {/* Individual Repo Result Modal */}
      <Modal
        isOpen={!!activeSingleResult}
        onClose={() => setActiveSingleResult(null)}
        title="Repository Results"
      >
        {activeSingleResult && (
          <ResultDialog projectResult={activeSingleResult} />
        )}
      </Modal>

      {/* Overall Summary Result Modal */}
      <Modal
        isOpen={showGlobalSummary && !!activeGlobalJob}
        onClose={() => setShowGlobalSummary(false)}
        title="Overall Job Summary"
      >
        {activeGlobalJob && (
          <>
            <p>
              <strong>Job Status:</strong> {activeGlobalJob.status}
            </p>
            <hr />
            <div>
              {Object.values(activeGlobalJob.results).map((res) => (
                <div key={res.path}>
                  <ResultDialog projectResult={res} />
                </div>
              ))}
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}
