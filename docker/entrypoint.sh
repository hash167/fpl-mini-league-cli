#!/bin/sh
# Enable the Java agent only when a Honeycomb key is present so the app
# still starts if telemetry is missing. Header is built from the env var —
# the key is never written into k8s Deployment YAML.
if [ -n "$HONEYCOMB_API_KEY" ]; then
  export OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=${HONEYCOMB_API_KEY}"
  export JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar ${JAVA_TOOL_OPTIONS}"
fi
exec java -jar /app/app.jar server /app/config.yml
