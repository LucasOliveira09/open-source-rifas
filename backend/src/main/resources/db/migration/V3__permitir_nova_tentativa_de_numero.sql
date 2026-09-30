ALTER TABLE purchase_number
    DROP CONSTRAINT IF EXISTS purchase_number_raffle_number_id_key;

CREATE INDEX IF NOT EXISTS purchase_number_raffle_number_id_idx
    ON purchase_number (raffle_number_id);
