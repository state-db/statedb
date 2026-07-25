# Config is baked in rather than bind-mounted; see compose.yml for why the harness avoids mounts.
FROM prom/prometheus:v3.1.0

COPY prometheus.yml /etc/prometheus/prometheus.yml
