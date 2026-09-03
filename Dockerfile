# Guard Line 단일 컨테이너.
#
# 프론트와 백엔드를 한 오리진으로 묶는다. 따로 배포하면 CORS 허용 목록, wss 스킴, 인증서를
# 배포할 때마다 맞춰야 하는데, 한 서버가 정적 파일과 WebSocket을 모두 맡으면 그 설정이
# 통째로 사라진다. 개발 중 Vite 프록시가 하던 역할을 그대로 서버가 한다.

# --- 1단계: 프론트 빌드 -------------------------------------------------------
FROM node:24-alpine AS frontend
WORKDIR /build

# 의존성 레이어를 먼저 굳혀 소스만 바뀌었을 때 재설치를 피한다.
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci

# 시나리오 음성은 frontend 밖에 있고 vite 플러그인이 dist로 복사한다.
COPY frontend ./frontend
COPY scenarios ./scenarios
RUN cd frontend && npm run build

# --- 2단계: 백엔드 빌드 -------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /build

COPY backend/gradle ./gradle
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY backend/src ./src
# 프론트 산출물을 정적 리소스로 넣는다. SpaWebConfig가 classpath:/static/에서 서빙한다.
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN ./gradlew bootJar --no-daemon -x test

# --- 3단계: 실행 --------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 루트로 돌리지 않는다.
RUN addgroup -S guardline && adduser -S guardline -G guardline
COPY --from=backend /build/build/libs/*.jar app.jar
USER guardline

# Render 등은 PORT 환경변수로 포트를 지정한다. 없으면 8080.
ENV PORT=8080
EXPOSE 8080

# 컨테이너 메모리에 맞춰 힙을 잡는다. 무료 티어는 512MB 안팎이라 고정값을 박으면 터진다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=$PORT -jar app.jar"]
