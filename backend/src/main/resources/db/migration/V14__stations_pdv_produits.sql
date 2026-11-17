-- =====================================================================
-- V14 : gérance libre, points de vente réouvrables et promotionnels, produits en point de vente,
--       types de lubrifiants (réunion du 17 novembre 2026)
-- =====================================================================

-- ---------------------------------------------------------------- gérance libre : l'exploitant et son contrat
CREATE TABLE station_concession (
    station_id        bigint PRIMARY KEY REFERENCES station (id) ON DELETE CASCADE,
    operator_name     text NOT NULL,             -- exploitant (gérant libre) : personne
    operator_company  text,                      -- … ou société exploitante
    operator_phone    text,
    operator_email    text,
    operator_id_doc   text,                      -- pièce d'identité / RCCM
    operator_address  text,
    contract_ref      text,
    contract_start    date,
    contract_end      date,
    fee_kind          text CHECK (fee_kind IN ('LOYER_FIXE', 'COMMISSION_LITRE', 'POURCENTAGE_CA', 'MIXTE')),
    fee_amount        bigint,                    -- loyer mensuel (F)
    commission_per_liter numeric(8, 2),          -- commission par litre vendu (F)
    revenue_pct       numeric(5, 2),             -- part du chiffre d'affaires (%)
    deposit_amount    bigint,                    -- caution versée
    equipment_zener   text,                      -- équipements fournis par ZENER (cuves, pompes, hub…)
    equipment_operator text,                     -- équipements de l'exploitant
    staff_by_operator boolean NOT NULL DEFAULT true,  -- personnel employé par l'exploitant
    note              text,
    updated_by        bigint REFERENCES employee (id),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- points de vente : sessions (réouverture) et promotionnels
ALTER TABLE pdv DROP CONSTRAINT pdv_kind_check;
ALTER TABLE pdv ADD CONSTRAINT pdv_kind_check CHECK (kind IN ('PERMANENT', 'TEMPORAIRE', 'PROMOTIONNEL'));
ALTER TABLE pdv DROP CONSTRAINT pdv_check;
ALTER TABLE pdv ADD CONSTRAINT pdv_check CHECK (kind = 'PERMANENT' OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date));
ALTER TABLE pdv
    ADD COLUMN campaign      text,               -- point promotionnel : campagne, partenaire, événement
    ADD COLUMN offer         text,               -- offre affichée (ex. « carte classique à 500 F »)
    ADD COLUMN session_no    int NOT NULL DEFAULT 1,
    ADD COLUMN session_started_at timestamptz;   -- début de la session en cours (rapprochement de la session)

-- une ligne par ouverture : la réouverture d'un point clos crée une nouvelle session, avec sa propre validation et sa clôture
CREATE TABLE pdv_session (
    id            bigserial PRIMARY KEY,
    pdv_id        bigint NOT NULL REFERENCES pdv (id),
    session_no    int NOT NULL,
    start_date    date,
    end_date      date,
    status        text NOT NULL CHECK (status IN ('DEMANDE', 'OUVERT', 'A_CLOTURER', 'CLOS', 'REFUSE')),
    reason        text,
    requested_by  bigint REFERENCES employee (id),
    validation_id bigint REFERENCES validation_request (id),
    opened_at     timestamptz,
    closed_at     timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (pdv_id, session_no)
);
INSERT INTO pdv_session (pdv_id, session_no, start_date, end_date, status, reason, requested_by, validation_id, opened_at, closed_at)
SELECT p.id, 1, p.start_date, p.end_date, p.status, p.reason, p.requested_by, p.validation_id,
       CASE WHEN p.status IN ('OUVERT', 'A_CLOTURER', 'CLOS') THEN p.created_at END,
       (SELECT closed_at FROM pdv_closure c WHERE c.pdv_id = p.id)
FROM pdv p;
UPDATE pdv SET session_started_at = created_at;

-- clôture par session (et non plus une par point de vente)
ALTER TABLE pdv_closure DROP CONSTRAINT pdv_closure_pkey;
ALTER TABLE pdv_closure
    ADD COLUMN id bigserial PRIMARY KEY,
    ADD COLUMN session_no int NOT NULL DEFAULT 1,
    ADD COLUMN products_sold_amount bigint NOT NULL DEFAULT 0,
    ADD COLUMN expected_card bigint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX pdv_closure_session ON pdv_closure (pdv_id, session_no);

ALTER TABLE validation_request DROP CONSTRAINT validation_request_kind_check;
ALTER TABLE validation_request ADD CONSTRAINT validation_request_kind_check
    CHECK (kind IN ('RECHARGE', 'RECHARGE_MASSE', 'OUVERTURE_PDV', 'DROITS_ROLE', 'PARAMETRES', 'AJUSTEMENT_SOLDE', 'COMPTE_SENSIBLE'));

-- produits vendus au point de vente (en plus des cartes) ; prix promotionnel facultatif
CREATE TABLE pdv_product (
    pdv_id      bigint NOT NULL REFERENCES pdv (id) ON DELETE CASCADE,
    product_id  bigint NOT NULL REFERENCES product (id),
    promo_price bigint CHECK (promo_price IS NULL OR promo_price >= 0),
    PRIMARY KEY (pdv_id, product_id)
);

-- ventes en point de vente : produits payables aussi par carte
ALTER TABLE sale DROP CONSTRAINT sale_kind_check;
ALTER TABLE sale ADD CONSTRAINT sale_kind_check CHECK (kind IN ('CARBURANT', 'BOUTIQUE', 'CARTE', 'ECHANGE_GAZ', 'ATELIER'));
ALTER TABLE sale DROP CONSTRAINT sale_channel_check;
ALTER TABLE sale ADD CONSTRAINT sale_channel_check CHECK (channel IN ('POMPE', 'BOUTIQUE', 'DISTRIBUTEUR_GAZ', 'PDV', 'LIVRAISON', 'ATELIER'));

-- ---------------------------------------------------------------- lubrifiants : type, grade, marque, conditionnement
ALTER TABLE product
    ADD COLUMN subcategory text,                 -- lubrifiant : MOTEUR_4T, MOTEUR_2T, DIESEL, BOITE_PONT, HYDRAULIQUE, FREIN, REFROIDISSEMENT, GRAISSE, AUTRE
    ADD COLUMN grade       text,                 -- 15W40, 5W30, SAE 90, DOT 4…
    ADD COLUMN brand       text,
    ADD COLUMN pack_size   numeric(8, 2),        -- contenance (L ou kg)
    ADD COLUMN pack_unit   text;                 -- L, kg
ALTER TABLE product DROP CONSTRAINT product_category_check;
ALTER TABLE product ADD CONSTRAINT product_category_check
    CHECK (category IN ('CARBURANT', 'LUBRIFIANT', 'GAZ', 'ACCESSOIRE', 'CARTE', 'SERVICE'));

UPDATE product SET subcategory = 'MOTEUR_4T', grade = '5W30', brand = 'Zener', pack_size = 5, pack_unit = 'L' WHERE code = 'HUILE_5W30_5L';
UPDATE product SET subcategory = 'MOTEUR_2T', grade = '2T', brand = 'Zener', pack_size = 1, pack_unit = 'L' WHERE code = 'HUILE_2T_1L';

INSERT INTO product (code, name, category, unit, subcategory, grade, brand, pack_size, pack_unit) VALUES
    ('HUILE_15W40_1L',   'Huile moteur 15W40 · 1 L',          'LUBRIFIANT', 'unité', 'MOTEUR_4T',       '15W40',  'Zener', 1,   'L'),
    ('HUILE_15W40_5L',   'Huile moteur 15W40 · 5 L',          'LUBRIFIANT', 'unité', 'MOTEUR_4T',       '15W40',  'Zener', 5,   'L'),
    ('HUILE_20W50_4L',   'Huile moteur 20W50 · 4 L',          'LUBRIFIANT', 'unité', 'MOTEUR_4T',       '20W50',  'Zener', 4,   'L'),
    ('HUILE_DIESEL_20L', 'Huile diesel poids lourds 15W40 · 20 L', 'LUBRIFIANT', 'unité', 'DIESEL',   '15W40',  'Zener', 20,  'L'),
    ('HUILE_DIESEL_208L','Huile diesel 15W40 · fût 208 L',    'LUBRIFIANT', 'fût',   'DIESEL',          '15W40',  'Zener', 208, 'L'),
    ('HUILE_BOITE_1L',   'Huile boîte et pont SAE 90 · 1 L',  'LUBRIFIANT', 'unité', 'BOITE_PONT',      'SAE 90', 'Zener', 1,   'L'),
    ('HUILE_HYDRO_20L',  'Huile hydraulique HV 46 · 20 L',    'LUBRIFIANT', 'unité', 'HYDRAULIQUE',     'HV 46',  'Zener', 20,  'L'),
    ('LIQUIDE_FREIN',    'Liquide de frein DOT 4 · 0,5 L',    'LUBRIFIANT', 'unité', 'FREIN',           'DOT 4',  'Zener', 0.5, 'L'),
    ('LIQUIDE_REFROID',  'Liquide de refroidissement · 1 L',  'LUBRIFIANT', 'unité', 'REFROIDISSEMENT', NULL,     'Zener', 1,   'L'),
    ('GRAISSE_1KG',      'Graisse multi-usages · 1 kg',       'LUBRIFIANT', 'pot',   'GRAISSE',         'EP 2',   'Zener', 1,   'kg'),
    ('MAIN_OEUVRE',      'Main-d''œuvre atelier',             'SERVICE',    'F',     NULL,              NULL,     NULL,    NULL, NULL);

INSERT INTO product_price (product_id, profile, price)
SELECT p.id, v.profile, CASE v.profile WHEN 'GRAND_PUBLIC' THEN v.price WHEN 'CLUB' THEN round(v.price * 0.98 / 5) * 5
                                       WHEN 'PARTENAIRE' THEN round(v.price * 0.94 / 5) * 5 ELSE round(v.price * 0.88 / 5) * 5 END
FROM product p
JOIN (VALUES ('HUILE_15W40_1L', 4500), ('HUILE_15W40_5L', 20000), ('HUILE_20W50_4L', 16000), ('HUILE_DIESEL_20L', 78000),
             ('HUILE_DIESEL_208L', 760000), ('HUILE_BOITE_1L', 5000), ('HUILE_HYDRO_20L', 65000), ('LIQUIDE_FREIN', 3000),
             ('LIQUIDE_REFROID', 2500), ('GRAISSE_1KG', 4000)) AS b (code, price) ON b.code = p.code
CROSS JOIN (VALUES ('GRAND_PUBLIC'), ('EMPLOYE_STATION'), ('EMPLOYE_GROUPE'), ('PARTENAIRE'), ('CLUB')) AS v0 (profile)
CROSS JOIN LATERAL (SELECT v0.profile AS profile, b.price AS price) v;
-- main-d'œuvre : 1 F l'unité, la quantité vaut le montant facturé (pas de stock)
INSERT INTO product_price (product_id, profile, price)
SELECT p.id, v.profile, 1 FROM product p CROSS JOIN (VALUES ('GRAND_PUBLIC'), ('EMPLOYE_STATION'), ('EMPLOYE_GROUPE'), ('PARTENAIRE'), ('CLUB')) v (profile)
WHERE p.code = 'MAIN_OEUVRE';
INSERT INTO price_change (product_id, profile, new_price, effective_at, status, applied_at, note)
SELECT pp.product_id, pp.profile, pp.price, now(), 'APPLIQUE', now(), 'Prix en vigueur'
FROM product_price pp JOIN product p ON p.id = pp.product_id
WHERE p.code IN ('HUILE_15W40_1L', 'HUILE_15W40_5L', 'HUILE_20W50_4L', 'HUILE_DIESEL_20L', 'HUILE_DIESEL_208L', 'HUILE_BOITE_1L',
                 'HUILE_HYDRO_20L', 'LIQUIDE_FREIN', 'LIQUIDE_REFROID', 'GRAISSE_1KG', 'MAIN_OEUVRE');

-- transferts de stock station ⇄ point de vente
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_kind_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_kind_check
    CHECK (kind IN ('ENTREE', 'VENTE', 'ECHANGE', 'AJUSTEMENT', 'INVENTAIRE', 'LIVRAISON', 'TRANSFERT'));
