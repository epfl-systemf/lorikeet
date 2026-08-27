FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY feedback_sites.py /app/feedback_sites.py
COPY assets /app/assets

RUN python -m py_compile /app/feedback_sites.py

EXPOSE 4173
VOLUME ["/data"]

ENTRYPOINT ["python", "/app/feedback_sites.py"]
CMD ["serve", "--directory", "/data", "--host", "0.0.0.0", "--port", "4173"]
