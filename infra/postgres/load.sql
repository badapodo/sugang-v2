\set ON_ERROR_STOP on
BEGIN;

\copy department (id, name, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/department.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy member (member_id, email, password, name, role, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/member.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy course (id, title, capacity, current_count, version, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/course.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy student (student_id, department_id, member_id, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/student.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy course_time (id, course_id, day_of_week, start_time, end_time, location, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/course_time.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy prerequisite (id, course_id, pre_course_id, department_id, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/prerequisite.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy completed_course (id, student_id, course_id, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/completed_course.csv' WITH (FORMAT csv, HEADER true, NULL '\N');
\copy enrollment (id, student_id, course_id, created_by, created_date, last_modified_by, last_modified_date) FROM 'mock/sugang-mock/output/csv/enrollment.csv' WITH (FORMAT csv, HEADER true, NULL '\N');

SELECT setval(pg_get_serial_sequence('department', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM department;
SELECT setval(pg_get_serial_sequence('member', 'member_id'), COALESCE(MAX(member_id), 0) + 1, false) FROM member;
SELECT setval(pg_get_serial_sequence('course', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM course;
SELECT setval(pg_get_serial_sequence('student', 'student_id'), COALESCE(MAX(student_id), 0) + 1, false) FROM student;
SELECT setval(pg_get_serial_sequence('course_time', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM course_time;
SELECT setval(pg_get_serial_sequence('prerequisite', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM prerequisite;
SELECT setval(pg_get_serial_sequence('completed_course', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM completed_course;
SELECT setval(pg_get_serial_sequence('enrollment', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM enrollment;

COMMIT;
