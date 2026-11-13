-- =====================================================================
-- V8 : fidélité et remises (phase 2d, modèle LTStation simplifié)
--   score_rate / score_settings → loyalty_rule (points par tranche de 1 000 F, par catégorie ou produit)
--   score_log                   → loyalty_movement (gain, utilisation, expiration, ajustement ; solde après)
--   customer_account.score       → holder.loyalty_points
--   member_discount (paliers selon la consommation) et discount_store (remise par catégorie et par groupe)
--                               → discount_rule (PALIER, GROUPE, PROFIL ; catégorie ou produit ; %)
-- Les remises s'appliquent aux achats payés par carte ; elles sont enregistrées ligne par ligne (sale_line).
-- =====================================================================

ALTER TABLE holder ADD COLUMN loyalty_points bigint NOT NULL DEFAULT 0 CHECK (loyalty_points >= 0);

CREATE TABLE loyalty_rule (
    id              bigserial PRIMARY KEY,
    category        text CHECK (category IN ('CARBURANT', 'LUBRIFIANT', 'GAZ', 'ACCESSOIRE')),
    product_id      bigint REFERENCES product (id),      -- règle propre à un produit (prioritaire sur la catégorie)
    points_per_1000 numeric(6, 2) NOT NULL CHECK (points_per_1000 >= 0),
    active          boolean NOT NULL DEFAULT true,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      bigint REFERENCES employee (id),
    CHECK ((category IS NULL) <> (product_id IS NULL))
);
CREATE UNIQUE INDEX loyalty_rule_category ON loyalty_rule (category) WHERE category IS NOT NULL;
CREATE UNIQUE INDEX loyalty_rule_product ON loyalty_rule (product_id) WHERE product_id IS NOT NULL;
INSERT INTO loyalty_rule (category, points_per_1000) VALUES ('CARBURANT', 1), ('LUBRIFIANT', 3), ('GAZ', 2), ('ACCESSOIRE', 2);

CREATE TABLE loyalty_movement (
    id            bigserial PRIMARY KEY,
    holder_id     bigint NOT NULL REFERENCES holder (id),
    card_id       bigint REFERENCES card (id),
    sale_id       bigint REFERENCES sale (id),
    at            timestamptz NOT NULL DEFAULT now(),
    kind          text NOT NULL CHECK (kind IN ('GAIN', 'UTILISATION', 'EXPIRATION', 'AJUSTEMENT')),
    points        bigint NOT NULL,                     -- signé
    balance_after bigint NOT NULL,
    amount        bigint,                              -- utilisation : montant crédité sur la carte
    employee_id   bigint REFERENCES employee (id),
    note          text
);
CREATE INDEX loyalty_movement_holder ON loyalty_movement (holder_id, at DESC);
CREATE INDEX loyalty_movement_at ON loyalty_movement (at);

CREATE TABLE discount_rule (
    id              bigserial PRIMARY KEY,
    name            text NOT NULL,
    scope           text NOT NULL CHECK (scope IN ('PALIER', 'GROUPE', 'PROFIL')),
    category        text CHECK (category IN ('CARBURANT', 'LUBRIFIANT', 'GAZ', 'ACCESSOIRE')),  -- nul : tous les produits
    product_id      bigint REFERENCES product (id),
    min_consumption bigint CHECK (min_consumption IS NULL OR min_consumption >= 0),     -- PALIER : achats des 12 derniers mois
    card_group_id   bigint REFERENCES card_group (id),                                  -- GROUPE
    profile         text CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE')), -- PROFIL
    discount_pct    numeric(5, 2) NOT NULL CHECK (discount_pct > 0 AND discount_pct < 100),
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      bigint REFERENCES employee (id),
    CHECK ((scope = 'PALIER') = (min_consumption IS NOT NULL)),
    CHECK ((scope = 'GROUPE') = (card_group_id IS NOT NULL)),
    CHECK ((scope = 'PROFIL') = (profile IS NOT NULL))
);

ALTER TABLE sale_line
    ADD COLUMN discount_amount  bigint NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    ADD COLUMN discount_rule_id bigint REFERENCES discount_rule (id);
ALTER TABLE sale
    ADD COLUMN discount_total bigint NOT NULL DEFAULT 0,
    ADD COLUMN points_earned  bigint NOT NULL DEFAULT 0;

ALTER TABLE card_movement DROP CONSTRAINT card_movement_kind_check;
ALTER TABLE card_movement ADD CONSTRAINT card_movement_kind_check
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'DOTATION', 'REPRISE', 'TRANSFERT', 'FIDELITE'));

INSERT INTO app_right (code, label, sort) VALUES ('FIDELITE', 'Fidélité et remises', 27);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'FIDELITE');

INSERT INTO setting (key, value) VALUES
    ('loyalty.enabled',        'true'),
    ('loyalty.point_value',    '5'),     -- F crédités par point utilisé
    ('loyalty.min_redeem',     '100'),   -- points minimum pour une utilisation
    ('loyalty.vip_multiplier', '2')      -- cartes VIP : points multipliés
ON CONFLICT (key) DO NOTHING;
