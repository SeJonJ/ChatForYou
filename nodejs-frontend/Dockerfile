FROM node:18-alpine
WORKDIR /app
COPY . .
ENV BASE_PATH=/chatforyou
RUN npm ci --omit=dev
RUN npm run prod
EXPOSE 3000
CMD ["node", "server.js"]
