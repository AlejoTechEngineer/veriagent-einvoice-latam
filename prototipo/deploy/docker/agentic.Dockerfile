# Plano agéntico: API de revisión + consola web + servidor MCP. Incluye JRE para invocar el kit en lote.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
 && rm -rf /var/lib/apt/lists/* && useradd -r -u 10001 agent
WORKDIR /app
COPY agentic-plane/pyproject.toml ./agentic-plane/
RUN pip install --no-cache-dir pydantic lxml fastapi uvicorn httpx "mcp>=2" anthropic
COPY agentic-plane ./agentic-plane
COPY review-console-web ./review-console-web
COPY specs ./specs
COPY catalogs ./catalogs
COPY schemas ./schemas
COPY dist/verification-kit-0.1.0.jar ./dist/verification-kit-0.1.0.jar
ENV TFG_KIT_JAR=/app/dist/verification-kit-0.1.0.jar TFG_DB=/data/proposals.db PYTHONPATH=/app/agentic-plane
RUN mkdir /data && chown agent /data
USER agent
EXPOSE 8090
CMD ["uvicorn", "tfg_agents.review_api:app", "--host", "0.0.0.0", "--port", "8090"]
