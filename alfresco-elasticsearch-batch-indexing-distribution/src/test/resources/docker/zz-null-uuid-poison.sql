-- Runs after alfresco-dump.sql (alphabetical order in /docker-entrypoint-initdb.d/).
--
-- Drops the NOT NULL constraint on alf_node.uuid and nulls out one node's uuid
-- so the JDBC row mapper's `requiredString("uuid")` throws SQLException when the
-- reader hits the row. Because the reindexing reader queries a whole page in
-- one JdbcTemplate.query call, a NULL uuid on any row in the page fails the
-- entire page — reproducing the "one bad row blocks the window" gap the poison
-- row 1 test targets.
--
-- Node 384 is the same node used by zz-missing-tag-poison.sql so it's a
-- well-known target across the poison IT suite.

ALTER TABLE public.alf_node ALTER COLUMN uuid DROP NOT NULL;

UPDATE public.alf_node SET uuid = NULL WHERE id = 384;
