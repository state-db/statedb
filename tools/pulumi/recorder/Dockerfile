FROM golang:1.23-bookworm AS builder

WORKDIR /src
COPY go.mod .
COPY *.go .
COPY catalog/ catalog/
RUN go build -o /recorder/recorder . && \
    go build -o /recorder/catalog ./catalog

# ---- runtime image ----
FROM debian:bookworm-slim

# Baseline CLI pin. Do not bump this alone: tools/pulumi/verify/go.mod
# (github.com/pulumi/pulumi/sdk/v3) must move in lockstep, and
# .github/workflows/native.yml pins the same version. The compat sweep's pin
# audit (tools/pulumi/compat/sweep.sh) enforces all three.
ARG PULUMI_VERSION=3.259.0
ARG RANDOM_PROVIDER_VERSION=4.20.0

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl && \
    rm -rf /var/lib/apt/lists/*

# Install the Pulumi CLI and providers used by /testproject at fixed versions so
# fixture refreshes are repeatable.
RUN curl -fsSL https://get.pulumi.com | sh -s -- --version "${PULUMI_VERSION}"
ENV PATH="/root/.pulumi/bin:${PATH}"

RUN pulumi plugin install resource random "${RANDOM_PROVIDER_VERSION}"

# Surfaced to entrypoint.sh for meta.json provenance. Deliberately NOT named
# PULUMI_*: the CLI enumerates every PULUMI_-prefixed variable into the update
# request body, so such a name would plant recorder plumbing in the fixtures.
ENV STATEDB_RECORDER_RANDOM_PROVIDER_VERSION=${RANDOM_PROVIDER_VERSION}

COPY --from=builder /recorder/recorder /recorder/recorder
COPY --from=builder /recorder/catalog /recorder/catalog
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

COPY testproject/ /testproject/

VOLUME /fixtures

ENTRYPOINT ["/entrypoint.sh"]
