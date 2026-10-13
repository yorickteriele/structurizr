# Structurizr (local/server/CLI) with PDF/PNG/SVG export support.
#
# Build:  docker build -t structurizr-pdf .
# Run:    see docker/README.md

# ---- build stage: compile the application from source ----
FROM mcr.microsoft.com/playwright/java:v1.62.0-noble AS build

WORKDIR /src
COPY structurizr-application structurizr-application
RUN mvn -B -q -DskipTests -Dgpg.skip -Dmaven.javadoc.skip -f structurizr-application/pom.xml package

# ---- runtime stage: Java + Chromium (the Playwright version must match the one in structurizr-application/pom.xml) ----
FROM mcr.microsoft.com/playwright/java:v1.62.0-noble

ENV PORT=8080
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

COPY --from=build /src/structurizr-application/target/structurizr-*.war /usr/local/structurizr.war
COPY structurizr-themes /usr/local/structurizr-themes
COPY structurizr-application/Dockerfiles/structurizr.sh /usr/local/structurizr.sh
RUN sed -i "s/\r$//" /usr/local/structurizr.sh && chmod +x /usr/local/structurizr.sh

EXPOSE ${PORT}

WORKDIR /usr/local/structurizr

ENTRYPOINT ["/usr/local/structurizr.sh"]
CMD ["local"]
