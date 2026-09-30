CREATE TABLE purchase (
    id UUID PRIMARY KEY,
    raffle_id BIGINT NOT NULL REFERENCES raffle (id),
    buyer_name VARCHAR(160) NOT NULL,
    buyer_email VARCHAR(254) NOT NULL,
    buyer_phone VARCHAR(32) NOT NULL,
    total_cents INTEGER NOT NULL CHECK (total_cents > 0),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'FAILED', 'EXPIRED')),
    mercado_pago_order_id VARCHAR(64) UNIQUE,
    pix_copy_paste TEXT,
    pix_qr_code_base64 TEXT,
    payment_url TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_number (
    purchase_id UUID NOT NULL REFERENCES purchase (id),
    raffle_number_id BIGINT NOT NULL REFERENCES raffle_number (id),
    PRIMARY KEY (purchase_id, raffle_number_id),
    UNIQUE (raffle_number_id)
);

ALTER TABLE raffle_number
    ADD COLUMN reserved_by_purchase UUID REFERENCES purchase (id),
    ADD COLUMN reserved_until TIMESTAMPTZ;

CREATE TABLE payment_event (
    event_id VARCHAR(128) PRIMARY KEY,
    mercado_pago_order_id VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX purchase_pending_expiry_idx
    ON purchase (expires_at)
    WHERE status = 'PENDING_PAYMENT';
