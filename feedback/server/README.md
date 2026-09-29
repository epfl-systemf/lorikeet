# Feedback server deployment

The deployment server exposes only opaque report URLs, event ingestion, and a health check. It does not contain the editor, overview, templates, directory listings, or log-download endpoints.

## Build and publish

Requirements on the build machine: Java 17+ and Scala CLI. Find available run timestamps with:

```bash
find feedback/feedback_automation/output -maxdepth 1 -type d -name 'grading_reports_*' | sort
```

```bash
./feedback/server/build.sh
./feedback/server/publish_lab.sh find-2026 2026.09.24_16.53.56
```

The second argument is the timestamp suffix from `grading_reports_<timestamp>`. Pass an output directory as a third argument when it is not `feedback/feedback_automation/output`. Publishing creates:

```text
feedback/feedback_automation/output/deployment/
├── bin/feedback-server.jar
├── public/find-2026/<opaque-id>.html
└── private/
    ├── hmac.key
    └── links/find-2026.csv
```

Back up `private/hmac.key`: it keeps report URLs stable. Never upload `private/`; prepend your public hostname to the paths in the CSV and distribute one URL to each student.

## Install on one Linux host

Install Java 17+, `rsync`, and `sqlite3` on the nginx host, then copy the application and public reports:

```bash
sudo install -d /opt/code-quality-feedback /srv/code-quality-feedback/public
sudo install -m 0644 feedback/feedback_automation/output/deployment/bin/feedback-server.jar \
  /opt/code-quality-feedback/feedback-server.jar
sudo rsync -a --delete feedback/feedback_automation/output/deployment/public/ \
  /srv/code-quality-feedback/public/
sudo install -m 0644 feedback/server/deploy/code-quality-feedback.service \
  /etc/systemd/system/code-quality-feedback.service
sudo systemctl daemon-reload
sudo systemctl enable --now code-quality-feedback
```

Add `feedback/server/deploy/nginx-location.conf` inside the existing HTTPS nginx `server` block for the feedback hostname, then reload nginx. Keep the `Host` header: the event endpoint uses it to check the browser's origin.

```bash
sudo nginx -t
sudo systemctl reload nginx
curl https://feedback.example.edu/healthz
```

Only these public routes exist:

- `GET /r/<lab>/<opaque-id>`
- `POST /api/events`
- `GET /healthz`

## Add or update a lab

Grade the new submissions, note the new grading timestamp, and publish a distinct lab name:

```bash
./feedback/server/publish_lab.sh boids-2026 2026.10.08_14.30.00
rsync -a --delete feedback/feedback_automation/output/deployment/public/boids-2026/ \
  feedback-host:/srv/code-quality-feedback/public/boids-2026/
```

Existing lab directories are untouched, and the server notices new files without a restart. Republishing the same lab replaces only that lab; its URLs remain stable because the HMAC key is reused.

## Logging

Events are stored in `/var/lib/code-quality-feedback/feedback.sqlite`. No separate database service is needed. Back it up from the host and export it over SSH when needed:

```bash
sudo sqlite3 /var/lib/code-quality-feedback/feedback.sqlite \
  ".backup '/var/lib/code-quality-feedback/feedback.backup.sqlite'"
sudo sqlite3 -header -csv /var/lib/code-quality-feedback/feedback.sqlite \
  "SELECT * FROM events ORDER BY received_timestamp;" > feedback-events.csv
```
