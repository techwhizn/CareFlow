ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
COPY --from=ghcr.io/astral-sh/uv:0.8.15 /uv /usr/local/bin/uv
WORKDIR /app
COPY LICENSE NOTICE /usr/share/licenses/careflow/
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY model_registry.py model_server.py download.py ./
RUN useradd --system --uid 10001 careflow
ENV PATH="/app/.venv/bin:$PATH" LOCAL_MODEL_DIRECTORY=/models HF_HUB_OFFLINE=1 HF_HUB_DISABLE_TELEMETRY=1 TOKENIZERS_PARALLELISM=false
USER careflow
CMD ["uvicorn", "model_server:create_app", "--factory", "--host", "0.0.0.0", "--port", "8092", "--no-access-log"]
