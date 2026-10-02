-- Preserve Orders history; its identifiers must never be reused as Payment IDs.
ALTER TABLE purchase ADD COLUMN mercado_pago_payment_id VARCHAR(64) UNIQUE;

ALTER TABLE payment_event
    ADD COLUMN mercado_pago_payment_id VARCHAR(64),
    ALTER COLUMN mercado_pago_order_id DROP NOT NULL,
    ADD CONSTRAINT payment_event_provider_id_required
        CHECK (mercado_pago_payment_id IS NOT NULL OR mercado_pago_order_id IS NOT NULL);
