CREATE TABLE export_slot_free_range (
    profile TEXT NOT NULL CHECK (length(trim(profile)) > 0),
    artifact TEXT NOT NULL CHECK (length(trim(artifact)) > 0),
    range_start INTEGER NOT NULL CHECK (range_start > 0),
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    released_at_ms INTEGER NOT NULL CHECK (released_at_ms >= 0),
    PRIMARY KEY (profile, artifact, range_start)
);

INSERT INTO export_slot_free_range(
    profile, artifact, range_start, range_end, released_at_ms)
WITH ordered AS (
    SELECT profile,
           artifact,
           slot,
           released_at_ms,
           slot - ROW_NUMBER() OVER (
               PARTITION BY profile, artifact
               ORDER BY slot) AS range_group
    FROM export_slot_free
), coalesced AS (
    SELECT profile,
           artifact,
           MIN(slot) AS range_start,
           MAX(slot) AS range_end,
           MAX(released_at_ms) AS released_at_ms
    FROM ordered
    GROUP BY profile, artifact, range_group
)
SELECT profile, artifact, range_start, range_end, released_at_ms
FROM coalesced;

DROP TABLE export_slot_free;

CREATE INDEX ix_export_slot_free_range_end
ON export_slot_free_range(profile, artifact, range_end, range_start);
