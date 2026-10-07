/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
 * %%
 * Copyright (C) 2026 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.elasticsearch.batchindexing.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One record per give-up event the indexer has surfaced for operator visibility.
 * <p>
 * Most records are keyed by {@code dbId} so repeated failures for the same node upsert into a single document and {@code failureCount} captures retry noise without exploding storage. A reader-side failure takes whichever shape its page had: a by-date page is keyed by the failed time window (id shape {@code window:<tsFrom>-<tsTo>}) with a null {@code dbId}, while an id-shaped page records one dbId-keyed document per id.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeadLetterDocument
{
    /** Bump when the document structure changes so readers can migrate or reject incompatible documents. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Stage of the pipeline that produced the skip event. */
    public enum FailureStage
    {
        /** Reader-side failure. Item never materialised — the whole page failed before a row could be mapped. Produced by {@code DlqPageReadFailureListener}, wired from reindexing's {@code AbstractAlfrescoNodeReader} when a JDBC page throws (e.g. malformed row). A by-date page is recorded as one document keyed by the failed time window (id shape {@code window:<tsFrom>-<tsTo>}) with a null {@code dbId}; an id-shaped page, as used by the DLQ drain, is recorded as one document per id so the drain's cleanup sees each entry's failureCount rise. Not produced through Spring Batch's {@code SkipListener#onSkipInRead}: the paging reader would re-read the same failed page indefinitely rather than advance. */
        READER,
        /** {@link org.springframework.batch.core.listener.SkipListener#onSkipInProcess}. Item is the reader's output. */
        PROCESSOR,
        /** {@link org.springframework.batch.core.listener.SkipListener#onSkipInWrite}. Item is the processor's output. Sub-writer is not distinguished because the composite writer swallows that context. Covers the metadata bulk writer's per-item failures too. */
        METADATA_WRITER,
        /** Emitted directly by {@code ElasticsearchPathWriter} after its internal retry loop; never fires through Spring Batch skip. Reserved for {@link org.alfresco.reindexing.writers.PathBulkItemFailureListener} bridges. */
        PATH_WRITER,
        /** Emitted directly by {@code ElasticsearchMergedReindexWriter} after its internal retry loop when the merged metadata+path scripted-upsert bulk fails for an item; never fires through Spring Batch skip. Populated by the {@link org.alfresco.reindexing.writers.MergedBulkItemFailureListener} bridge. Kept distinct from {@code METADATA_WRITER}/{@code PATH_WRITER} because the merged writer folds both writes into a single bulk, so a failure cannot be attributed to metadata or path alone. */
        MERGED_WRITER,
        /** Emitted directly by {@code ContentTransformDirectWriter} when the ACS transform endpoint returns a non-retryable error (e.g. 4xx). Never fires through Spring Batch skip; the writer swallows the failure by design and this stage is populated by the {@code ContentTransformFailureListener} bridge. */
        CONTENT_WRITER
    }

    @JsonProperty("schemaVersion")
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** Alfresco node database id. Matches {@code AlfrescoNode.getId()}. Populated for processor and writer skips, and for a reader failure on an id-shaped page; null only for a window-keyed reader failure. */
    @JsonProperty("dbId")
    private Long dbId;

    /** Alfresco node reference (`workspace://SpacesStore/...`). Best-effort; null when not derivable from the item. */
    @JsonProperty("nodeRef")
    private String nodeRef;

    /** Approximate ACS commit time of the node when it was seen. Best-effort. */
    @JsonProperty("sourceCommitTimeMs")
    private Long sourceCommitTimeMs;

    /** ISO-8601 timestamp of the first give-up event for this dbId. Never rewritten. */
    @JsonProperty("firstFailedAt")
    private String firstFailedAt;

    /** ISO-8601 timestamp of the most recent give-up event for this dbId. */
    @JsonProperty("lastFailedAt")
    private String lastFailedAt;

    /** How many times we've given up on this dbId (across all overlap-cycle attempts). */
    @JsonProperty("failureCount")
    private int failureCount;

    @JsonProperty("failureStage")
    private FailureStage failureStage;

    /** Short classifier taken from the exception's simple class name. */
    @JsonProperty("failureType")
    private String failureType;

    /** Truncated {@code toString()} of the exception message. Not a stack trace. */
    @JsonProperty("failureReason")
    private String failureReason;
}
