\set ON_ERROR_STOP on
BEGIN;

TRUNCATE TABLE enrollment RESTART IDENTITY;
UPDATE course
SET current_count = 0,
    version = 0;

\copy enrollment (id, student_id, course_id, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/enrollment.csv' WITH (FORMAT csv, HEADER true, NULL '\N');

UPDATE course AS target
SET current_count = seeded.enrollment_count
FROM (
    SELECT course_id, COUNT(*)::INTEGER AS enrollment_count
    FROM enrollment
    GROUP BY course_id
) AS seeded
WHERE target.id = seeded.course_id;

SELECT setval(
    pg_get_serial_sequence('enrollment', 'id'),
    COALESCE(MAX(id), 0) + 1,
    false
)
FROM enrollment;

COMMIT;
