-- =====================================================================
-- V10 : listes de cartes, perte et remplacement (phase 2f, modèle LTStation simplifié)
--   black list / gray_card_lists / white_card_lists → card_list (NOIRE : opposition, refusée partout ;
--        GRISE : sous surveillance, code PIN exigé à chaque paiement ; BLANCHE : acceptée hors connexion par la
--        station indiquée). Les listes sont envoyées au PC station avec la configuration.
--   card_service_logs (perte, remplacement…) → card_event (V4) + card_replacement (ancienne / nouvelle carte,
--        solde reporté, frais)
-- Non repris : champs de transaction des cartes à puce de la liste grise (ctc, gmac, psam…), propres au
-- matériel chinois ; une transaction hors ligne refusée à la synchronisation met la carte en liste grise.
-- =====================================================================

CREATE TABLE card_list (
    id           bigserial PRIMARY KEY,
    kind         text NOT NULL CHECK (kind IN ('NOIRE', 'BLANCHE', 'GRISE')),
    card_id      bigint NOT NULL REFERENCES card (id),
    station_id   bigint REFERENCES station (id),        -- BLANCHE : station (nul = toutes)
    reason       text NOT NULL,
    added_at     timestamptz NOT NULL DEFAULT now(),
    added_by     bigint REFERENCES employee (id),        -- nul : ajout automatique
    valid_until  timestamptz,
    removed_at   timestamptz,
    removed_by   bigint REFERENCES employee (id),
    removal_note text
);
CREATE UNIQUE INDEX card_list_open ON card_list (card_id, kind, COALESCE(station_id, 0)) WHERE removed_at IS NULL;
CREATE INDEX card_list_kind ON card_list (kind) WHERE removed_at IS NULL;

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
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'DOTATION', 'REPRISE', 'TRANSFERT', 'FIDELITE', 'REGLEMENT', 'FRAIS'));

ALTER TABLE card_event DROP CONSTRAINT card_event_kind_check;
ALTER TABLE card_event ADD CONSTRAINT card_event_kind_check
    CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE', 'PIN', 'ATTRIBUTION_PDV',
                    'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION', 'REMPLACEMENT', 'AFFECTATION', 'LISTE', 'TRANSFERT'));

-- les cartes déjà bloquées passent en opposition (liste noire)
INSERT INTO card_list (kind, card_id, reason, added_at)
SELECT 'NOIRE', id, COALESCE(blocked_reason, 'Carte bloquée'), now() FROM card WHERE status = 'BLOQUEE';

INSERT INTO setting (key, value) VALUES
    ('card.replacement_fee',   '1000'),  -- frais de remplacement prélevés sur le solde reporté
    ('card.gray_pin_failures', '3')      -- échecs de PIN en 15 min avant la mise en liste grise
ON CONFLICT (key) DO NOTHING;
