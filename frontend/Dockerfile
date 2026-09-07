FROM node:22-alpine AS build

RUN corepack enable
WORKDIR /workspace
ARG FRONTEND_BUILD_HEAP_MB=2048
ENV NODE_OPTIONS="--max-old-space-size=${FRONTEND_BUILD_HEAP_MB}"
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY packages ./packages
RUN pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80
