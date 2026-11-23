FROM python:3.12-slim

WORKDIR /app
COPY render_server.py /app/render_server.py

ENV PORT=8080
EXPOSE 8080

CMD ["python3", "render_server.py"]
