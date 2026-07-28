# Image du relay server Hasan — network_mode: host (voir server/docker-compose.yml),
# donc aucun EXPOSE/port-mapping n'a d'effet réel ; conservé pour documentation.
FROM python:3.11-slim

RUN useradd --system --create-home --uid 10000 hasanrelay

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

COPY . .

USER hasanrelay

EXPOSE 8767

CMD ["python", "server.py"]
