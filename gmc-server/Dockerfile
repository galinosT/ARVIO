FROM python:3.12-alpine
WORKDIR /app
COPY server.py .
RUN adduser -D -u 10001 gmc && mkdir /data && chown gmc /data
USER gmc
VOLUME /data
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8787/health || exit 1
CMD ["python", "-u", "server.py"]
