FROM golang:1.22-alpine AS builder

WORKDIR /src
COPY backend/telefam/company/go.mod ./go.mod
COPY backend/telefam/company/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o /out/telefam-backend ./cmd/server

FROM alpine:3.20

RUN addgroup -S telefam && adduser -S -G telefam telefam
COPY --from=builder /out/telefam-backend /usr/local/bin/telefam-backend

USER telefam
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/telefam-backend"]
