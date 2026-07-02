# 멀티스테이지: 컨테이너 안에서 Gradle 로 빌드 → JRE 로 실행 (호스트에 Java 불필요)
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 기존 업로드 이미지는 /seed 에 구워두고, 부팅 시 볼륨(/app/uploads)이 비어있으면 복사(기존 파일은 안 덮음).
# → /app/uploads 에 Railway 볼륨을 마운트하면: 기존 이미지 표시 + 새 업로드 영구 보존 둘 다 됨.
COPY uploads /seed/uploads
EXPOSE 8080
ENTRYPOINT ["/bin/sh","-c","mkdir -p /app/uploads && cp -rn /seed/uploads/. /app/uploads/ 2>/dev/null || true; exec java -jar /app/app.jar"]
