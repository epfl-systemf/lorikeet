# Feedback server deployment

The deployment server exposes only opaque report URLs, event ingestion, and a health check. It does not contain the editor, overview, templates, directory listings, or log-download endpoints.

## Build and publish

Requirements on the build machine: Python 3.9+, Java 17+, and Scala CLI. Find available run timestamps with:

```bash
find grading/output -maxdepth 1 -type d -name 'grading_reports_*' | sort
```

```bash
./feedback_server/build.sh
./feedback_server/publish_lab.sh find-2026 2026.09.24_16.53.56
```

The second argument is the timestamp suffix from `grading_reports_<timestamp>`. Pass a grading-output directory as a third argument when it is not `grading/output`. Publishing creates:

```text
grading/output/deployment/
├── bin/feedback-server.jar
├── public/find-2026/<opaque-id>.html
└── private/
    ├── hmac.key
    └── links/find-2026.csv
```

Back up `private/hmac.key`: it keeps report URLs stable. Never upload `private/`; prepend your public hostname to the paths in the CSV and distribute one URL to each student.

## Install on one Linux host

Install Java 17+, Caddy, `rsync`, and `sqlite3`, then copy the application and public reports:

```bash
sudo install -d /opt/lorikeet-feedback /srv/lorikeet-feedback/public
sudo install -m 0644 grading/output/deployment/bin/feedback-server.jar \
  /opt/lorikeet-feedback/feedback-server.jar
sudo rsync -a --delete grading/output/deployment/public/ \
  /srv/lorikeet-feedback/public/
sudo install -m 0644 feedback_server/deploy/lorikeet-feedback.service \
  /etc/systemd/system/lorikeet-feedback.service
sudo systemctl daemon-reload
sudo systemctl enable --now lorikeet-feedback
```

Copy the site block from `feedback_server/deploy/Caddyfile` into `/etc/caddy/Caddyfile`, replace `feedback.example.edu` with the real hostname, and reload Caddy:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://feedback.example.edu/healthz
```

Only these public routes exist:

- `GET /r/<lab>/<opaque-id>`
- `POST /api/events`
- `GET /healthz`

## Add or update a lab

Grade the new submissions, note the new grading timestamp, and publish a distinct lab name:

```bash
./feedback_server/publish_lab.sh boids-2026 2026.10.08_14.30.00
rsync -a --delete grading/output/deployment/public/boids-2026/ \
  feedback-host:/srv/lorikeet-feedback/public/boids-2026/
```

Existing lab directories are untouched, and the server notices new files without a restart. Republishing the same lab replaces only that lab; its URLs remain stable because the HMAC key is reused.

## Logging

Events are stored in `/var/lib/lorikeet-feedback/feedback.sqlite`. No separate database service is needed. Back it up from the host and export it over SSH when needed:

```bash
sudo sqlite3 /var/lib/lorikeet-feedback/feedback.sqlite \
  ".backup '/var/lib/lorikeet-feedback/feedback.backup.sqlite'"
sudo sqlite3 -header -csv /var/lib/lorikeet-feedback/feedback.sqlite \
  "SELECT * FROM events ORDER BY received_timestamp;" > feedback-events.csv
```
