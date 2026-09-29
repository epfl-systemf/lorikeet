# Feedback server deployment

The public server exposes only opaque student report URLs (`GET /r/<lab>/<id>`), event ingestion (`POST /api/events`), and `GET /healthz`. It does not expose the local editor, overview, templates, directory listings, or event downloads. Student submissions stay on the build machine; only generated public report pages go to the host.

## Build and smoke-test locally first

On this machine, run the real build step, then start the local helper from the repository root. Java 17+, Scala CLI, curl, and sqlite3 are needed; no sudo is needed.

```bash
./feedback/server/build.sh
./feedback/server/local_smoke.sh
```

The helper checks that the server starts with no reports or events, then prints a `publish_lab.sh` command. In a second terminal, run **that printed command**: it is the same publishing step you will use for a real lab, pointed at a temporary synthetic grading run. The helper then prints a report URL. Open it in your browser, step forward through the feedback, and click **Yes** or **No** under “Was this feedback helpful?” Back in the helper terminal, press Enter to see the saved `issue_loaded`, `feedback_view`, and `feedback_rating` events. Press Ctrl-C there to stop the server and remove the temporary data.

This tests the built server, publishing, report display, and browser-driven logging locally. It does not test the future host's systemd, nginx, DNS, or TLS setup.

## Deploy the empty server

On the host, arrange a DNS name and a valid HTTPS certificate for it, and install Java 17+ and nginx. Package installation and initial host configuration require sudo. The login account used for deployment will own only the public report directory, so subsequent lab uploads need no sudo. Use a dedicated account for this if other people have access to the host.

From the repository root on this machine, copy the jar, service unit, and nginx example to your unprivileged account on the host (replace `user@feedback-host`):

```bash
scp feedback/feedback_automation/output/deployment/bin/feedback-server.jar \
  feedback/server/deploy/code-quality-feedback.service \
  feedback/server/deploy/nginx-server.example.conf user@feedback-host:
```

On the host, from the directory where those files landed:

```bash
sudo install -d -m 0755 /opt/code-quality-feedback \
  /srv/code-quality-feedback /srv/code-quality-feedback/public
sudo chown "$(id -un):$(id -gn)" /srv/code-quality-feedback/public
sudo install -m 0644 feedback-server.jar /opt/code-quality-feedback/feedback-server.jar
sudo install -m 0644 code-quality-feedback.service \
  /etc/systemd/system/code-quality-feedback.service
sudo systemctl daemon-reload
sudo systemctl start code-quality-feedback
systemctl is-active code-quality-feedback
find /srv/code-quality-feedback/public -type f -print
```

The service should be `active`; `find` should print nothing. The service creates `/var/lib/code-quality-feedback/feedback.sqlite` itself. It listens on loopback, so do not expose port 8766 publicly. If startup fails, inspect `sudo journalctl -u code-quality-feedback -n 50 --no-pager`.

nginx is not configured by this repository. The staged `nginx-server.example.conf` is a complete example site. Inspect the host's included site paths with `sudo nginx -T`, install it in an included path, and edit its hostname and certificate paths before testing. If your distribution uses `sites-enabled`, enable the site there. Keep its `Host` header because the event endpoint checks browser origins against it. Editing/enabling the site, testing, and reloading nginx need sudo. For example, **if `/etc/nginx/conf.d/` is included**:

```bash
sudo nginx -T
sudo install -m 0644 nginx-server.example.conf /etc/nginx/conf.d/code-quality-feedback.conf
sudoedit /etc/nginx/conf.d/code-quality-feedback.conf
sudo nginx -t
sudo systemctl reload nginx
sudo systemctl enable code-quality-feedback
```

From another machine, open `https://feedback.example.edu/healthz` in a browser and expect `ok`. Opening `https://feedback.example.edu/generated/overview.html` should show a not-found response. These checks exercise the actual host, reverse proxy, DNS, and TLS before reports are uploaded. After the first real lab is published, open one of its student links and step through feedback as in the local test. Do not run `publish_lab.sh` or copy a `public/` tree during the empty-server deployment.

## Publish reports later

Run `publish_lab.sh` on the build machine, not the public host, after generating and checking the feedback automation output. From the repository root:

```bash
find feedback/feedback_automation/output -maxdepth 1 -type f -name 'grading_results_*.json' | sort
./feedback/server/publish_lab.sh find-2026 2026.09.24_16.53.56
```

The arguments are the public lab name and the timestamp suffix of `grading_results_<timestamp>.json`. An optional third argument replaces the default `feedback/feedback_automation/output` input directory. The script resolves repository paths, then runs `GenerateFeedback.scala` with that input, the HTML template, `--run`, `--publish-lab`, and `--deployment-root`. `build.sh` packages the server jar; it does not publish reports. `LORIKEET_DEPLOYMENT_ROOT` can override the default deployment output directory for both scripts.

Publishing reads that run's result manifest and full-code histories; renders one HTML page per submission in `deployment/public/<lab>/`; replaces only the chosen lab's local public directory; and writes `deployment/private/links/<lab>.csv`. It creates/reuses `deployment/private/hmac.key` so the same lab/submission keeps the same opaque URL on later publishes.

Back up `private/hmac.key` so republishing keeps URLs stable. Never upload `private/`. After checking the generated pages and CSV, transfer only the chosen lab's public pages. From the repository root on the build machine:

```bash
rsync -a --delete --chmod=D755,F644 \
  feedback/feedback_automation/output/deployment/public/find-2026/ \
  user@feedback-host:/srv/code-quality-feedback/public/find-2026/
```

No sudo is needed for this upload: the deployment account owns `/srv/code-quality-feedback/public`, while the systemd service has read-only access to it. `--delete` is limited to **that one lab directory**, not the whole public tree; `--chmod` keeps the pages readable by the service. The server notices new pages without a restart. Prepend your HTTPS hostname to the CSV paths before distributing individual links to students.

## Event database

Feedback events are stored in `/var/lib/code-quality-feedback/feedback.sqlite`. On the host, the following should return `0` before any report is published. Inspecting or backing up this protected database requires sudo; building, publishing, transferring reports, and HTTP smoke checks do not.

```bash
sudo sqlite3 /var/lib/code-quality-feedback/feedback.sqlite 'SELECT count(*) FROM events;'
```
