-- FrontRow core schema (spec §4, §5; ADR-001).
-- No column defaults to now(): time comes from the application's injected Clock.

CREATE TABLE venue (
    id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL
);

CREATE TABLE seat (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id    bigint NOT NULL,
    section     text   NOT NULL,
    row_label   text   NOT NULL,
    seat_number int    NOT NULL,
    CONSTRAINT fk_seat_venue FOREIGN KEY (venue_id) REFERENCES venue (id),
    CONSTRAINT uq_seat_position UNIQUE (venue_id, section, row_label, seat_number),
    CONSTRAINT uq_seat_venue UNIQUE (id, venue_id)
);

CREATE TABLE event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id       bigint      NOT NULL,
    name           text        NOT NULL,
    starts_at      timestamptz NOT NULL,
    sales_open_at  timestamptz NOT NULL,
    sales_close_at timestamptz NOT NULL,
    status         text        NOT NULL,
    currency       char(3)     NOT NULL,
    CONSTRAINT fk_event_venue FOREIGN KEY (venue_id) REFERENCES venue (id),
    CONSTRAINT uq_event_venue UNIQUE (id, venue_id),
    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'ON_SALE', 'CANCELLED')),
    CONSTRAINT ck_event_sales_window CHECK (sales_open_at < sales_close_at),
    CONSTRAINT ck_event_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE event_seat (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    bigint NOT NULL,
    seat_id     bigint NOT NULL,
    venue_id    bigint NOT NULL,
    price_cents bigint NOT NULL,
    CONSTRAINT uq_event_seat UNIQUE (event_id, seat_id),
    CONSTRAINT fk_event_seat_event_venue FOREIGN KEY (event_id, venue_id) REFERENCES event (id, venue_id),
    CONSTRAINT fk_event_seat_seat_venue FOREIGN KEY (seat_id, venue_id) REFERENCES seat (id, venue_id),
    CONSTRAINT ck_event_seat_price CHECK (price_cents >= 0)
);

CREATE TABLE seat_hold (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_seat_id bigint      NOT NULL,
    hold_group_id uuid        NOT NULL,
    owner         text        NOT NULL,
    status        text        NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT fk_seat_hold_event_seat FOREIGN KEY (event_seat_id) REFERENCES event_seat (id),
    CONSTRAINT ck_seat_hold_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'RELEASED', 'CONVERTED'))
);

-- The invariant (ADR-001): a seat is claimed by at most one hold that is live-pending or sold.
CREATE UNIQUE INDEX uq_claimed_seat
    ON seat_hold (event_seat_id) WHERE status IN ('ACTIVE', 'CONVERTED');

-- Inserted first with (owner, idempotency_key, request_hash, created_at from the Clock);
-- hold_group_id and response_json are filled in at commit (spec §5).
CREATE TABLE hold_request (
    owner           text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    hold_group_id   uuid,
    response_json   jsonb,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_hold_request PRIMARY KEY (owner, idempotency_key)
);

CREATE TABLE ticket_order (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      bigint      NOT NULL,
    hold_group_id uuid        NOT NULL,
    owner         text        NOT NULL,
    status        text        NOT NULL,
    total_cents   bigint      NOT NULL,
    currency      char(3)     NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT fk_ticket_order_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT uq_order_hold_group UNIQUE (hold_group_id),
    CONSTRAINT ck_ticket_order_status CHECK (status IN ('CONFIRMED')),
    CONSTRAINT ck_ticket_order_total CHECK (total_cents >= 0),
    CONSTRAINT ck_ticket_order_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE order_line (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      bigint NOT NULL,
    event_seat_id bigint NOT NULL,
    price_cents   bigint NOT NULL,
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES ticket_order (id),
    CONSTRAINT fk_order_line_event_seat FOREIGN KEY (event_seat_id) REFERENCES event_seat (id),
    -- Defence in depth (ADR-001): a seat appears on at most one order line.
    CONSTRAINT uq_sold_once UNIQUE (event_seat_id),
    CONSTRAINT ck_order_line_price CHECK (price_cents >= 0)
);
