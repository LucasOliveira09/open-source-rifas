UPDATE raffle
SET unit_price_cents = 500
WHERE slug = 'iracema' AND unit_price_cents IS NULL;
