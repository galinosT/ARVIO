# GMC Cloud: ARVIO's backend (netlify-auth-site) on a plain Node server.
# Build from the repository root:  docker build -f gmc-cloud/Dockerfile -t gmc-cloud .
FROM node:22-slim
ENV NODE_ENV=production
WORKDIR /app
COPY netlify-auth-site/package.json netlify-auth-site/package-lock.json netlify-auth-site/
RUN npm --prefix netlify-auth-site ci --omit=dev --no-audit --no-fund
COPY netlify-auth-site/index.html netlify-auth-site/delete-account.html netlify-auth-site/
COPY netlify-auth-site/assets netlify-auth-site/assets
COPY netlify-auth-site/discord netlify-auth-site/discord
COPY netlify-auth-site/docs netlify-auth-site/docs
COPY netlify-auth-site/netlify netlify-auth-site/netlify
COPY gmc-cloud gmc-cloud
RUN rm -f gmc-cloud/.env && mkdir -p /data && chown node:node /data
USER node
ENV DATA_DIR=/data PORT=8790
EXPOSE 8790
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD node -e "fetch('http://127.0.0.1:8790/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "gmc-cloud/server.cjs"]
