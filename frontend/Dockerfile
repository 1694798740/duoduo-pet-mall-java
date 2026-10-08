# 网页端「正式版」容器（S1）
#
# 为什么要它：此前网页只能靠开发模式（vite dev server）访问 —— 需要开一个命令行窗口，
# 且那种服务随终端/会话关闭就被回收（S1 之前，用户点开链接看到"无法访问"正是这个原因）。
# 本镜像把网页**打包后的静态文件**交给 nginx 托管，并**同源反代** `/api` 与 `/sandbox` 到网关，
# 于是 `docker compose up -d` 一条命令即可长期使用，不再依赖任何命令行窗口。
#
# 构建约定（与根 Dockerfile 一致）：**不在容器内做前端构建**，复用宿主机的依赖缓存，
# 因此**必须先在本机构建出 dist**：
#     cd frontend && npm run build     # monorepo 根 → 依次构建三个应用（各自 dist）
# 一条命令的替代做法（脚本已备）：scripts/web-up.sh / scripts/web-up.ps1
#
# 为什么用「同源反代」而不是让浏览器直连网关：
#   前端构建时 `VITE_API_BASE` 留空 → 请求走**同源** → httpOnly Cookie 正常携带、
#   不触发跨域预检、**后端 CORS 白名单一个字都不用改**（这是本方案选同源的核心收益）。
#
# 若将来要做 CI 内自包含构建（不依赖宿主机 Node），把本文件换成多阶段写法即可：
#   FROM node:22-alpine AS build … RUN npm ci && npm run build … FROM nginx:1.27-alpine
# 现在不这么做，是因为本机拉取 Docker Hub 基础镜像受限（见 docs/PLAN.md 变更记录）。

FROM nginx:1.27-alpine

# 站点配置：SPA 兜底 + /api、/sandbox 反代 + 上传体积（后端上限 12MB）
#
# 两个构建参数把「哪个应用」与本应用的 nginx 配置解耦（三个站点共用这一份 Dockerfile）：
#   APP        = mall | admin | merchant        —— 决定 COPY 哪一份构建产物
#   NGINX_CONF = nginx.conf / nginx-admin.conf / nginx-merchant.conf
ARG APP=mall
ARG NGINX_CONF=nginx.conf
COPY ${NGINX_CONF} /etc/nginx/conf.d/default.conf

# 构建产物（必须先在本机 npm run build；.dockerignore 已排除宿主 node_modules）
# E7-2 起三个应用**各自构建自己的 dist**（源码、构建、产物三者都分开）：
#     cd frontend && npm run build     # = 依次构建 @duoduo/mall / @duoduo/admin / @duoduo/merchant
# 只构建某一个应用：npm run build:admin 之类（见 frontend/package.json）
COPY apps/${APP}/dist /usr/share/nginx/html

EXPOSE 80

# 容器自检：能取到首页即视为就绪（alpine 自带 busybox wget，不依赖 curl）
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
