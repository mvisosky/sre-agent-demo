FROM mcr.microsoft.com/devcontainers/java:25

# Install Apache Maven and PostgreSQL client utilities
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends \
        maven \
        postgresql-client \
        curl \
        jq \
    && apt-get clean -y && rm -rf /var/lib/apt/lists/*
