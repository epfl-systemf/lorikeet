"use client";

import React, { useMemo } from "react";
import {
  parseDiff,
  Diff,
  Hunk,
  Decoration,
  markEdits,
  tokenize,
  TokenNode,
} from "react-diff-view";
import { File } from "gitdiff-parser";
import "react-diff-view/style/index.css";
import { DefaultRenderToken } from "react-diff-view/types/context";

interface DiffViewerProps {
  rawDiff: string;
  viewType?: "unified" | "split";
}

export default function DiffViewer({
  rawDiff,
  viewType = "split",
}: DiffViewerProps) {
  const files = useMemo(() => {
    try {
      return parseDiff(rawDiff, { nearbySequences: "zip" });
    } catch (e) {
      console.error("Failed to parse diff text", e);
      return [];
    }
  }, [rawDiff]);

  if (!files || files.length === 0) {
    return <div>No changes to display.</div>;
  }

  return (
    <div>
      {files.map((file) => (
        <FileDiff
          key={`${file.oldPath}-${file.newPath}`}
          file={file}
          viewType={viewType}
        />
      ))}
    </div>
  );
}

function FileDiff({
  file,
  viewType,
}: {
  file: File;
  viewType: "unified" | "split";
}) {
  const { oldPath, newPath, type, hunks } = file;
  const tokens = useMemo(
    () =>
      tokenize(hunks, {
        highlight: false,
        enhancers: [markEdits(hunks, { type: "block" })],
      }),
    [hunks],
  );

  return (
    <div key={`${oldPath}-${newPath}`}>
      <div>
        <span title={type === "delete" ? oldPath : newPath}>
          {type === "delete" ? oldPath : newPath}
        </span>
      </div>

      <div>
        <Diff
          viewType={viewType}
          diffType={type}
          hunks={hunks || []}
          tokens={tokens}
        >
          {(hunks) =>
            hunks.map((hunk) => (
              <React.Fragment key={hunk.content}>
                {/* <Decoration>{hunk.content}</Decoration> */}
                <Hunk hunk={hunk} />
              </React.Fragment>
            ))
          }
        </Diff>
      </div>
    </div>
  );
}
