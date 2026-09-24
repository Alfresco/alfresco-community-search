-- Runs after alfresco-dump.sql (alphabetical order in /docker-entrypoint-initdb.d/).
--
-- Leaves the DB with the full Alfresco schema but no node / transaction /
-- content rows. Used by BothIndicesEmptyFreshBootstrapIT to prove that the
-- indexer starts cleanly against an empty repository.
--
-- CASCADE handles FK descendants (alf_node_properties, alf_node_aspects,
-- alf_child_assoc, alf_node_assoc, alf_store etc. via alf_node.transaction_id
-- and alf_store.root_node_id). RESTART IDENTITY resets sequences so any data
-- the test adds later starts at 1.

TRUNCATE TABLE public.alf_transaction, public.alf_node
    RESTART IDENTITY CASCADE;

TRUNCATE TABLE public.alf_content_data, public.alf_content_url
    RESTART IDENTITY CASCADE;
