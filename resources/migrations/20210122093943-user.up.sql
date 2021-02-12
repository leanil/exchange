CREATE TABLE exchange_user
(
    id        SERIAL PRIMARY KEY,
    user_name TEXT NOT NULL UNIQUE,
    token     TEXT NOT NULL UNIQUE
);
--;;
CREATE
INDEX token_idx ON exchange_user (token);
