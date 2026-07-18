FROM ghcr.io/navikt/mock-oauth2-server:2.1.10

COPY workbench/pulumi/mock-oauth2-login.html /app/workbench-login.html
