FROM node:24.11.0-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
COPY frontend/.npmrc ./.npmrc
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./pom.xml
COPY deployment/maven-settings.xml ./settings.xml
RUN mvn -s settings.xml -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN mvn -s settings.xml -B package -DskipTests
FROM eclipse-temurin:21.0.9_10-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app && mkdir /app/data && chown -R app:app /app
COPY --from=backend --chown=app:app /build/target/agentlens.jar /app/agentlens.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/agentlens.jar"]
