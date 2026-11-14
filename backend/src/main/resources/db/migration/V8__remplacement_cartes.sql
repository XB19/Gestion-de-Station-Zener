-- =====================================================================
-- V8 : remplacement d'une carte perdue, volée ou abîmée (phase 2f, version simple)
--   card_service_logs (perte, remplacement…) → card_event (V4) + card_replacement (ancienne / nouvelle carte,
--   solde reporté, frais). L'ancienne carte est bloquée ; pas de listes noire / grise / blanche.
-- =====================================================================

CREATE TABLE card_replacement (
    id                  bigserial PRIMARY KEY,
    old_card_id         bigint NOT NULL REFERENCES card (id),
    new_card_id         bigint NOT NULL REFERENCES card (id),
    reason              text NOT NULL CHECK (reason IN ('PERTE', 'VOL', 'DETERIORATION', 'AUTRE')),
    balance_transferred bigint NOT NULL DEFAULT 0,
    fee                 bigint NOT NULL DEFAULT 0,
    transfer_id         bigint REFERENCES card_transfer (id),
    employee_id         bigint REFERENCES employee (id),
    at                  timestamptz NOT NULL DEFAULT now(),
    note                text
);
CREATE INDEX card_replacement_old ON card_replacement (old_card_id);

ALTER TABLE card_movement DROP CONSTRAINT card_movement_kind_check;
ALTER TABLE card_movement ADD CONSTRAINT card_movement_kind_check
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'TRANSFERT', 'FRAIS'));

ALTER TABLE card_event DROP CONSTRAINT card_event_kind_check;
ALTER TABLE card_event ADD CONSTRAINT card_event_kind_check
    CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE', 'PIN', 'ATTRIBUTION_PDV',
                    'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION', 'REMPLACEMENT', 'AFFECTATION', 'TRANSFERT'));

INSERT INTO setting (key, value) VALUES
    ('card.replacement_fee', '1000')  -- frais de remplacement prélevés sur le solde reporté
ON CONFLICT (key) DO NOTHING;
