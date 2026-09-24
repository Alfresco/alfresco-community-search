-- Runs after alfresco-dump.sql (alphabetical order in /docker-entrypoint-initdb.d/).
--
-- Adds a poison cm:taggable value on node 384 (uuid d1ddf3fc-...) pointing at
-- a nodeRef that is NOT under tag:tag-root. The AddTagsPostProcessor's tag
-- lookup returns empty, and with alfresco.reindex.failOnMissingTag=true (the
-- shipping default in both reindexing and batch-indexing) it throws
-- ElasticsearchUnexpectedException. Batch-indexing catches this via Spring
-- Batch skip and DlqSkipListener routes it to the DLQ.
--
-- Mirrors the "category-as-tag" scenario reported from a customer environment
-- where ACS Share allowed cm:categories to be attached as cm:taggable values,
-- leaving orphan tag references that the reindexer couldn't resolve.
--
-- Node 384 already carries two valid cm:taggable values (list_index 0 and 1);
-- this row is list_index=2 so it doesn't collide, and one poison row is enough
-- to fail processing of node 384 on every attempt.

INSERT INTO public.alf_node_properties
    (node_id, actual_type_n, persisted_type_n, boolean_value, long_value,
     float_value, double_value, string_value, serializable_value,
     qname_id, list_index, locale_id)
VALUES
    (384, 12, 6, false, 0, 0, 0,
     'workspace://SpacesStore/deadc0de-c0de-c0de-c0de-c0dec0dec0de', NULL,
     154, 2, 1);
