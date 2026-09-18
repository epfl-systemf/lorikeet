FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LORIKEET_LOG_DIR=/var/lib/lorikeet-feedback

WORKDIR /app
COPY requirements-server.txt ./
RUN pip install --no-cache-dir -r requirements-server.txt \
    && groupadd --gid 10001 feedback \
    && useradd --uid 10001 --gid feedback --create-home feedback \
    && mkdir -p /var/lib/lorikeet-feedback \
    && chown feedback:feedback /var/lib/lorikeet-feedback

COPY generate_feedback.py serve_feedback.py ./
COPY generated/ ./generated/
USER feedback
EXPOSE 8765
CMD ["gunicorn", "--bind", "0.0.0.0:8765", "--workers", "1", "--threads", "4", "--timeout", "60", "--access-logfile", "-", "--error-logfile", "-", "serve_feedback:build_app()"]
