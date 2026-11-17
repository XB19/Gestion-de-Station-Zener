-- =====================================================================
-- V12 : activation des cartes avant attribution, carte Club (réunion du 17 novembre 2026)
-- Cycle d'une carte : créée « à activer » (en stock, sans titulaire ; « déjà active » si l'activation physique est
-- faite) → activée ou confirmée par une AUTRE personne (garde-fou anti-fraude) → « en stock » (activée, disponible)
-- → attribuée à un titulaire (active) ou confiée à un point de vente puis vendue (active).
-- Carte Club (ex-VIP, carte noire) : 10 000 F + dépôt initial de 200 000 F crédité sur la carte ; adhésion de
-- 12 mois, renouvelée d'office si le titulaire a assez consommé dans l'année, sinon contre des frais ; 30 jours de
-- grâce, puis la carte reste utilisable sans les avantages Club (profil de prix Club, plafonds plus élevés).
-- =====================================================================

ALTER TABLE card
    ADD COLUMN physically_active boolean NOT NULL DEFAULT false,  -- activation physique déclarée à la création
    ADD COLUMN attributed_by     bigint REFERENCES employee (id),
    ADD COLUMN attributed_at     timestamptz,
    ADD COLUMN club_until        date;                             -- fin de l'adhésion Club en cours

-- cartes déjà en stock : considérées comme activées
UPDATE card SET physically_active = true WHERE status IN ('STOCK', 'PDV');

-- VIP → Club
INSERT INTO card_type (code, label, price, sold_to_public, sort) VALUES ('CLUB', 'Club', 10000, true, 2);
UPDATE card SET type_code = 'CLUB' WHERE type_code = 'VIP';
UPDATE card_batch SET type_code = 'CLUB' WHERE type_code = 'VIP';
UPDATE product SET code = 'CARTE_CLUB', name = 'Carte Club', card_type_code = 'CLUB' WHERE card_type_code = 'VIP';
DELETE FROM card_type WHERE code = 'VIP';
UPDATE card_type SET sort = 3 WHERE code = 'FLOTTE';
UPDATE card_type SET sort = 4 WHERE code = 'EMPLOYE';

UPDATE product_price SET price = 10000 WHERE profile = 'GRAND_PUBLIC' AND product_id = (SELECT id FROM product WHERE code = 'CARTE_CLUB');

-- profil de prix Club (avantage par défaut : 2 % sous le grand public, arrondi à 5 F ; à ajuster)
ALTER TABLE product_price DROP CONSTRAINT product_price_profile_check;
ALTER TABLE product_price ADD CONSTRAINT product_price_profile_check
    CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE', 'CLUB'));
INSERT INTO product_price (product_id, profile, price)
SELECT product_id, 'CLUB', GREATEST(round(price * 0.98 / 5) * 5, 0) FROM product_price
WHERE profile = 'GRAND_PUBLIC' AND product_id NOT IN (SELECT id FROM product WHERE category = 'CARTE')
ON CONFLICT DO NOTHING;
INSERT INTO product_price (product_id, profile, price)
SELECT product_id, 'CLUB', price FROM product_price
WHERE profile = 'GRAND_PUBLIC' AND product_id IN (SELECT id FROM product WHERE category = 'CARTE')
ON CONFLICT DO NOTHING;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'price_change_profile_check') THEN
        ALTER TABLE price_change DROP CONSTRAINT price_change_profile_check;
        ALTER TABLE price_change ADD CONSTRAINT price_change_profile_check
            CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE', 'CLUB'));
    END IF;
END $$;

-- adhésions Club : achat, renouvellement d'office (consommation), renouvellement payé
CREATE TABLE club_membership (
    id             bigserial PRIMARY KEY,
    card_id        bigint NOT NULL REFERENCES card (id),
    kind           text NOT NULL CHECK (kind IN ('ADHESION', 'RENOUVELLEMENT_AUTO', 'RENOUVELLEMENT_PAYE')),
    period_start   date NOT NULL,
    period_end     date NOT NULL,
    fee            bigint NOT NULL DEFAULT 0,
    deposit        bigint NOT NULL DEFAULT 0,       -- dépôt initial crédité sur la carte (adhésion)
    spent_12m      bigint,                          -- consommation retenue pour un renouvellement d'office
    payment_method text CHECK (payment_method IN ('ESPECES', 'MOBILE_MONEY', 'VIREMENT', 'CARTE_BANCAIRE')),
    payment_ref    text,
    employee_id    bigint REFERENCES employee (id),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX club_membership_card ON club_membership (card_id, period_end DESC);

ALTER TABLE card_event DROP CONSTRAINT card_event_kind_check;
ALTER TABLE card_event ADD CONSTRAINT card_event_kind_check
    CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE', 'PIN', 'ATTRIBUTION_PDV',
                    'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION', 'REMPLACEMENT', 'AFFECTATION', 'TRANSFERT', 'ATTRIBUTION', 'CLUB'));

INSERT INTO setting (key, value) VALUES
    ('club.price',            '10000'),     -- prix de la carte Club
    ('club.initial_deposit',  '200000'),    -- dépôt minimal crédité sur la carte à l'adhésion
    ('club.validity_months',  '12'),
    ('club.renewal_fee',      '10000'),     -- renouvellement payé
    ('club.auto_renew_spend', '1200000'),   -- consommation sur 12 mois donnant un renouvellement d'office
    ('club.grace_days',       '30'),
    ('club.reminder_days',    '30,7,1'),
    ('club.daily_limit',      '1000000'),   -- plafonds par défaut d'une carte Club (plus élevés)
    ('club.monthly_limit',    '10000000')
ON CONFLICT (key) DO NOTHING;
