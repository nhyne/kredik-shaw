FROM k8s.gcr.io/kustomize/kustomize:v4.3.0 AS kustomize

FROM alpine/helm:3.7.0 AS helm

FROM alpine:3.14.2 as kubectl
RUN apk add curl \
    && curl -LO https://dl.k8s.io/release/v1.20.10/bin/linux/amd64/kubectl \
    && curl -LO "https://dl.k8s.io/v.1.20.10/bin/linux/amd64/kubectl.sha256" \
    && echo "$(<kubectl.sha256) kubectl" | sha256sum \
    && chmod +x kubectl

FROM openjdk:16-buster

COPY --from=kustomize /app/kustomize /usr/local/bin
COPY --from=kubectl kubectl /usr/local/bin
COPY --from=helm /usr/bin/helm /usr/local/bin

ENV HELM_PLUGINS=/opt/plugins

RUN helm plugin install https://github.com/hypnoglow/helm-s3.git --version 0.10.0
