-- =====================================================================
-- V11 : historique des prix et commandes fournisseurs (phase 2g, modèle LTStation simplifié)
--   price_logs              → price_change (ancien / nouveau prix par produit et profil, date d'effet ;
--                             changement planifié appliqué automatiquement à la date d'effet)
--   supplier_oil_supply     → supplier (dépôts pétroliers et fournisseurs de la boutique)
--   supplier_purchase_order → supplier_order (commande, expédition, livraison en cuve rattachée à la réception)
-- Simplifications : prix nationaux (pas de prix par station), la densité reste sur la réception (tank_delivery) ;
-- non repris : platform_id / company_id (plateforme d'achat chinoise).
-- =====================================================================

CREATE TABLE price_change (
    id           bigserial PRIMARY KEY,
    product_id   bigint NOT NULL REFERENCES product (id),
    profile      text NOT NULL CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE')),
    old_price    bigint,
    new_price    bigint NOT NULL CHECK (new_price >= 0),
    effective_at timestamptz NOT NULL,
    status       text NOT NULL CHECK (status IN ('PLANIFIE', 'APPLIQUE', 'ANNULE')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   bigint REFERENCES employee (id),
    applied_at   timestamptz,
    note         text
);
CREATE INDEX price_change_product ON price_change (product_id, profile, effective_at DESC);
CREATE INDEX price_change_planned ON price_change (effective_at) WHERE status = 'PLANIFIE';

-- prix actuels : point de départ de l'historique
INSERT INTO price_change (product_id, profile, old_price, new_price, effective_at, status, created_by, applied_at, note)
SELECT product_id, profile, NULL, price, updated_at, 'APPLIQUE', updated_by, updated_at, 'Prix en vigueur'
FROM product_price;

CREATE TABLE supplier (
    id           bigserial PRIMARY KEY,
    name         text NOT NULL UNIQUE,
    kind         text NOT NULL DEFAULT 'CARBURANT' CHECK (kind IN ('CARBURANT', 'BOUTIQUE', 'GAZ', 'AUTRE')),
    contact_name text,
    phone        text,
    email        text,
    address      text,
    note         text,
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE supplier_order (
    id              bigserial PRIMARY KEY,
    ref             text NOT NULL UNIQUE,
    supplier_id     bigint NOT NULL REFERENCES supplier (id),
    station_id      bigint NOT NULL REFERENCES station (id),
    product_id      bigint NOT NULL REFERENCES product (id),
    tank_id         bigint REFERENCES tank (id),
    quantity        numeric(12, 2) NOT NULL CHECK (quantity > 0),
    unit_price      bigint NOT NULL CHECK (unit_price >= 0),        -- prix d'achat
    amount          bigint NOT NULL,
    status          text NOT NULL DEFAULT 'COMMANDEE' CHECK (status IN ('BROUILLON', 'COMMANDEE', 'EXPEDIEE', 'LIVREE', 'ANNULEE')),
    ordered_at      timestamptz NOT NULL DEFAULT now(),
    expected_at     timestamptz,
    seller_name     text,
    seller_phone    text,
    driver_name     text,
    driver_phone    text,
    truck_plate     text,
    shipped_at      timestamptz,
    delivered_at    timestamptz,
    received_volume numeric(12, 2),
    created_by      bigint REFERENCES employee (id),
    note            text,
    cancel_reason   text
);
CREATE INDEX supplier_order_status ON supplier_order (status, ordered_at DESC);
CREATE INDEX supplier_order_station ON supplier_order (station_id, ordered_at DESC);
CREATE SEQUENCE ref_supplier_order_seq START 1001;

ALTER TABLE tank_delivery ADD COLUMN supplier_order_id bigint REFERENCES supplier_order (id);
ALTER TABLE purchase ADD COLUMN supplier_id bigint REFERENCES supplier (id);

INSERT INTO app_right (code, label, sort) VALUES ('APPROVISIONNEMENT', 'Fournisseurs et commandes', 29);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'APPROVISIONNEMENT'), ('GERANT', 'APPROVISIONNEMENT');
