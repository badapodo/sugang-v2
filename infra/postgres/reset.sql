\set ON_ERROR_STOP on
BEGIN;

TRUNCATE TABLE enrollment RESTART IDENTITY;
UPDATE course
SET current_count = 0,
    version = 0;

COMMIT;
