CREATE TABLE users
(
    id        SERIAL PRIMARY KEY,
    user_name TEXT NOT NULL UNIQUE,
    token     TEXT NOT NULL UNIQUE
);
--;;
CREATE
INDEX token_idx ON users (token);
