# Lorikeet Server

Backend for the Lorikeet webapp.

## API Endpoints

### `POST /api/refactor`

Submits a refactoring job for execution. Every path in `projectPaths` is processed as part of the same job.

Request body:

```json
{
  "rule": "rule body",
  "projectPaths": ["/path/to/project-a", "/path/to/project-b"]
}
```

Response body:

```json
{
  "id": "job-id",
  "request": {
    "rule": "rule body",
    "projectPaths": ["/path/to/project-a", "/path/to/project-b"]
  },
  "status": "PENDING",
  "results": {}
}
```

### `GET /api/jobs/{jobId}`

Returns the current state of a submitted job, including the per-project results collected so far.

Response body:

```json
{
  "id": "job-id",
  "request": {
    "rule": "rule body",
    "projectPaths": ["/path/to/project-a", "/path/to/project-b"]
  },
  "status": "RUNNING",
  "results": {
    "/path/to/project-a": {
      "path": "/path/to/project-a",
      "result": "SUCCESS",
      "diff": "optional unified diff",
      "report": "optional execution report"
    }
  }
}
```

### Job status

- `PENDING`: job accepted and waiting in the queue
- `RUNNING`: job is currently being processed
- `COMPLETED`: job finished successfully
- `FAILED`: job finished with an error

### API docs

The server also exposes Swagger documentation generated from the Tapir endpoints.
