# syntax=docker/dockerfile:1
FROM python:3.13-slim

# AWS Lambda Web Adapter (ADR 0029): runs this FastAPI server inside Lambda unchanged.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/lambda-adapter
ENV AWS_LWA_PORT=8000 \
    AWS_LWA_READINESS_CHECK_PATH=/health \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY pyproject.toml ./
COPY src src
RUN pip install --no-cache-dir .

EXPOSE 8000
CMD ["python", "-m", "uvicorn", "ops_agent.api:app", "--host", "0.0.0.0", "--port", "8000"]
