# 多阶段构建:第一阶段用 Node 构建 React 控制台,
# 第二阶段用 Nginx 提供静态文件并把 /api 反向代理到 z-graph-server。
# 适用于分布式部署:仅部署 z-graph-frontend,后端 z-graph-server 独立。
# upstream 通过 envsubst 在 nginx 官方 entrypoint 里注入,默认 z-graph-server:8090。

# ===== 构建阶段 =====
FROM node:20-alpine AS build
WORKDIR /workspace

# 先复制 package.json 让依赖层独立缓存
COPY z-graph-console/package.json z-graph-console/package-lock.json* ./z-graph-console/
WORKDIR /workspace/z-graph-console
RUN npm install --no-audit --no-fund

# 复制源代码并构建
COPY z-graph-console ./
RUN npm run build

# ===== 运行时阶段 =====
FROM nginx:1.27-alpine AS runtime
LABEL org.opencontainers.image.title="z-graph-frontend" \
      org.opencontainers.image.description="z-graph React 控制台(Nginx 提供静态资源 + API 反向代理)" \
      org.opencontainers.image.source="https://github.com/z-opc-foundation/z-graph"

# 模板文件:nginx 官方 entrypoint 会自动 envsubst 处理并写到 /etc/nginx/conf.d/
COPY deploy/nginx/frontend.conf /etc/nginx/templates/default.conf.template

# 复制构建产物
COPY --from=build /workspace/z-graph-console/dist /usr/share/nginx/html

EXPOSE 80

# 健康检查
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
