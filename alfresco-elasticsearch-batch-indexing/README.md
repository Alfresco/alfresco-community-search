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
