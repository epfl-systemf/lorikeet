(() => {
  "use strict";

  const data = JSON.parse(document.getElementById("feedback-data").textContent);
  const app = document.getElementById("app");
  const sessionId =
    sessionStorage.getItem("lorikeet-session") || crypto.randomUUID();
  sessionStorage.setItem("lorikeet-session", sessionId);

  let activeStarted = document.hidden ? null : performance.now();
  let activeMilliseconds = 0;
  let maxScroll = 0;
  let lastSummary = -1;
  const viewedItems = new Set();
  const visibleItems = new Set();
  const dwellStarted = new Map();

  const escapeHtml = (value) =>
    String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");

  function event(name, itemId = null, properties = {}) {
    if (data.telemetryMode !== "research") return;
    fetch("/api/events", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        token: data.token,
        session_id: sessionId,
        event_name: name,
        client_ts: new Date().toISOString(),
        item_id: itemId,
        properties,
      }),
      keepalive: true,
    }).catch(() => {});
  }

  function codeFrame(path, content, pointer = "") {
    return `<div class="code-frame">
      <div class="code-header">${escapeHtml(path)}</div>
      <pre><code>${escapeHtml(content)}${pointer ? `\n<span class="pointer">${escapeHtml(pointer)}</span>` : ""}</code></pre>
    </div>`;
  }

  function warningCard(issue) {
    return `<article class="feedback-card observed-item" data-item-id="${escapeHtml(issue.id)}" data-kind="warning">
      <div class="card-heading">
        <div><h3>${escapeHtml(issue.rule)}</h3><p class="location">${escapeHtml(issue.path)}:${issue.line}:${issue.column}</p></div>
      </div>
      <p class="explanation">${escapeHtml(issue.message)}</p>
      ${codeFrame(issue.path, issue.code, issue.pointer)}
    </article>`;
  }

  function lineNumber(value) {
    return value == null ? "" : String(value);
  }

  function codeRows(hunk, mode) {
    return hunk.lines
      .filter(
        (line) =>
          mode === "diff" ||
          (mode === "original" ? line.kind !== "add" : line.kind !== "remove"),
      )
      .map((line) => {
        const kind = mode === "diff" ? line.kind : "context";
        const marker =
          mode === "diff"
            ? { add: "+", remove: "−", context: " " }[line.kind]
            : " ";
        const oldNumber = mode === "suggested" ? "" : lineNumber(line.old_line);
        const newNumber = mode === "original" ? "" : lineNumber(line.new_line);
        return `<tr class="${kind}"><td class="line-no old-no">${oldNumber}</td><td class="line-no new-no">${newNumber}</td><td class="marker">${marker}</td><td><code>${escapeHtml(line.content) || " "}</code></td></tr>`;
      })
      .join("");
  }

  function hunks(rewrite, mode) {
    return (
      rewrite.hunks
        .map(
          (hunk) => `<section class="hunk">
      <div class="hunk-label">Around line ${mode === "suggested" ? hunk.new_start : hunk.old_start}${hunk.label ? ` · ${escapeHtml(hunk.label)}` : ""}</div>
      <div class="diff-scroll"><table><tbody>${codeRows(hunk, mode)}</tbody></table></div>
    </section>`,
        )
        .join("") || `<p class="empty-inline">No changed lines found.</p>`
    );
  }

  function diffView(rewrite) {
    return `<div class="diff-view">${hunks(rewrite, "diff")}</div>`;
  }

  function interactiveView(rewrite, version = "original") {
    const suggested = version === "suggested";
    return `<div class="interactive-view">
      <div class="version-control">
        <span class="${suggested ? "" : "selected"}">Original</span>
        <label class="switch">
          <input type="checkbox" data-version-toggle="${escapeHtml(rewrite.id)}" ${suggested ? "checked" : ""} aria-label="Toggle between original and suggested code">
          <span></span>
        </label>
        <span class="${suggested ? "selected" : ""}">Suggested</span>
      </div>
      <div class="diff-view single-version">${hunks(rewrite, version)}</div>
    </div>`;
  }

  function rewriteCard(rewrite) {
    const primary = rewrite.feedback[0];
    const title = primary?.rule || `Rewrite in ${rewrite.file}`;
    const messages = [
      ...new Set(rewrite.feedback.map((item) => item.message).filter(Boolean)),
    ];
    return `<article class="rewrite-card observed-item" data-item-id="${escapeHtml(rewrite.id)}" data-kind="rewrite">
      <div class="card-heading rewrite-heading">
        <div><h3>${escapeHtml(title)}</h3><p class="location">${escapeHtml(rewrite.file)}</p></div>
      </div>
      ${messages.map((message) => `<p class="explanation">${escapeHtml(message)}</p>`).join("")}
      <div class="view-tabs" role="tablist" aria-label="Rewrite display">
        <button role="tab" aria-selected="true" class="active" data-view="diff" data-rewrite="${escapeHtml(rewrite.id)}">Diff</button>
        <button role="tab" aria-selected="false" data-view="interactive" data-rewrite="${escapeHtml(rewrite.id)}">Interactive change</button>
      </div>
      <div class="rewrite-view" id="view-${escapeHtml(rewrite.id)}">${diffView(rewrite)}</div>
    </article>`;
  }

  function resultMessage() {
    if (data.status === "compile_error") {
      return `<div class="result-message caution"><span>!</span><div><h2>The automated review could not run</h2><p>Your submission did not compile in the feedback environment. Ask course staff for the compiler output.</p></div></div>`;
    }
    if (data.status === "missing_files") {
      return `<div class="result-message caution"><span>!</span><div><h2>Target files were missing</h2><p>The selected patterns could not be checked. Verify the submission structure or ask course staff.</p></div></div>`;
    }
    if (!data.issues.length && !data.rewrites.length) {
      return `<div class="result-message"><span>✓</span><div><h2>No selected patterns were found</h2><p>This means the submitted code did not match the patterns used for this review.</p></div></div>`;
    }
    return "";
  }

  const warningSection = data.issues.length
    ? `<section class="content-section observed-section" id="feedback" data-section="warnings" aria-labelledby="feedback-title">
    <header class="section-heading"><h2 id="feedback-title">Warnings</h2></header>
    <div class="card-stack">${data.issues.map(warningCard).join("")}</div>
  </section>`
    : "";
  const rewriteSection = data.rewrites.length
    ? `<section class="content-section observed-section" id="rewrites" data-section="rewrites" aria-labelledby="rewrites-title">
    <header class="section-heading"><h2 id="rewrites-title">Rewrites</h2></header>
    <div class="card-stack">${data.rewrites.map(rewriteCard).join("")}</div>
  </section>`
    : "";

  app.innerHTML = `${resultMessage()}${warningSection}${rewriteSection}`;

  function bindVersionToggle(container) {
    container
      .querySelector("[data-version-toggle]")
      ?.addEventListener("change", (toggleEvent) => {
        const toggle = toggleEvent.target;
        const rewrite = data.rewrites.find(
          (item) => item.id === toggle.dataset.versionToggle,
        );
        const version = toggle.checked ? "suggested" : "original";
        container.innerHTML = interactiveView(rewrite, version);
        bindVersionToggle(container);
        event("rewrite_toggle", rewrite.id, { version });
      });
  }

  document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => {
      const rewrite = data.rewrites.find(
        (item) => item.id === button.dataset.rewrite,
      );
      const card = button.closest(".rewrite-card");
      card.querySelectorAll("[data-view]").forEach((peer) => {
        const selected = peer === button;
        peer.classList.toggle("active", selected);
        peer.setAttribute("aria-selected", String(selected));
      });
      const container = card.querySelector(".rewrite-view");
      container.innerHTML =
        button.dataset.view === "diff"
          ? diffView(rewrite)
          : interactiveView(rewrite);
      if (button.dataset.view === "interactive") bindVersionToggle(container);
      event("rewrite_view", rewrite.id, { mode: button.dataset.view });
    });
  });

  const sectionObserver = new IntersectionObserver(
    (entries, observer) =>
      entries.forEach((entry) => {
        if (entry.isIntersecting && entry.intersectionRatio >= 0.35) {
          event("section_view", null, {
            section: entry.target.dataset.section,
          });
          observer.unobserve(entry.target);
        }
      }),
    { threshold: 0.35 },
  );
  document
    .querySelectorAll(".observed-section")
    .forEach((section) => sectionObserver.observe(section));

  function startDwell(element) {
    if (!document.hidden && !dwellStarted.has(element.dataset.itemId)) {
      dwellStarted.set(element.dataset.itemId, performance.now());
    }
  }

  function endDwell(element) {
    const started = dwellStarted.get(element.dataset.itemId);
    if (started == null) return;
    dwellStarted.delete(element.dataset.itemId);
    const visibleSeconds = (performance.now() - started) / 1000;
    if (visibleSeconds >= 1) {
      event("item_dwell", element.dataset.itemId, {
        kind: element.dataset.kind,
        visible_seconds: Math.round(visibleSeconds * 10) / 10,
      });
    }
  }

  const itemObserver = new IntersectionObserver(
    (entries) =>
      entries.forEach((entry) => {
        const id = entry.target.dataset.itemId;
        if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
          visibleItems.add(entry.target);
          startDwell(entry.target);
          if (!viewedItems.has(id)) {
            viewedItems.add(id);
            event("item_view", id, { kind: entry.target.dataset.kind });
          }
        } else {
          visibleItems.delete(entry.target);
          endDwell(entry.target);
        }
      }),
    { threshold: 0.5 },
  );
  document
    .querySelectorAll(".observed-item")
    .forEach((item) => itemObserver.observe(item));

  function updateActiveTime() {
    if (activeStarted != null) {
      activeMilliseconds += performance.now() - activeStarted;
      activeStarted = performance.now();
    }
  }

  function sendSummary() {
    updateActiveTime();
    const seconds = Math.round(activeMilliseconds / 1000);
    if (seconds === lastSummary) return;
    lastSummary = seconds;
    event("session_summary", null, {
      active_seconds: seconds,
      max_scroll_percent: Math.round(maxScroll),
    });
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      updateActiveTime();
      activeStarted = null;
      visibleItems.forEach(endDwell);
      sendSummary();
    } else {
      activeStarted = performance.now();
      visibleItems.forEach(startDwell);
    }
  });
  window.addEventListener(
    "scroll",
    () => {
      const available = document.documentElement.scrollHeight - innerHeight;
      maxScroll = Math.max(
        maxScroll,
        available > 0 ? (scrollY / available) * 100 : 100,
      );
    },
    { passive: true },
  );
  window.addEventListener("pagehide", () => {
    visibleItems.forEach(endDwell);
    sendSummary();
  });
  setInterval(sendSummary, 30000);

  const privacyDialog = document.getElementById("privacy-dialog");
  const telemetryCopy = {
    off: `<p>This site is not collecting telemetry.</p>`,
    access: `<p>This course records when this personalized review is requested from the course server.</p><p>The access event uses a pseudonymous site identifier and does not include source code, typed text, IP addresses, user agents, or device fingerprints.</p>`,
    research: `<p>This course records access to this review. It also records page sections and feedback items shown on screen, visible time, active page time, scroll depth, and use of rewrite controls.</p><p>Telemetry uses a pseudonymous site identifier and does not include source code, typed text, IP addresses, user agents, or device fingerprints.</p>`,
  };
  document.getElementById("privacy-copy").innerHTML =
    telemetryCopy[data.telemetryMode];
  document
    .getElementById("privacy-button")
    .addEventListener("click", () => privacyDialog.showModal());

  event("page_open", null, {
    feedback_count: data.issues.length,
    rewrite_count: data.rewrites.length,
  });
})();
