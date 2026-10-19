FROM python:3.12-slim
ARG PIP_INDEX_URL=https://pypi.org/simple
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml ./
RUN mkdir nemo_agent && touch nemo_agent/__init__.py
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" .
RUN rm -rf /app/nemo_agent
COPY --chown=10001:10001 nemo_agent ./nemo_agent
USER 10001:10001
CMD ["python", "-m", "nemo_agent"]
