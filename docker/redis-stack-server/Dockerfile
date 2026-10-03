# W5D0 自建 Redis Stack server（终极路径，前两版 Dockerfile 因网络死局废弃，见 README 踩坑实录）：
# 网络事实：Docker Hub/加速站全断 → 官方镜像不可拉；packages.redis.io 宿主机直连可达。
# 源事实：bookworm 源无 redis-stack-server 包（apt 必报「无法定位软件包」）；包在 bullseye/jammy 源。
# 链接事实：选 jammy 7.4.0-v8 deb——与官方 redis/redis-stack-server:7.4.0-v8 镜像底座（ubuntu22.04/libssl3）同构，
#   bookworm 底座 glibc 2.36≥2.35 + libssl3 在，链接面全兼容（bullseye 版链 OpenSSL 1.1，bookworm 无 1.1）。
# 构建事实：deb 已由宿主机下载至本目录（SHA256 校验过，见 README），构建全程零网络 → BuildKit 网络问题不复存在。
# 多阶段：unpack 阶段 dpkg -x 解包，最终阶段 Linux→Linux COPY 保留 POSIX 权限（Windows 直 COPY 会丢可执行位）。
# 构建命令（仓库根目录）：docker build -t shopagent/redis-stack-server:7.4.0-v8 docker/redis-stack-server
FROM redis:7 AS unpack
COPY redis-stack-server-7.4.0-v8.jammy.amd64.deb /tmp/redis-stack-server.deb
RUN dpkg -x /tmp/redis-stack-server.deb /rootfs

FROM redis:7
COPY --from=unpack /rootfs/ /
EXPOSE 6379
# redis-stack-server 脚本自带全模块 loadmodule + --protected-mode no；--bind 0.0.0.0 透传底层 redis-server
ENTRYPOINT []
CMD ["/opt/redis-stack/bin/redis-stack-server", "--bind", "0.0.0.0"]
