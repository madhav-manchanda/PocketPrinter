# Multi-stage production Dockerfile for PocketPrint Backend
FROM python:3.12-slim-bookworm AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

COPY backend/requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

FROM python:3.12-slim-bookworm AS runner

# Security: non-root user
RUN groupadd -g 1001 pocketprint && \
    useradd -u 1001 -g pocketprint -m -s /bin/bash pocketprint

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /root/.local /home/pocketprint/.local
ENV PATH=/home/pocketprint/.local/bin:$PATH
ENV PYTHONPATH=/app

# Create storage mount with proper permissions
RUN mkdir -p /app/storage/documents /app/storage/.tmp && \
    chown -R pocketprint:pocketprint /app

# Copy application files
COPY --chown=pocketprint:pocketprint backend /app/backend
COPY --chown=pocketprint:pocketprint web /app/web

USER pocketprint

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["sh", "-c", "python -m alembic -c backend/alembic.ini upgrade head && uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --workers 1 --proxy-headers --forwarded-allow-ips='*'"]
