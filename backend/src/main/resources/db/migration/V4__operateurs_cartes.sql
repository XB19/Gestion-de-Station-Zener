-- =====================================================================
-- V4 : opérateur de chaque opération sur carte
-- Les types d'opérateur sont les rôles existants (employee.role_code).
-- - card_movement.employee_id : qui a fait la vente ou la recharge qui a
--   modifié le solde (repris de sale.employee_id / recharge.requested_by) ;
-- - card_event : journal des opérations de service sur une carte (émission,
--   activation, blocage, PIN, permissions, PDV…), avec l'opérateur.
-- =====================================================================

ALTER TABLE card_movement ADD COLUMN employee_id bigint REFERENCES employee (id);

UPDATE card_movement m SET employee_id = x.employee_id FROM sale x WHERE x.id = m.sale_id AND m.employee_id IS NULL;
UPDATE card_movement m SET employee_id = r.requested_by FROM recharge r WHERE r.id = m.recharge_id AND m.employee_id IS NULL;

CREATE INDEX card_movement_employee ON card_movement (employee_id, at) WHERE employee_id IS NOT NULL;

CREATE TABLE card_event (
    id          bigserial PRIMARY KEY,
    card_id     bigint NOT NULL REFERENCES card (id),
    at          timestamptz NOT NULL DEFAULT now(),
    kind        text NOT NULL CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE',
                                              'PIN', 'ATTRIBUTION_PDV', 'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION',
                                              'REMPLACEMENT')),
    employee_id bigint REFERENCES employee (id),     -- opérateur (nul : système, PC station)
    station_id  bigint REFERENCES station (id),
    pdv_id      bigint REFERENCES pdv (id),
    detail      text
);
CREATE INDEX card_event_card ON card_event (card_id, at DESC);
CREATE INDEX card_event_employee ON card_event (employee_id, at DESC) WHERE employee_id IS NOT NULL;
CREATE INDEX card_event_at ON card_event (at);

-- Historique déjà connu par les colonnes de la carte
INSERT INTO card_event (card_id, at, kind, employee_id, detail)
SELECT id, COALESCE(sold_at, created_at), 'EMISSION', issued_by, 'Reprise de l''historique'
FROM card WHERE issued_by IS NOT NULL AND sold_pdv_id IS NULL;
INSERT INTO card_event (card_id, at, kind, employee_id, pdv_id, detail)
SELECT id, sold_at, 'VENTE_PDV', sold_by, sold_pdv_id, 'Reprise de l''historique'
FROM card WHERE sold_pdv_id IS NOT NULL AND sold_at IS NOT NULL;
INSERT INTO card_event (card_id, at, kind, employee_id, detail)
SELECT id, activated_at, 'ACTIVATION', activated_by, 'Reprise de l''historique'
FROM card WHERE activated_at IS NOT NULL;
INSERT INTO card_event (card_id, at, kind, employee_id, detail)
SELECT id, now(), 'BLOCAGE', NULL, blocked_reason FROM card WHERE status = 'BLOQUEE';
