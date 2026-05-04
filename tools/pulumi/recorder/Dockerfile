FROM golang:1.23-bookworm AS builder

WORKDIR /src
COPY go.mod .
COPY *.go .
COPY catalog/ catalog/
RUN go build -o /recorder/recorder . && \
    go build -o /recorder/catalog ./catalog

# ---- runtime image ----
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*

# Install Pulumi CLI.
RUN curl -fsSL https://get.pulumi.com | sh
ENV PATH="/root/.pulumi/bin:${PATH}"

COPY --from=builder /recorder/recorder /recorder/recorder
COPY --from=builder /recorder/catalog /recorder/catalog
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

COPY testproject/ /testproject/

VOLUME /fixtures

ENTRYPOINT ["/entrypoint.sh"]
