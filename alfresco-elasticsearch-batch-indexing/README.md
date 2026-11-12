# Alfresco Elasticsearch Batch Indexing

Standalone Spring Boot application that continuously reindexes Alfresco content into Elasticsearch
without requiring JMS/ActiveMQ. Intended as a search indexing solution for ACS Community Edition.

See [Consolidated Design Document](../docs/architecture/consolidated-design-document.md) for the full
architecture and configuration reference.

**All commands below must be run from the repository root (`alfresco-elasticsearch-connector/`).**

## Running locally

The application needs a running Alfresco environment (PostgreSQL with Alfresco schema,
Elasticsearch, and optionally Transform Core AIO for content indexing).

### 1. Start infrastructure services

```bash
docker compose -f alfresco-elasticsearch-batch-indexing/src/test/resources/compose-test.yaml up -d
```

Wait until the services are healthy:

```bash
docker compose -f alfresco-elasticsearch-batch-indexing/src/test/resources/compose-test.yaml ps
```

### 2. Build and run the application

```bash
mvn clean install -DskipTests -pl alfresco-elasticsearch-batch-indexing -am
```

The PostgreSQL JDBC driver is bundled in the fat jar, so the default community deployment
needs no extra setup:

```bash
(cd alfresco-elasticsearch-batch-indexing/target && java -jar alfresco-elasticsearch-batch-indexing-*-app.jar)
```

For Oracle or SQL Server, drop the driver jar into a `db-drivers/` directory next to the jar.
The fat jar uses `PropertiesLauncher` with `Loader-Path: db-drivers/` (see the
[root README](../README.md#database-driver-configuration) for details).

Override default properties via environment variables or command-line flags, e.g.
`--spring.datasource.url=jdbc:postgresql://myhost:5432/alfresco`.

### 3. Stop infrastructure

```bash
docker compose -f alfresco-elasticsearch-batch-indexing/src/test/resources/compose-test.yaml down
```

## Content indexing filter

Excludes content from content indexing by MIME type and by size. Metadata and path indexing are
unaffected: an excluded node stays searchable by its properties and its path, only its text is not
indexed.

| Property | Default | Meaning |
|---|---|---|
| `alfresco.content-indexing.filter.excluded-mime-types` | empty | Comma-separated MIME types to exclude. Empty excludes nothing. |
| `alfresco.content-indexing.filter.max-content-size` | `0` | Maximum size to index, as a Spring `DataSize`. `0` means no limit. |

Matching is case-insensitive, and a `type/*` entry excludes every subtype, so `video/*` covers
`video/mp4`. `max-content-size` takes a unit (`50MB`, `1GB`; `KB`/`MB`/`GB`/`TB` are binary, so
1MB is 1048576 bytes) and a bare number is read as bytes. Content strictly larger than the limit is
skipped.

```properties
alfresco.content-indexing.filter.excluded-mime-types=application/zip,video/*
alfresco.content-indexing.filter.max-content-size=50MB
```

Excluded content is not fetched from the repository and not sent to Transform Core.

### Content already indexed

Content indexed before a node became excluded is removed by the content stage, which runs only when
`alfresco.reindex.contentIndexingEnabled=true`. With content indexing switched off, adding an
exclusion stops new content being indexed but leaves existing content in the index.

Live indexing behaves differently: it removes already-indexed content from the metadata module, so
it applies whether or not content indexing is enabled.

## Running tests

This module depends on `alfresco-search-reindexing-core` and other sibling modules that are not
published to a remote repository during development. The first command installs them into the local
Maven repository so that the second command can resolve them when running only the batch-indexing
module's tests.

### Unit and integration tests

Runs all unit tests (Mockito-based, no containers) and Testcontainers-based integration tests.
Containers are started automatically by Testcontainers — no manual Docker Compose setup needed.

```bash
mvn clean install -DskipTests -pl alfresco-elasticsearch-batch-indexing -am && \
mvn verify -pl alfresco-elasticsearch-batch-indexing
```

### Docker tests

End-to-end tests that build a Docker image of the application and run the full stack
(PostgreSQL, Elasticsearch, Transform Core, batch-indexing) inside a Docker Compose network.

```bash
mvn clean package -DskipTests -pl alfresco-elasticsearch-batch-indexing -am && \
mvn verify -pl alfresco-elasticsearch-batch-indexing -P docker-compose-test
```
