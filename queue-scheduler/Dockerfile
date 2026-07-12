FROM eclipse-temurin:25-jre-alpine

ARG DD_JAVA_AGENT_VERSION=1.60.1

WORKDIR /app

ADD https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar /opt/datadog/dd-java-agent.jar
COPY build/libs/*.jar app.jar

# Headless worker: no web server, no exposed port.
ENTRYPOINT ["java", "-jar", "app.jar"]
