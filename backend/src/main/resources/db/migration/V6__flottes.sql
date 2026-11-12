-- =====================================================================
-- V6 : flottes, chauffeurs et véhicules (phase 2b, modèle LTStation simplifié)
--   joy_fill_fleet_list            → card_group (flotte d'une entreprise) + compte flotte (balance)
--   joy_fill_fleet_driver, car_user → driver, vehicle, vehicle_assignment ; card.driver_id / vehicle_id
--   joy_fill_fleet_recharge_record → fleet_recharge (+ validation au-dessus du palier)
--   joy_fill_fleet_distribute_record → fleet_movement (DOTATION / REPRISE, soldes avant / après)
--   joy_fill_fleet_card_record     → sale.vehicle_id / driver_id / odometer_km (consommation)
--   vehicle_types                  → vehicle_type ; vehicle_colors → vehicle.color (texte)
-- Non repris : champs administratifs chinois de car_user (construction_company, supervision_org, security_admin,
-- review_passed) ; soldes et numéros de carte dupliqués (portés par la carte) ; max_oil_value = plafonds de la carte.
-- =====================================================================

INSERT INTO app_right (code, label, sort) VALUES ('FLOTTES', 'Flottes, véhicules et chauffeurs', 26);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'FLOTTES');

CREATE TABLE vehicle_type (
    code  text PRIMARY KEY,
    label text NOT NULL,
    sort  int NOT NULL
);
INSERT INTO vehicle_type (code, label, sort) VALUES
    ('CAMION', 'Camion', 1), ('CAMIONNETTE', 'Camionnette / pick-up', 2), ('VOITURE', 'Voiture', 3),
    ('BUS', 'Bus / minibus', 4), ('MOTO', 'Moto / tricycle', 5), ('ENGIN', 'Engin de chantier', 6),
    ('GROUPE', 'Groupe électrogène', 7), ('AUTRE', 'Autre', 8);

-- Flotte = groupe de cartes d'une entreprise : contact et compte flotte (solde à répartir sur les cartes)
ALTER TABLE card_group
    ADD COLUMN code         text,                     -- code court de la flotte (group_code de LTStation)
    ADD COLUMN contact_name text,
    ADD COLUMN phone        text,
    ADD COLUMN address      text,
    ADD COLUMN note         text,
    ADD COLUMN active       boolean NOT NULL DEFAULT true,
    ADD COLUMN balance      bigint NOT NULL DEFAULT 0 CHECK (balance >= 0);

CREATE TABLE driver (
    id              bigserial PRIMARY KEY,
    holder_id       bigint NOT NULL REFERENCES holder (id),
    group_id        bigint REFERENCES card_group (id),
    name            text NOT NULL,
    phone           text,
    licence_number  text,
    licence_expires date,
    id_doc_number   text,
    active          boolean NOT NULL DEFAULT true,
    note            text,
    created_by      bigint REFERENCES employee (id),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX driver_holder ON driver (holder_id, lower(name));

CREATE TABLE vehicle (
    id            bigserial PRIMARY KEY,
    holder_id     bigint NOT NULL REFERENCES holder (id),
    group_id      bigint REFERENCES card_group (id),
    plate         text NOT NULL UNIQUE,                 -- immatriculation en majuscules
    type_code     text NOT NULL DEFAULT 'AUTRE' REFERENCES vehicle_type (code),
    brand         text,
    model         text,
    color         text,
    product_id    bigint REFERENCES product (id),      -- carburant du véhicule
    tank_capacity numeric(8, 2),
    odometer_km   int,                                  -- dernier kilométrage connu
    active        boolean NOT NULL DEFAULT true,
    note          text,
    created_by    bigint REFERENCES employee (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX vehicle_holder ON vehicle (holder_id, group_id);

-- Qui conduit quel véhicule, et depuis quand (une affectation ouverte par véhicule et par chauffeur)
CREATE TABLE vehicle_assignment (
    id          bigserial PRIMARY KEY,
    vehicle_id  bigint NOT NULL REFERENCES vehicle (id),
    driver_id   bigint NOT NULL REFERENCES driver (id),
    started_at  timestamptz NOT NULL DEFAULT now(),
    ended_at    timestamptz,
    assigned_by bigint REFERENCES employee (id),
    note        text,
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX vehicle_assignment_vehicle_open ON vehicle_assignment (vehicle_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX vehicle_assignment_driver_open ON vehicle_assignment (driver_id) WHERE ended_at IS NULL;
CREATE INDEX vehicle_assignment_driver ON vehicle_assignment (driver_id, started_at DESC);

ALTER TABLE card
    ADD COLUMN vehicle_id bigint REFERENCES vehicle (id),
    ADD COLUMN driver_id  bigint REFERENCES driver (id);

-- Consommation : véhicule et chauffeur de la carte au moment de la vente, kilométrage saisi au plein
ALTER TABLE sale
    ADD COLUMN vehicle_id  bigint REFERENCES vehicle (id),
    ADD COLUMN driver_id   bigint REFERENCES driver (id),
    ADD COLUMN odometer_km int CHECK (odometer_km IS NULL OR odometer_km >= 0);
CREATE INDEX sale_vehicle ON sale (vehicle_id, at) WHERE vehicle_id IS NOT NULL;
CREATE INDEX sale_driver ON sale (driver_id, at) WHERE driver_id IS NOT NULL;

-- Recharge du compte flotte (au-dessus du palier : Comptable puis DAF)
CREATE TABLE fleet_recharge (
    id             bigserial PRIMARY KEY,
    ref            text NOT NULL UNIQUE,
    group_id       bigint NOT NULL REFERENCES card_group (id),
    amount         bigint NOT NULL CHECK (amount > 0),
    payment_method text NOT NULL CHECK (payment_method IN ('ESPECES', 'MOBILE_MONEY', 'VIREMENT', 'CARTE_BANCAIRE')),
    payment_ref    text,
    status         text NOT NULL CHECK (status IN ('EN_VALIDATION', 'CREDITEE', 'REFUSEE')),
    validation_id  bigint REFERENCES validation_request (id),
    requested_by   bigint NOT NULL REFERENCES employee (id),
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    credited_at    timestamptz
);
CREATE INDEX fleet_recharge_group ON fleet_recharge (group_id, created_at DESC);

-- Mouvements du compte flotte : recharge, dotation d'une carte (compte → carte), reprise (carte → compte)
CREATE TABLE fleet_movement (
    id                  bigserial PRIMARY KEY,
    group_id            bigint NOT NULL REFERENCES card_group (id),
    at                  timestamptz NOT NULL DEFAULT now(),
    kind                text NOT NULL CHECK (kind IN ('RECHARGE', 'DOTATION', 'REPRISE', 'AJUSTEMENT')),
    amount              bigint NOT NULL,                -- effet sur le compte flotte (+ crédit, − débit)
    balance_after       bigint NOT NULL,
    card_id             bigint REFERENCES card (id),
    card_balance_before bigint,
    card_balance_after  bigint,
    fleet_recharge_id   bigint REFERENCES fleet_recharge (id),
    employee_id         bigint REFERENCES employee (id),
    note                text
);
CREATE INDEX fleet_movement_group ON fleet_movement (group_id, at DESC);
CREATE INDEX fleet_movement_card ON fleet_movement (card_id, at DESC) WHERE card_id IS NOT NULL;

ALTER TABLE card_movement DROP CONSTRAINT card_movement_kind_check;
ALTER TABLE card_movement ADD CONSTRAINT card_movement_kind_check
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'DOTATION', 'REPRISE'));
ALTER TABLE card_movement ADD COLUMN fleet_movement_id bigint REFERENCES fleet_movement (id);

ALTER TABLE validation_request DROP CONSTRAINT validation_request_kind_check;
ALTER TABLE validation_request ADD CONSTRAINT validation_request_kind_check
    CHECK (kind IN ('RECHARGE', 'RECHARGE_MASSE', 'OUVERTURE_PDV', 'RECHARGE_FLOTTE'));

ALTER TABLE card_event DROP CONSTRAINT card_event_kind_check;
ALTER TABLE card_event ADD CONSTRAINT card_event_kind_check
    CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE', 'PIN', 'ATTRIBUTION_PDV',
                    'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION', 'REMPLACEMENT', 'AFFECTATION'));

CREATE SEQUENCE ref_fleet_recharge_seq START 1001;

-- ---------------------------------------------------------------- reprise des véhicules et chauffeurs saisis en texte
INSERT INTO vehicle (holder_id, group_id, plate, type_code, brand)
SELECT DISTINCT ON (upper(trim(c.vehicle_plate))) c.holder_id, c.group_id, upper(trim(c.vehicle_plate)),
       CASE WHEN c.vehicle_label ILIKE '%camion%' OR c.vehicle_label ILIKE '%kerax%' OR c.vehicle_label ILIKE '%actros%' THEN 'CAMION'
            WHEN c.vehicle_label ILIKE '%bus%' THEN 'BUS' WHEN c.vehicle_label ILIKE '%moto%' THEN 'MOTO'
            WHEN c.vehicle_label ILIKE '%npr%' OR c.vehicle_label ILIKE '%hilux%' THEN 'CAMIONNETTE' ELSE 'AUTRE' END,
       c.vehicle_label
FROM card c WHERE c.vehicle_plate IS NOT NULL AND trim(c.vehicle_plate) <> '' AND c.holder_id IS NOT NULL
ORDER BY upper(trim(c.vehicle_plate)), c.id;
UPDATE card c SET vehicle_id = v.id FROM vehicle v WHERE upper(trim(c.vehicle_plate)) = v.plate AND c.vehicle_id IS NULL;

INSERT INTO driver (holder_id, group_id, name)
SELECT DISTINCT ON (c.holder_id, trim(c.driver_name)) c.holder_id, c.group_id, trim(c.driver_name)
FROM card c WHERE c.driver_name IS NOT NULL AND trim(c.driver_name) <> '' AND c.holder_id IS NOT NULL
ORDER BY c.holder_id, trim(c.driver_name), c.id;
UPDATE card c SET driver_id = d.id FROM driver d WHERE d.holder_id = c.holder_id AND d.name = trim(c.driver_name) AND c.driver_id IS NULL;

INSERT INTO vehicle_assignment (vehicle_id, driver_id, started_at)
SELECT DISTINCT ON (c.vehicle_id) c.vehicle_id, c.driver_id, c.created_at
FROM card c WHERE c.vehicle_id IS NOT NULL AND c.driver_id IS NOT NULL
ORDER BY c.vehicle_id, c.id
ON CONFLICT DO NOTHING;
