-- =====================================================================
-- V7 : cartes d'entreprise et transferts (phase 2c, modèle LTStation simplifié)
--   group_accounts   → card_group.balance (V6) + plafond du compte (load_up_limit) et frais de recharge (service_fee)
--   group_card_infos → card_permission : plafonds en volume (jour, mois), nombre de pleins par jour, solde minimum ;
--                      les plafonds en montant, produits, jours, horaires, stations existaient ; la limitation au
--                      véhicule est portée par card.vehicle_id (V6) ; crédit (is_credit_card…) : V9
--   card_transfers   → card_transfer (carte → carte du même titulaire)
--   group_tansfers   → fleet_movement DOTATION (V6) et TRANSFERT entre flottes de la même entreprise
-- Non repris : champs de chiffrement des cartes à puce (cipher_flg, ctc, media_type_id), use_confine_in_store
-- (catégories de boutique : couvertes par card_permission.products), quota_range / mileage_tolerance.
-- =====================================================================

ALTER TABLE card_group
    ADD COLUMN load_up_limit    bigint CHECK (load_up_limit IS NULL OR load_up_limit > 0),   -- solde maximal du compte flotte
    ADD COLUMN recharge_fee_pct numeric(5, 2) NOT NULL DEFAULT 0 CHECK (recharge_fee_pct >= 0 AND recharge_fee_pct < 100);

ALTER TABLE fleet_recharge ADD COLUMN fee bigint NOT NULL DEFAULT 0 CHECK (fee >= 0);

ALTER TABLE card_permission
    ADD COLUMN daily_volume_limit   numeric(10, 2) CHECK (daily_volume_limit IS NULL OR daily_volume_limit > 0),
    ADD COLUMN monthly_volume_limit numeric(12, 2) CHECK (monthly_volume_limit IS NULL OR monthly_volume_limit > 0),
    ADD COLUMN daily_fills_limit    int CHECK (daily_fills_limit IS NULL OR daily_fills_limit > 0),
    ADD COLUMN min_balance          bigint CHECK (min_balance IS NULL OR min_balance >= 0);

-- Transfert de solde d'une carte à une autre du même titulaire
CREATE TABLE card_transfer (
    id                  bigserial PRIMARY KEY,
    ref                 text NOT NULL UNIQUE,
    from_card_id        bigint NOT NULL REFERENCES card (id),
    to_card_id          bigint NOT NULL REFERENCES card (id),
    amount              bigint NOT NULL CHECK (amount > 0),
    from_balance_before bigint NOT NULL,
    from_balance_after  bigint NOT NULL,
    to_balance_before   bigint NOT NULL,
    to_balance_after    bigint NOT NULL,
    reason              text NOT NULL DEFAULT 'TRANSFERT' CHECK (reason IN ('TRANSFERT', 'REMPLACEMENT')),
    employee_id         bigint REFERENCES employee (id),
    at                  timestamptz NOT NULL DEFAULT now(),
    note                text,
    CHECK (from_card_id <> to_card_id)
);
CREATE INDEX card_transfer_from ON card_transfer (from_card_id, at DESC);
CREATE INDEX card_transfer_to ON card_transfer (to_card_id, at DESC);
CREATE SEQUENCE ref_transfer_seq START 1001;

ALTER TABLE card_movement DROP CONSTRAINT card_movement_kind_check;
ALTER TABLE card_movement ADD CONSTRAINT card_movement_kind_check
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'DOTATION', 'REPRISE', 'TRANSFERT'));
ALTER TABLE card_movement ADD COLUMN transfer_id bigint REFERENCES card_transfer (id);

-- Transfert entre deux flottes de la même entreprise
ALTER TABLE fleet_movement DROP CONSTRAINT fleet_movement_kind_check;
ALTER TABLE fleet_movement ADD CONSTRAINT fleet_movement_kind_check
    CHECK (kind IN ('RECHARGE', 'DOTATION', 'REPRISE', 'AJUSTEMENT', 'TRANSFERT'));
ALTER TABLE fleet_movement ADD COLUMN counterpart_group_id bigint REFERENCES card_group (id);
