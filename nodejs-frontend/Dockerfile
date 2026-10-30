FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 설정 확정이 빌드보다 먼저 와야 한다. 순서가 바뀌면 dist 에 로컬 설정이 실린다.
RUN npm run prod
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
# server.js 는 Node 내장 모듈만 쓰므로 런타임에 node_modules 가 필요 없다.
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/server.js ./server.js
ENV BASE_PATH=/chatforyou
# 정적 파일만 읽으므로 root 권한이 필요 없다.
USER node
EXPOSE 3000
CMD ["node", "server.js"]
