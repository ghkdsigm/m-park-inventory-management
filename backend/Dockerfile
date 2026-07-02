# 멀티스테이지: 컨테이너 안에서 Gradle 로 빌드 → JRE 로 실행 (호스트에 Java 불필요)
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# PoC용: 로컬에서 업로드된 이미지 파일을 이미지에 구워넣어 배포 후에도 표시되게 함.
# (운영에서는 S3 등 외부 스토리지로 대체 예정. 볼륨을 /app/uploads 에 마운트하면 이 파일들이 가려지니 주의)
COPY uploads /app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
