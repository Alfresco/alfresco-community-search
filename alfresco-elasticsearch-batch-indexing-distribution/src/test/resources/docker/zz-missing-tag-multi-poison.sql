-- Runs after alfresco-dump.sql (alphabetical order in /docker-entrypoint-initdb.d/).
--
-- Two poison cm:taggable rows on two different nodes (384 and 385). Same shape
-- as zz-missing-tag-poison.sql — the tag UUIDs don't resolve, so AddTagsPostProcessor
-- throws. Used by SkipLimitExceededIT to confirm Spring Batch's step-level skipLimit
-- trips a step FAILED once the poison count exceeds the cap.
--
-- Both nodes already carry valid cm:taggable values in the dump (node 384 at
-- list_index 0 and 1, node 385 at list_index 0). Poison rows sit at unused
-- list_index positions so they don't collide with the existing data.

INSERT INTO public.alf_node_properties
    (node_id, actual_type_n, persisted_type_n, boolean_value, long_value,
     float_value, double_value, string_value, serializable_value,
     qname_id, list_index, locale_id)
VALUES
    (384, 12, 6, false, 0, 0, 0,
     'workspace://SpacesStore/deadc0de-c0de-c0de-c0de-c0dec0dec0de', NULL,
     154, 2, 1),
    (385, 12, 6, false, 0, 0, 0,
     'workspace://SpacesStore/beefbeef-beef-beef-beef-beefbeefbeef', NULL,
     154, 1, 1);
