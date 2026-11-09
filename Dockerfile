# syntax=docker/dockerfile:1
# Imagem do servidor de autorização (apps/server): build com o Maven Wrapper, runtime só com a JRE e usuário sem
# privilégios. As bibliotecas (auth-security-api, auth-security) não viram imagem: vão por um repositório Maven.
# Uso: docker build -t auth:local .

FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
WORKDIR /build

# Dependências numa camada própria: mudar só o código não baixa tudo de novo. Os poms de todos os módulos entram
# antes das fontes, nas mesmas pastas (apps/, modules/ e tools/).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY --parents */*/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Os testes precisam de Docker (Testcontainers) e rodam no CI com ./mvnw verify, não dentro do build da imagem.
COPY --parents */*/src ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests -pl apps/server -am \
    && cp apps/server/target/*-exec.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble

# curl só para o HEALTHCHECK: a imagem da JRE não traz curl nem wget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# Camadas do Spring Boot, da que menos muda para a que mais muda.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER app:app
# 8090: páginas, protocolo OAuth, /livez e /readyz; 8091: actuator completo, só para a rede interna.
EXPOSE 8090 8091
# Configuração de cada projeto (clientes OAuth, audiência, nome exibido): monte um application.yml em /config.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/config/"

# /livez, e não /actuator/health: a queda do Redis entra no health completo e não pode reiniciar o servidor.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:8090/livez" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
