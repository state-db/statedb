FROM golang:1.26-bookworm AS builder

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -o /out/verify .

# ---- runtime image ----
FROM debian:bookworm-slim

COPY --from=builder /out/verify /usr/local/bin/verify

# Default to staying alive so test harnesses can start one container and exec
# `verify` per assertion. One-shot use: `docker run -i <image> verify -` < d.json
CMD ["sleep", "infinity"]
