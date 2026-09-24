# Alfresco Elasticsearch Batch Indexing :: Distribution

Packages the [`alfresco-elasticsearch-batch-indexing`](../alfresco-elasticsearch-batch-indexing/README.md)
Spring Boot app as a Docker image.

## Image

`alfresco/alfresco-elasticsearch-batch-indexing:<version>`

It runs the batch-indexing fat jar with the default JVM entrypoint. The
PostgreSQL JDBC driver is bundled (matches the re-indexing app), so no
runtime mount is needed for the default community deployment:

```bash
docker run --rm \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/alfresco \
  -e SPRING_DATASOURCE_USERNAME=alfresco \
  -e SPRING_DATASOURCE_PASSWORD=alfresco \
  -e SPRING_ELASTICSEARCH_URIS=http://elasticsearch:9200 \
  alfresco/alfresco-elasticsearch-batch-indexing:<version>
```

For Oracle or SQL Server, drop the driver jar into `/opt/db-drivers` —
`PropertiesLauncher` picks it up via the manifest's `Loader-Path:
db-drivers/` entry:

```bash
docker run --rm \
  -v /path/to/ojdbc11.jar:/opt/db-drivers/ojdbc.jar \
  -e SPRING_DATASOURCE_URL=jdbc:oracle:thin:@oracle:1521/XEPDB1 \
  ... \
  alfresco/alfresco-elasticsearch-batch-indexing:<version>
```

## Recommended production configuration

- **Database user (`SPRING_DATASOURCE_USERNAME`)**: grant `SELECT` only on the Alfresco schema. The indexer never issues `INSERT` / `UPDATE` / `DELETE` against the repository DB, so a SELECT-only role bounds the blast radius of a future regression.
- **Elasticsearch role**: `write` on the `alfresco*` indices and `write` on `alfresco-reindex-state`, no cluster / admin privileges. The watermark document is the singleton lock between competing instances — restricting write to the indexer prevents an accidental cursor roll-forward that would leave rows un-indexed.

## Push policy

The image is published to Docker Hub by CI.
