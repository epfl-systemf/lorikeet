# Deploy student feedback

The intended address is `https://cs-214.epfl.ch/lorikeet-feedback/`.
It displays `generated/overview.html`; the links open individual feedback pages.
The address without the trailing slash redirects to this address.

This setup uses a Linux server with systemd, Python 3.10+, and an existing Nginx
HTTPS site. It does not require Docker or Scala on the web server. An administrator
of cs-214.epfl.ch must add the route to that site's configuration; pushing to
GitHub alone cannot configure the domain. The public app does not implement login.
If reports need course access restrictions, apply the site's authentication to
the new location as well.

## Check the server first

Run these on the server, not your Mac:

```bash
command -v nginx apache2 httpd docker python3 systemctl
systemctl is-active nginx apache2 httpd
python3 --version
```

If Apache or a managed proxy owns the site, ask its administrator to proxy
`/lorikeet-feedback/` to `http://127.0.0.1:8765/`, stripping the prefix and preserving
the browser's Origin header. Do not install a competing web server on port 443.

## Generate and push from your Mac

From the repository root:

```bash
scala-cli run grading/scripts/Check.scala --server=false
python3 feedback_website/generate_feedback.py --data .
python3 -m unittest discover -s feedback_website -p 'test_*.py'
git add .gitignore README.md feedback_website grading/scripts/Check.scala
git diff --cached --stat
git commit -m "Deploy student feedback with persistent interaction logs"
git push origin HEAD
```

Only publish reports intended for visitors to this site. All HTML files under
`generated/` are served, including older files no longer linked from overview.
The server serves these pre-generated files; it does not need the original Scala
submissions, diffs, or reports. Pull the branch you pushed in the next step.

## Install on the server

Use your deployment account for Git operations. Replace `YOUR_BRANCH` with the
branch pushed above. These commands assume `/srv/lorikeet` does not already exist.

```bash
sudo install -d -o "$USER" -g "$(id -gn)" /srv/lorikeet
git clone --branch YOUR_BRANCH https://github.com/epfl-systemf/lorikeet.git /srv/lorikeet
cd /srv/lorikeet
python3 -m venv .venv
.venv/bin/pip install -r feedback_website/requirements-server.txt
sudo useradd --system --no-create-home --shell /usr/sbin/nologin lorikeet-feedback
sudo cp feedback_website/deploy/lorikeet-feedback.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now lorikeet-feedback
curl -f http://127.0.0.1:8765/
```

The service user needs read/traverse access to `/srv/lorikeet`, the virtual
environment, and generated pages. systemd creates `/var/lib/lorikeet-feedback`
with write access for that user. If Python's venv module is missing, have the
administrator install the distribution's Python venv package.

## Connect the existing Nginx site

Copy `deploy/nginx-location.conf` into the **existing HTTPS server block** for
`cs-214.epfl.ch`, or include that file from inside the block. Keep the site's
existing TLS configuration. Do not replace the entire site configuration.

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -I https://cs-214.epfl.ch/lorikeet-feedback
curl -f https://cs-214.epfl.ch/lorikeet-feedback/
```

The first request should redirect; the second should show Overview HTML.
Open a report, click a code line, and submit a Yes/No rating to verify logs.

## Logs

Interaction logs are kept outside Git:

- `/var/lib/lorikeet-feedback/feedback_events.jsonl`: one event per line,
  including issue load, feedback view, and rating, with client and server timestamps.
- `/var/lib/lorikeet-feedback/feedback_summary.csv`: summary per browser session,
  report, and issue, including view count and most recent rating.

```bash
sudo tail -n 5 /var/lib/lorikeet-feedback/feedback_events.jsonl
sudo head -n 5 /var/lib/lorikeet-feedback/feedback_summary.csv
sudo journalctl -u lorikeet-feedback -n 50 --no-pager
```

Files are created after the first interaction is received. Sessions are anonymous
page sessions, not verified student identities. Overview visits alone do not
create interaction events. HTTP access and application errors go to the systemd
journal. The public app does not serve the editor or log-download APIs.

Keep **one Gunicorn worker**, with threads as configured. Deduplication and writes
use a process-local lock. The file logger rebuilds the summary for each event;
it is intended for modest traffic. Back up the log directory. For larger usage,
move event storage to a database before adding workers. Retry queues in the
browser recover transient request failures, but do not guarantee every event is
recorded if browser storage is cleared or the visitor never returns.

## Update after git push

On the server, in the checked-out deployment branch:

```bash
cd /srv/lorikeet
git pull --ff-only
.venv/bin/pip install -r feedback_website/requirements-server.txt
sudo systemctl restart lorikeet-feedback
```

Regenerate and commit HTML locally when reports or styles change. Restarting does
not delete interaction logs. If the systemd unit changes, copy it again and run
`sudo systemctl daemon-reload` before restarting. If the proxy config changes,
validate and reload Nginx again.

References: [Gunicorn deployment](https://gunicorn.org/deploy/),
[Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass).
