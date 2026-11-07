-- Run immediately after importing a metadata dump and BEFORE setting files.loaded=NULL.
-- Fail closed: only records explicitly marked isAdult=false are eligible for the general-audience index.
BEGIN;

CREATE TEMP TABLE reianix_excluded_anilist_ids ON COMMIT DROP AS
SELECT id
FROM anilist
WHERE lower(COALESCE(json->>'isAdult', '')) NOT IN ('false', '0', 'no');

DELETE FROM files
WHERE anilist_id IN (SELECT id FROM reianix_excluded_anilist_ids);

-- Remove orphaned video rows with no remaining AniList metadata.
DELETE FROM files AS f
WHERE NOT EXISTS (SELECT 1 FROM anilist AS a WHERE a.id = f.anilist_id);

DELETE FROM anilist
WHERE id IN (SELECT id FROM reianix_excluded_anilist_ids);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM files AS f
        JOIN anilist AS a ON a.id = f.anilist_id
        WHERE lower(COALESCE(a.json->>'isAdult', '')) NOT IN ('false', '0', 'no')
    ) THEN
        RAISE EXCEPTION 'Adult-tagged records remain in the ReiAnix visual index.';
    END IF;
END $$;

-- Keep the API's materialized AniList views consistent with the filtered base table.
REFRESH MATERIALIZED VIEW anilist_view;
REFRESH MATERIALIZED VIEW anilist_title;

COMMIT;
