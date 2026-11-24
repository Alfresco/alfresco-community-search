# Reindex writer configuration

Each reindex / batch-indexing chunk is written to Elasticsearch by up to three logical writers: **metadata**,
**path** and **content**. The writer strategy decides how those writers run for every chunk.

## Selecting a strategy

```properties
alfresco.reindex.writerMode=sequential   # sequential | concurrent | merged
```

| Strategy     | What runs per chunk                                                                                                                                                  |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sequential` | Metadata, path and content writers run one after another. Historical behaviour.                                                                                      |
| `concurrent` | Metadata, path and content writers run in parallel on a dedicated pool (`writerConcurrency`). The path bulk is a scripted upsert unless `pathUpsertEnabled=false`. |
| `merged`     | Metadata and path are folded into one scripted-upsert bulk (one Elasticsearch round-trip instead of two); content stays a separate writer.                           |

`merged` needs both metadata and path indexing enabled; when either is disabled the per-writer toggles are honoured
and the strategy behaves as `sequential`.

### Legacy toggles

`alfresco.reindex.writersConcurrentEnabled` and `alfresco.reindex.writersMergedEnabled` are still honoured, but only
when `writerMode` is unset. Precedence: `writerMode` > `writersMergedEnabled` > `writersConcurrentEnabled` > `sequential`.

## Edition support

| Strategy     | Community (batch-indexing)  | Enterprise (reindexing) |
|--------------|-----------------------------|-------------------------|
| `sequential` | Supported (default)         | Supported               |
| `concurrent` | Falls back to `sequential`  | Supported (default)     |
| `merged`     | Falls back to `sequential`  | Supported               |

Requesting a strategy the edition does not support, through `writerMode` or the legacy toggles, never fails startup
or a job. One warning is logged at startup and the job runs sequentially:

```text
WARN  Reindex writer mode MERGED is not supported in this edition (supported: [SEQUENTIAL]); falling back to SEQUENTIAL. See docs/reindex-writer-configuration.md.
```

A supported strategy logs the strategy in use instead:

```text
INFO  Reindex writer mode: CONCURRENT
```

The startup `Settings:` line also reports `writerMode`, `requestedWriterMode` and `effectiveWriterMode`.

The fallback is resolved once, in `ReindexingCoreProperties#getEffectiveWriterMode()`. Writer composition, path upsert
and the write metrics / timing summaries all read the resolved strategy, so they always describe what actually ran.
The supported set comes from `ReindexingCoreProperties#supportedWriterModes()`: Community returns `sequential` only
and Enterprise widens it in its properties subclass. It is not a configuration property, so it cannot be changed from
configuration.

## Related properties

| Property                                         | Applies to   | Default | Description                                                                                    |
|--------------------------------------------------|--------------|---------|------------------------------------------------------------------------------------------------|
| `alfresco.reindex.writerConcurrency`             | `concurrent` | `6`     | Size of the dedicated pool backing the concurrent writers.                                     |
| `alfresco.reindex.writerJoinTimeoutSeconds`      | `concurrent` | `0`     | Per-chunk upper bound to wait for a writer task; `0` waits indefinitely.                       |
| `alfresco.reindex.pathUpsertEnabled`             | `concurrent` | `true`  | Writes the path bulk as a scripted upsert so it cannot fail with `document_missing_exception`. |
| `alfresco.reindex.writerTimingSummaryLogEnabled` | all          | `true`  | Logs a per-writer timing summary at the end of each step.                                      |
| `alfresco.reindex.failureSummaryLogEnabled`      | all          | `true`  | Logs a per-writer roll-up of terminal bulk-item failures at the end of each step.              |

## Merged writer failures

The merged writer reports terminal per-item failures through `MergedBulkItemFailureListener`. Community
batch-indexing registers a dead-letter listener for it (stage `MERGED_WRITER`), which stays dormant while `merged` is
not supported there. Enterprise reindexing registers no listener: failures are included in the failure summary and,
with `alfresco.reindex.failOnTerminalWriteErrors=true`, fail the chunk.
