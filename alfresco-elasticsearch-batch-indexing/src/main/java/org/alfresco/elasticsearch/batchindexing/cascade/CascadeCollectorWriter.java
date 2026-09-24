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
package org.alfresco.elasticsearch.batchindexing.cascade;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.CM_CATEGORY;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.NAME;
import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.TYPE_DELETED;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.repo.event.v1.model.NodeResource;

/**
 * Observer {@link ItemWriter} that scans events for cascade signals and stashes enriched sources for the post-cycle sweep. Must run <em>first</em> in the composite writer so mgets capture the ES doc before it is overwritten.
 * <p>
 * Detects renames (non-delete event with {@code sys:cascadeTx}) and category deletes (delete event whose pre-delete ES doc has {@code TYPE == cm:category} — the event's own type is unreliable, ACS flips it to {@code sys:deleted}).
 */
@SuppressWarnings("PMD.GodClass")
public class CascadeCollectorWriter implements ItemWriter<ReindexingEvent>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CascadeCollectorWriter.class);

    static final String SYS_CASCADE_TX = "sys:cascadeTx";

    private static final String NODEREF_PREFIX_END = "/";

    private final CascadeSourceStash stash;

    private final SourceDocLookup sourceDocLookup;

    public CascadeCollectorWriter(CascadeSourceStash stash, SourceDocLookup sourceDocLookup)
    {
        this.stash = stash;
        this.sourceDocLookup = sourceDocLookup;
    }

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
    public void write(Chunk<? extends ReindexingEvent> chunk)
    {
        List<PendingRename> pendingRenames = new ArrayList<>();
        List<String> pendingDeleteRefs = new ArrayList<>();

        for (ReindexingEvent event : chunk)
        {
            if (event == null || event.getData() == null)
            {
                continue;
            }
            NodeResource nr = (NodeResource) event.getData().getResource();
            if (nr == null || nr.getId() == null)
            {
                continue;
            }
            if (TYPE_DELETED.equals(nr.getNodeType()))
            {
                // Original type is unavailable on the event (workspace tombstone type = sys:deleted).
                // Defer detection to the mget below which reads the still-present ES doc's TYPE.
                pendingDeleteRefs.add(nr.getId());
                continue;
            }
            Map<String, Serializable> properties = nr.getProperties();
            if (properties == null || properties.get(SYS_CASCADE_TX) == null)
            {
                continue;
            }
            String newName = resolveNewName(nr, properties);
            if (newName == null || newName.isBlank())
            {
                continue;
            }
            pendingRenames.add(new PendingRename(nr.getId(), newName, properties.get(SYS_CASCADE_TX)));
        }

        Map<String, SourceDocLookup.SourceDocSnapshot> snapshots = mgetAll(pendingRenames, pendingDeleteRefs);
        int enrichedRenames = stashRenames(pendingRenames, snapshots);
        int categoryDeletes = stashCategoryDeletes(pendingDeleteRefs, snapshots);

        if ((enrichedRenames > 0 || categoryDeletes > 0) && LOGGER.isInfoEnabled())
        {
            LOGGER.info("Cascade observer: chunk contributed {} rename candidate(s), {} category-delete candidate(s) "
                    + "(cumulative this cycle: renames={}, categoryDeletes={})",
                    enrichedRenames, categoryDeletes, stash.renameCount(), stash.categoryDeleteCount());
        }
    }

    private Map<String, SourceDocLookup.SourceDocSnapshot> mgetAll(List<PendingRename> pendingRenames,
            List<String> pendingDeleteRefs)
    {
        if (pendingRenames.isEmpty() && pendingDeleteRefs.isEmpty())
        {
            return Map.of();
        }
        Set<String> allRefs = new LinkedHashSet<>();
        Stream.concat(pendingRenames.stream().map(PendingRename::nodeRef), pendingDeleteRefs.stream())
                .forEach(allRefs::add);
        return sourceDocLookup.lookup(allRefs);
    }

    private int stashCategoryDeletes(List<String> pendingDeleteRefs,
            Map<String, SourceDocLookup.SourceDocSnapshot> snapshots)
    {
        int count = 0;
        for (String ref : pendingDeleteRefs)
        {
            SourceDocLookup.SourceDocSnapshot snapshot = snapshots.get(ref);
            if (snapshot == null)
            {
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Cascade observer: delete candidate has no ES source doc (already deleted or "
                            + "never indexed), skipping category-delete detection nodeRef={}", ref);
                }
                continue;
            }
            if (!CM_CATEGORY.equals(snapshot.type()))
            {
                continue;
            }
            stash.recordCategoryDelete(new CategoryDeleteCascadeSource(ref, extractUuid(ref),
                    snapshot.paths(), snapshot.unprefixedPaths()));
            count++;
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Cascade observer: category-delete candidate nodeRef={} (pre-delete TYPE={})",
                        ref, snapshot.type());
            }
        }
        return count;
    }

    private int stashRenames(List<PendingRename> pendingRenames,
            Map<String, SourceDocLookup.SourceDocSnapshot> snapshots)
    {
        if (pendingRenames.isEmpty())
        {
            return 0;
        }
        int enriched = 0;
        for (PendingRename pending : pendingRenames)
        {
            SourceDocLookup.SourceDocSnapshot snapshot = snapshots.get(pending.nodeRef());
            if (snapshot == null)
            {
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Cascade observer: rename candidate has no ES source doc yet, skipping enrichment nodeRef={}",
                            pending.nodeRef());
                }
                continue;
            }
            RenameCascadeSource source = buildSource(pending, snapshot);
            if (source == null)
            {
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Cascade observer: rename candidate has incomplete source-doc segments, skipping nodeRef={}",
                            pending.nodeRef());
                }
                continue;
            }
            if (source.isNoop())
            {
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Cascade observer: rename candidate is a no-op (name unchanged), skipping nodeRef={}",
                            pending.nodeRef());
                }
                continue;
            }
            stash.recordRename(source);
            enriched++;
            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Cascade observer: rename candidate nodeRef={} '{}'->'{}' sys:cascadeTx={}",
                        source.nodeRef(), source.oldPrefixedSegment(), source.newPrefixedSegment(), pending.cascadeTx());
            }
        }
        return enriched;
    }

    private static String resolveNewName(NodeResource nr, Map<String, Serializable> properties)
    {
        if (nr.getName() != null && !nr.getName().isBlank())
        {
            return nr.getName();
        }
        // NodeResource.name is null when the batch processor didn't populate it (e.g. cm:name
        // was absent from the property map); fall back to the raw property for safety.
        Object raw = properties.get(NAME);
        return raw == null ? null : String.valueOf(raw);
    }

    private static RenameCascadeSource buildSource(PendingRename pending, SourceDocLookup.SourceDocSnapshot snapshot)
    {
        String oldPrefixed = snapshot.prefixedSegment();
        String oldUnprefixed = snapshot.unprefixedSegment();
        if (oldPrefixed == null || oldUnprefixed == null)
        {
            return null;
        }
        String namespacePrefix = extractNamespacePrefix(oldPrefixed);
        String newUnprefixed = stripPrefix(pending.newName());
        String newPrefixed = namespacePrefix == null ? newUnprefixed : namespacePrefix + ":" + newUnprefixed;
        return new RenameCascadeSource(
                pending.nodeRef(),
                extractUuid(pending.nodeRef()),
                oldPrefixed,
                newPrefixed,
                oldUnprefixed,
                newUnprefixed);
    }

    private static String stripPrefix(String prefixedOrBare)
    {
        if (prefixedOrBare == null)
        {
            return null;
        }
        int idx = prefixedOrBare.indexOf(':');
        return idx < 0 ? prefixedOrBare : prefixedOrBare.substring(idx + 1);
    }

    private static String extractNamespacePrefix(String prefixedSegment)
    {
        if (prefixedSegment == null)
        {
            return null;
        }
        int idx = prefixedSegment.indexOf(':');
        return idx <= 0 ? null : prefixedSegment.substring(0, idx);
    }

    /** Extracts the bare UUID from an Alfresco nodeRef. */
    static String extractUuid(String nodeRef)
    {
        if (nodeRef == null)
        {
            return null;
        }
        int idx = nodeRef.lastIndexOf(NODEREF_PREFIX_END);
        return idx < 0 ? nodeRef : nodeRef.substring(idx + 1);
    }

    private record PendingRename(String nodeRef, String newName, Serializable cascadeTx)
    {}
}
