export type RunRequest = {
  rule: string;
  projectPaths: string[];
};

export type RunResult = "SUCCESS" | "FAILURE";

export type ProjectResult = {
  path: string;
  result: RunResult;
  diff?: string | null;
  report?: string | null;
};

export type JobStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";

export type Job = {
  id: string;
  request: RunRequest;
  status: JobStatus;
  results: Record<string, ProjectResult>;
};

export type PollJobOptions = {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
};

export class ApiError extends Error {
  readonly status: number;
  readonly details: string | undefined;

  constructor(status: number, message: string, details?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
  }
}

const absoluteUrlPattern = /^[a-zA-Z][a-zA-Z\d+\-.]*:/;

const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "/api").replace(
  /\/$/,
  "",
);

function getOrigin() {
  if (typeof window !== "undefined") {
    return window.location.origin;
  }

  return process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";
}

export function buildApiUrl(path: string) {
  const cleanPath = path.startsWith("/") ? path : `/${path}`;

  if (absoluteUrlPattern.test(apiBaseUrl)) {
    return new URL(`${apiBaseUrl}${cleanPath}`).toString();
  }

  return new URL(`${apiBaseUrl}${cleanPath}`, getOrigin()).toString();
}

async function readResponseBody(response: Response) {
  const contentType = response.headers.get("content-type") ?? "";

  if (contentType.includes("application/json")) {
    return response.json();
  }

  return response.text();
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(buildApiUrl(path), {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const body = await readResponseBody(response);
    const details = typeof body === "string" ? body : JSON.stringify(body);
    throw new ApiError(
      response.status,
      `Request failed with status ${response.status}`,
      details,
    );
  }

  return response.json() as Promise<T>;
}

export function submitRefactorJob(request: RunRequest) {
  return requestJson<Job>("/refactor", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

export function getJobStatus(jobId: string) {
  return requestJson<Job>(`/jobs/${encodeURIComponent(jobId)}`);
}

export async function pollJobUntilComplete(
  jobId: string,
  options: PollJobOptions = {},
) {
  const intervalMs = options.intervalMs ?? 2000;
  const timeoutMs = options.timeoutMs ?? 5 * 60 * 1000;
  const startedAt = Date.now();

  while (true) {
    if (options.signal?.aborted) {
      throw new DOMException("The operation was aborted.", "AbortError");
    }

    const job = await getJobStatus(jobId);

    if (job.status === "COMPLETED" || job.status === "FAILED") {
      return job;
    }

    if (Date.now() - startedAt >= timeoutMs) {
      throw new ApiError(
        408,
        `Polling job ${jobId} timed out after ${timeoutMs}ms`,
      );
    }

    await new Promise<void>((resolve, reject) => {
      const timeoutId = window.setTimeout(resolve, intervalMs);

      if (!options.signal) {
        return;
      }

      const abortHandler = () => {
        window.clearTimeout(timeoutId);
        reject(new DOMException("The operation was aborted.", "AbortError"));
      };

      if (options.signal.aborted) {
        abortHandler();
        return;
      }

      options.signal.addEventListener("abort", abortHandler, { once: true });
    });
  }
}
