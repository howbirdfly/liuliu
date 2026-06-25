FROM node:22-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --fetch-retries=5 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=120000

COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src

ARG VITE_API_BASE_URL=
ARG VITE_AMAP_JS_KEY=
ARG VITE_USE_MOCK_LOGIN=false

ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_AMAP_JS_KEY=${VITE_AMAP_JS_KEY}
ENV VITE_USE_MOCK_LOGIN=${VITE_USE_MOCK_LOGIN}

RUN npm run build

FROM nginx:1.27-alpine

COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
