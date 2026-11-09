# Imagem da API ou da aplicação web a partir do jar já construído (ARG JAR): ./mvnw package, e então
# docker compose --profile app up --build. O build fica fora da imagem para não precisar do token do GitHub Packages.
FROM eclipse-temurin:25.0.5_8-jre-noble
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
ARG JAR
COPY ${JAR} application.jar
USER app:app
EXPOSE 8170 8180 8181
ENTRYPOINT ["java", "-jar", "application.jar"]
