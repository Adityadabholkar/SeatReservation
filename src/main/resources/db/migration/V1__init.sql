CREATE TABLE shows (
    id             UUID PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per seat. The primary key (show_id, label) makes every seat a unique
-- lockable row; the CHECK makes an "owned but unowned" seat impossible.
CREATE TABLE seats (
    show_id        UUID NOT NULL REFERENCES shows (id),
    label          TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'available'
                   CHECK (status IN ('available', 'held', 'confirmed')),
    user_id        TEXT,
    reservation_id UUID,
    PRIMARY KEY (show_id, label),
    CONSTRAINT seat_owner_consistent CHECK (
        (status = 'available' AND user_id IS NULL AND reservation_id IS NULL) OR
        (status <> 'available' AND user_id IS NOT NULL AND reservation_id IS NOT NULL)
    )
);
CREATE INDEX idx_seats_reservation ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- The idempotency key lives on the reservation itself: UNIQUE (user_id, idempotency_key)
-- is what makes "same key => exactly one reservation" a database guarantee.
CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    CONSTRAINT uq_reservation_idempotency UNIQUE (user_id, idempotency_key)
);

-- Which seats a reservation covers (kept after cancel so replays can still describe it).
CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations (id),
    label          TEXT NOT NULL,
    PRIMARY KEY (reservation_id, label)
);

-- Per-user, per-show seat counter. Its row lock serialises one user's concurrent requests.
CREATE TABLE user_show_counts (
    show_id    UUID NOT NULL REFERENCES shows (id),
    user_id    TEXT NOT NULL,
    held_count INT  NOT NULL CHECK (held_count >= 0),
    PRIMARY KEY (show_id, user_id)
);
