FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /app
COPY docker/embedding/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY ops/postgres/embedding_server.py /app/job_embedding_server.py
COPY ops/interview/embedding_server_interview.py /app/interview_embedding_server.py
EXPOSE 5001 5002
CMD ["python", "/app/job_embedding_server.py"]
