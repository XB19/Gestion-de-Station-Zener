-- =====================================================================
-- V15 : mise en route du réseau et ordres de travail atelier (réunion du 17 novembre 2026)
-- - Mise en route : informations de l'entreprise (paramètres company.*) ; la liste de mise en route du réseau,
--   des stations et des employés est calculée à partir des données (pas de table).
-- - Ordres de travail : prestation faite sur un véhicule à la station (vidange, graissage, filtres, lavage…) :
--   véhicule (immatriculation, kilométrage), produits utilisés (sortis du stock à l'encaissement), main-d'œuvre,
--   technicien, statut (ouvert → en cours → terminé → payé), paiement en espèces, mobile money ou carte.
-- =====================================================================

INSERT INTO setting (key, value) VALUES
    ('company.name',    'ZENER'),
    ('company.legal',   'ZENER SA'),
    ('company.address', 'Lomé, Togo'),
    ('company.phone',   '+228 22 21 40 00'),
    ('company.email',   'contact@zener.tg')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE vehicle (
    id            bigserial PRIMARY KEY,
    plate         text NOT NULL UNIQUE,          -- immatriculation en majuscules
    brand         text,
    model         text,
    year          int,
    fuel          text,
    holder_id     bigint REFERENCES holder (id), -- client propriétaire (facultatif)
    owner_name    text,
    owner_phone   text,
    odometer_km   int,                           -- dernier kilométrage relevé
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE work_order (
    id             bigserial PRIMARY KEY,
    ref            text NOT NULL UNIQUE,            -- OT-2026-1001
    station_id     bigint NOT NULL REFERENCES station (id),
    vehicle_id     bigint NOT NULL REFERENCES vehicle (id),
    card_id        bigint REFERENCES card (id),
    customer_name  text,
    customer_phone text,
    odometer_km    int CHECK (odometer_km IS NULL OR odometer_km >= 0),
    services       text[] NOT NULL DEFAULT '{}',    -- VIDANGE, GRAISSAGE, FILTRES, LAVAGE, PNEUS, BATTERIE, DIAGNOSTIC, AUTRE
    description    text,
    status         text NOT NULL DEFAULT 'OUVERT' CHECK (status IN ('OUVERT', 'EN_COURS', 'TERMINE', 'PAYE', 'ANNULE')),
    technician_id  bigint REFERENCES employee (id),
    labour_amount  bigint NOT NULL DEFAULT 0 CHECK (labour_amount >= 0),
    next_service_km int,                            -- prochaine vidange conseillée
    opened_by      bigint NOT NULL REFERENCES employee (id),
    opened_at      timestamptz NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    paid_at        timestamptz,
    sale_id        bigint REFERENCES sale (id),
    cancel_reason  text
);
CREATE INDEX work_order_station ON work_order (station_id, opened_at DESC);
CREATE INDEX work_order_vehicle ON work_order (vehicle_id, opened_at DESC);

CREATE TABLE work_order_line (
    id            bigserial PRIMARY KEY,
    work_order_id bigint NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    product_id    bigint NOT NULL REFERENCES product (id),
    quantity      numeric(10, 2) NOT NULL CHECK (quantity > 0)
);

CREATE SEQUENCE ref_work_order_seq START 1001;

INSERT INTO app_right (code, label, sort) VALUES ('ATELIER', 'Atelier : ordres de travail véhicules', 34);
INSERT INTO role_right (role_code, right_code) VALUES
    ('ADMIN', 'ATELIER'), ('GERANT', 'ATELIER'), ('CHEF_POSTE', 'ATELIER');
