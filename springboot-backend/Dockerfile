# 빌드 단계
FROM eclipse-temurin:17-jdk-jammy AS builder

# 작업 디렉토리 설정
WORKDIR /workspace/app

# Gradle Wrapper 및 프로젝트 소스 파일 복사
COPY gradlew .
COPY gradle/wrapper/ gradle/wrapper/
COPY build.gradle settings.gradle ./
COPY lombok.config .
COPY src/ src/

# Gradle Wrapper 실행 권한 부여
RUN chmod +x gradlew

# Gradle 캐시 사용 및 프로젝트 빌드
RUN ./gradlew clean build -x test --no-daemon

# 런타임 단계
FROM eclipse-temurin:17-jdk-jammy

# Spring Boot 애플리케이션 실행에 필요한 포트 노출
EXPOSE 8443

# 빌드된 JAR 파일을 복사
COPY --from=builder /workspace/app/build/libs/*.jar app.jar

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.location=/config/application.properties"]