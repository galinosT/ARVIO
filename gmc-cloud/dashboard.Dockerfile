# GMC dashboard: ARVIO's web app (web/) in hosted mode, pointed at GMC Cloud.
# Build from the repository root:
#   docker build -f gmc-cloud/dashboard.Dockerfile \
#     --build-arg BACKEND_URL=https://gmc.projectneverland.gr/.netlify/functions \
#     --build-arg APP_ANON_KEY=<the GMC app key> -t gmc-dashboard .
FROM node:22-bookworm-slim AS builder
WORKDIR /src/web
ENV NEXT_TELEMETRY_DISABLED=1
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
# The interface strings are generated from the Android resources.
COPY app/src/main/res /src/app/src/main/res
ARG BACKEND_URL
ARG APP_ANON_KEY
ENV NEXT_PUBLIC_SELF_HOSTED=false \
    NEXT_PUBLIC_PAYWALL_ENABLED=false \
    NEXT_PUBLIC_TELEGRAM_ENABLED=false \
    NEXT_PUBLIC_NETLIFY_BACKEND_URL=${BACKEND_URL} \
    NEXT_PUBLIC_ARVIO_APP_ANON_KEY=${APP_ANON_KEY} \
    ARVIO_STANDALONE=true
RUN test -n "$BACKEND_URL" && test ${#APP_ANON_KEY} -ge 40 && npm run build

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --from=builder --chown=node:node /src/web/.next/standalone ./
COPY --from=builder --chown=node:node /src/web/.next/static ./.next/static
COPY --from=builder --chown=node:node /src/web/public ./public
RUN mkdir -p /app/.data/simkl-sessions /app/.data/wetrakr-sessions && chown -R node:node /app/.data && chmod 700 /app/.data
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD node -e "fetch('http://127.0.0.1:3000/version.json',{signal:AbortSignal.timeout(4000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server.js"]
