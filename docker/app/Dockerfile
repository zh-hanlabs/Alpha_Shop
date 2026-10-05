# W7D4 T4.3 加分项：mvn package 后 COPY jar，构建全程零网络（W5D0 playbook 复用）；
# 不引入 buildpacks（需网络，砍单线）。Java 17 target 跑在 JRE 21（pom java.version=17）。
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/shopagent-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
