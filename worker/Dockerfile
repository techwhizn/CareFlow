ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
COPY --from=ghcr.io/astral-sh/uv:0.8.15 /uv /usr/local/bin/uv
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim libglib2.0-0 libgl1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY careflow careflow
RUN useradd --system --uid 10001 careflow && mkdir -p /app/cache && chown careflow /app/cache
ENV PATH="/app/.venv/bin:$PATH" TIKTOKEN_CACHE_DIR=/app/cache
USER careflow
CMD ["python", "-m", "careflow.consumer"]
