CREATE TABLE raffle_draw (
    raffle_id BIGINT NOT NULL REFERENCES raffle (id),
    draw_sequence INTEGER NOT NULL CHECK (draw_sequence > 0),
    raffle_number_id BIGINT NOT NULL UNIQUE REFERENCES raffle_number (id),
    purchase_id UUID NOT NULL,
    drawn_by VARCHAR(80) NOT NULL,
    drawn_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (raffle_id, draw_sequence),
    FOREIGN KEY (purchase_id, raffle_number_id)
        REFERENCES purchase_number (purchase_id, raffle_number_id)
);
