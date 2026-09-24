-- Runs after alfresco-dump.sql (alphabetical order in /docker-entrypoint-initdb.d/).
-- Resyncs every public.${table}_seq past MAX(${table}.id).
--
-- The dump file restores rows with explicit ids (e.g. a sentinel node with
-- id=12147483647 in alf_node) but its setval calls only reflect the
-- "regular" id ranges. perf-populate then reads nextval(), gets a value
-- well below the sentinel ids in the table, and its first COPY conflicts
-- with the existing rows.
--
-- This script targets the convention only — sequences named ${table}_seq
-- where table.id exists. Anything that doesn't fit the convention is left
-- alone.

DO $$
DECLARE
    seq_record RECORD;
    tbl_name text;
    max_id bigint;
    new_value bigint;
BEGIN
    FOR seq_record IN
        SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public'
    LOOP
        tbl_name := regexp_replace(seq_record.sequence_name, '_seq$', '');

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = tbl_name
              AND column_name = 'id'
        ) THEN
            EXECUTE format('SELECT COALESCE(MAX(id), 0) FROM public.%I', tbl_name) INTO max_id;
            new_value := GREATEST(max_id + 1, 1);
            EXECUTE format('SELECT setval(%L, %s, false)', 'public.' || seq_record.sequence_name, new_value);
            RAISE NOTICE 'Bumped % to % (table % MAX(id)=%)',
                seq_record.sequence_name, new_value, tbl_name, max_id;
        END IF;
    END LOOP;
END$$;
