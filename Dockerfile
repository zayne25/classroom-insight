# 构建阶段：Maven + JDK 17
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只复制依赖描述文件，最大化 Docker 层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# 运行阶段：仅 JRE
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache tzdata mysql-client \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# mysqldump / mysql 客户端：让管理后台的“数据备份与恢复”开箱可用
COPY --from=build /build/target/*.jar app.jar

# PORT 由云平台注入（Render / Railway / Fly.io），未注入时回退 8080
ENV PORT=8080
ENV JAVA_OPTS="-Xms128m -Xmx384m"
EXPOSE ${PORT}

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT:-8080}"]
