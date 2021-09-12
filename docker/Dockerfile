FROM k8s.gcr.io/kustomize/kustomize:v3.8.7 AS kustomize

FROM openjdk:16-slim-buster

COPY --from=kustomize /app/kustomize /usr/local/bin
