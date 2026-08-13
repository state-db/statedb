FROM ghcr.io/navikt/mock-oauth2-server:2.1.10@sha256:65d4ed47ce094cc3849e41e03499bfb32372d69c726ec22b3d5a19eba9bc787e

COPY workbench/pulumi/mock-oauth2-login.html /app/workbench-login.html
