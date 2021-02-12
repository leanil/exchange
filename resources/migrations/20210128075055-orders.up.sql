CREATE TYPE order_state AS ENUM ('LIVE', 'FULFILLED', 'CANCELLED');
--;;
CREATE TYPE order_type AS ENUM ('BUY', 'SELL');
--;;
CREATE TABLE exchange_order
(
    id              SERIAL PRIMARY KEY,
    user_id         INTEGER     NOT NULL REFERENCES exchange_user (id),
    state           order_state NOT NULL,
    amount          INTEGER     NOT NULL,
    type            order_type  NOT NULL,
    price           INTEGER     NOT NULL,
    url             TEXT,
    original_amount INTEGER     NOT NULL,
    usd_amount      INTEGER     NOT NULL DEFAULT 0
);
--;;
CREATE INDEX live_orders_idx ON exchange_order (user_id, state, type);