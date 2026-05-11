# Stage 1 — Frontend builder
FROM node:22-alpine AS frontend-builder

WORKDIR /app/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# Stage 2 — Backend builder
FROM eclipse-temurin:25-jdk-alpine AS backend-builder

WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN chmod +x ./gradlew

COPY src/ src/
COPY --from=frontend-builder /app/frontend/dist src/main/resources/static/

RUN ./gradlew bootJar --no-daemon -x test

# Stage 3 — Runtime
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S analyzer && adduser -S analyzer -G analyzer

COPY --from=backend-builder /app/build/libs/spring-boot-analyzer.jar app.jar

USER analyzer

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
