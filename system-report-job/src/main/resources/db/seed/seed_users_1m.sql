CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (id, username, email, full_name, phone_number, address, gender, dob, description, status)
SELECT
    gen_random_uuid(),
    'user' || gs,
    'user' || gs || '@example.com',
    'Mock User ' || gs,
    '09' || lpad((floor(random() * 100000000))::text, 8, '0'),
    'Address ' || gs || ', District ' || (1 + floor(random() * 24))::int || ', HCMC',
    (ARRAY['MALE', 'FEMALE', 'OTHER'])[1 + floor(random() * 3)],
    date '1970-01-01' + (floor(random() * 18250))::int,
    'Mock user #' || gs || ' generated for load testing',
    (ARRAY['ACTIVE', 'INACTIVE', 'DRAFT', 'LOCKED'])[1 + floor(random() * 4)]
FROM generate_series(1, 1000000) AS gs;
