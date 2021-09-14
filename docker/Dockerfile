FROM k8s.gcr.io/kustomize/kustomize:v3.8.7 AS kustomize

FROM alpine:3.14.2 as kubectl
RUN apk add curl \
    && curl -LO https://dl.k8s.io/release/v1.20.10/bin/linux/amd64/kubectl \
    && curl -LO "https://dl.k8s.io/v.1.20.10/bin/linux/amd64/kubectl.sha256" \
    && echo "$(<kubectl.sha256) kubectl" | sha256sum \
    && chmod +x kubectl

FROM openjdk:16-buster

COPY --from=kustomize /app/kustomize /usr/local/bin
COPY --from=kubectl kubectl /usr/local/bin
