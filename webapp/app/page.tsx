"use client";

import Editor from "@monaco-editor/react";
import {
  IconBrandGithub,
  IconExternalLink,
  IconPlus,
} from "@tabler/icons-react";
import { useState } from "react";

interface RepoItem {
  url: string;
  name: string;
  owner: string;
}

const parseGithubUrl = (url: string) => {
  try {
    const trimmed = url.trim();
    const match = trimmed.match(/github\.com\/([^/]+)\/([^/]+)/);
    if (match && match[1] && match[2]) {
      return { owner: match[1], name: match[2].replace(/\.git$/, "") };
    }
  } catch (e) {
    console.error("Invalid URL");
  }
  return null;
};

export default function Home() {
  const [repos, setRepos] = useState<RepoItem[]>([]);
  const [editorValue, setEditorValue] = useState<string>("rules = []\n");

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
        <h2 className="title">Repositories</h2>
        <div className="repoGrid">
          {repos.map((repo) => (
            <div key={repo.url} className="repoCard">
              <div className="repoCardHeader">
                <IconBrandGithub stroke={2} className="iconMain" />
                <a
                  href={repo.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="externalLink"
                >
                  <IconExternalLink stroke={2} />
                </a>
              </div>

              <div className="repoCardBody">
                <p>{repo.owner}</p>
                <h3>{repo.name}</h3>
              </div>
            </div>
          ))}
          <button onClick={handleAddRepo} className="addButton">
            <IconPlus stroke={2} />
            <span>Add GitHub Repo</span>
          </button>
        </div>
      </div>
    </div>
  );
}
