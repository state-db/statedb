# Serves both the load driver and the sampler; they share the same dependencies.
#
# The Makefile stages the statedbsim binary and the generator into .build/ first, so the context
# stays small and Docker never walks workbench/.generated.
FROM debian:bookworm-slim

# Pinned so reruns weeks apart exercise the same CLI. Matches tools/pulumi/recorder.
ARG PULUMI_VERSION=3.242.0
ARG SIM_PROVIDER_VERSION=0.1.0

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl bash postgresql-client procps \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://get.pulumi.com | sh -s -- --version "${PULUMI_VERSION}"
ENV PATH="/root/.pulumi/bin:${PATH}"

# Baked into the image's Pulumi home; statedbsim is not published anywhere to download from.
ENV PULUMI_HOME=/soak/.pulumi-home
COPY .build/pulumi-resource-statedbsim /tmp/pulumi-resource-statedbsim
RUN mkdir -p "${PULUMI_HOME}" && \
    pulumi plugin install resource statedbsim "${SIM_PROVIDER_VERSION}" \
      --file /tmp/pulumi-resource-statedbsim --reinstall && \
    rm /tmp/pulumi-resource-statedbsim

COPY .build/generate.sh /soak/generate.sh
COPY seed.sh drive.sh sample.sh /soak/
RUN chmod +x /soak/*.sh

WORKDIR /soak
