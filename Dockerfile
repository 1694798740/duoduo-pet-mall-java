# 多多宠物商店 · 微服务运行镜像（三服务共用，靠构建参数 JAR_FILE 区分）
#
# 设计取舍：
#   * 不在容器内做 Maven 构建（镜像最小化 + 复用宿主机依赖缓存），
#     因此**必须先在本机构建出可执行 jar**：
#       mvn -gs maven/settings.xml -B -DskipTests package
#   * 基础镜像用 JRE 而非 JDK：运行时不需要编译器。
#   * alpine + musl 的 temurin 构建体积约 180MB，比标准版小一半。
#
# 单独构建一个服务：
#   docker build --build-arg JAR_FILE=duoduo-user/target/duoduo-user-1.0.0.jar --build-arg APP_PORT=8081 -t duoduo/duoduo-user:1.0.0 .
#
# 生产落地前必须补：-XX:MaxRAMPercentage 按实例内存校准、非 root 用户、只读根文件系统、镜像漏洞扫描。

FROM eclipse-temurin:21-jre-alpine

ARG JAR_FILE
ARG APP_PORT=8080

ENV APP_PORT=${APP_PORT} \
    TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

RUN addgroup -g 10001 duoduo && adduser -D -u 10001 -G duoduo duoduo

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar
RUN chown duoduo:duoduo /app/app.jar

USER duoduo:duoduo
EXPOSE ${APP_PORT}

# 健康检查打 Spring Boot Actuator 的就绪探针（READY 形态），
# 容器内用 busybox 的 wget 探测，避免依赖 curl（alpine 镜像没带）
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=12 \
    CMD wget -q -O - "http://127.0.0.1:${APP_PORT}/actuator/health/readiness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
