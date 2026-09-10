ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE} AS ocr-build
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
 && apt-get update -o Acquire::Retries=3 \
 && apt-get install -y --no-install-recommends build-essential cmake pkg-config curl libleptonica-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl --fail --location --retry 3 https://codeload.github.com/tesseract-ocr/tesseract/tar.gz/refs/tags/5.5.3 -o tesseract.tar.gz \
 && echo '9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884  tesseract.tar.gz' | sha256sum -c - \
 && tar -xzf tesseract.tar.gz
RUN cmake -S tesseract-5.5.3 -B build -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/opt/tesseract -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF -DGRAPHICS_DISABLED=ON \
      -DDISABLE_ARCHIVE=ON -DDISABLE_CURL=ON -DOPENMP_BUILD=ON \
 && cmake --build build --parallel 2 \
 && cmake --install build
FROM ${PYTHON_IMAGE}
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
 && apt-get update -o Acquire::Retries=3 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends libleptonica6 libgomp1 tesseract-ocr-eng tesseract-ocr-chi-sim \
 && rm -rf /var/lib/apt/lists/*
COPY --from=ocr-build /opt/tesseract/bin/tesseract /usr/local/bin/tesseract
COPY --from=ocr-build /src/tesseract-5.5.3/LICENSE /usr/share/licenses/tesseract/LICENSE
WORKDIR /app
COPY LICENSE NOTICE /usr/share/licenses/careflow/
COPY pyproject.toml uv.lock ./
RUN --mount=from=ghcr.io/astral-sh/uv:0.8.15,source=/uv,target=/usr/local/bin/uv uv sync --frozen --no-dev
COPY careflow careflow
RUN useradd --system --uid 10001 careflow && mkdir -p /app/cache && chown careflow /app/cache
ENV PATH="/app/.venv/bin:$PATH" TIKTOKEN_CACHE_DIR=/app/cache TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata OMP_THREAD_LIMIT=2
USER careflow
CMD ["python", "-m", "careflow.consumer"]
