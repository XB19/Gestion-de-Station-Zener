-- =====================================================================
-- V5 : pistolets et cuves (phase 2a, modèle LTStation simplifié)
--   daily_gun_reports / monthly_gun_reports → pump_report (POSTE, JOUR, MOIS)
--   tank_history / tank_measurements / tank_real_data → tank_reading
--   import_oil_infos → tank_delivery ; stocktakings → stock_count
--   tank_alarm / tank_alarm_history → tank_alarm (ouverte tant que ended_at est nul)
-- =====================================================================

-- Relevé d'index d'un pistolet sur un poste, un jour ou un mois.
-- Volume compteur (index fin − index début) comparé au volume vendu : l'écart signale une
-- distribution non enregistrée (essai, étalonnage, fraude, panne de transmission).
CREATE TABLE pump_report (
    id                bigserial PRIMARY KEY,
    pump_id           bigint NOT NULL REFERENCES pump (id) ON DELETE CASCADE,
    station_id        bigint NOT NULL REFERENCES station (id),
    product_id        bigint NOT NULL REFERENCES product (id),
    kind              text NOT NULL CHECK (kind IN ('POSTE', 'JOUR', 'MOIS')),
    period_start      date NOT NULL,                       -- jour du poste, jour, ou premier jour du mois
    employee_shift_id bigint REFERENCES employee_shift (id),
    started_at        timestamptz NOT NULL,
    ended_at          timestamptz NOT NULL,
    start_index       numeric(14, 2) NOT NULL,
    end_index         numeric(14, 2) NOT NULL,
    meter_volume      numeric(12, 2) NOT NULL,             -- index fin − index début
    sold_volume       numeric(12, 2) NOT NULL,             -- ventes enregistrées sur le pistolet
    gap               numeric(12, 2) NOT NULL,             -- compteur − ventes
    transactions      int NOT NULL DEFAULT 0,
    amount            bigint NOT NULL DEFAULT 0,
    generated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'POSTE') = (employee_shift_id IS NOT NULL))
);
CREATE UNIQUE INDEX pump_report_period ON pump_report (pump_id, kind, period_start) WHERE kind <> 'POSTE';
CREATE UNIQUE INDEX pump_report_shift ON pump_report (pump_id, employee_shift_id) WHERE kind = 'POSTE';
CREATE INDEX pump_report_station ON pump_report (station_id, kind, started_at DESC);

-- Mesure d'une cuve : jauge automatique (PC station) ou mesure manuelle (pige).
-- book_volume : stock théorique au moment de la mesure ; gap : mesuré − théorique.
CREATE TABLE tank_reading (
    id              bigserial PRIMARY KEY,
    tank_id         bigint NOT NULL REFERENCES tank (id) ON DELETE CASCADE,
    station_id      bigint NOT NULL REFERENCES station (id),
    product_id      bigint NOT NULL REFERENCES product (id),
    at              timestamptz NOT NULL DEFAULT now(),
    source          text NOT NULL CHECK (source IN ('JAUGE', 'MANUEL')),
    volume          numeric(12, 2) NOT NULL CHECK (volume >= 0),
    book_volume     numeric(12, 2),
    gap             numeric(12, 2),
    height_mm       numeric(8, 1),
    temperature     numeric(5, 1),
    water_height_mm numeric(7, 1),
    water_volume    numeric(10, 2),
    density         numeric(6, 4),
    employee_id     bigint REFERENCES employee (id),
    note            text
);
CREATE INDEX tank_reading_tank ON tank_reading (tank_id, at DESC);
CREATE INDEX tank_reading_station ON tank_reading (station_id, at DESC);

-- Réception de carburant (dépotage) : bon du fournisseur comparé au volume reçu en cuve.
CREATE TABLE tank_delivery (
    id              bigserial PRIMARY KEY,
    ref             text NOT NULL UNIQUE,
    tank_id         bigint NOT NULL REFERENCES tank (id),
    station_id      bigint NOT NULL REFERENCES station (id),
    product_id      bigint NOT NULL REFERENCES product (id),
    supplier        text,
    truck_plate     text,
    driver          text,
    ticket_number   text,                                  -- n° du bon de livraison
    ticket_volume   numeric(12, 2) NOT NULL CHECK (ticket_volume > 0),
    ticket_density  numeric(6, 4),
    temperature     numeric(5, 1),
    volume_before   numeric(12, 2) NOT NULL,
    volume_after    numeric(12, 2) NOT NULL,
    sold_during     numeric(12, 2) NOT NULL DEFAULT 0,     -- vendu pendant le dépotage
    received_volume numeric(12, 2) NOT NULL CHECK (received_volume > 0),
    gap             numeric(12, 2) NOT NULL,               -- reçu − bon
    started_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz NOT NULL DEFAULT now(),
    employee_id     bigint REFERENCES employee (id),
    note            text
);
CREATE INDEX tank_delivery_station ON tank_delivery (station_id, ended_at DESC);
CREATE INDEX tank_delivery_tank ON tank_delivery (tank_id, ended_at DESC);

-- Inventaire : stock théorique et stock compté (articles, ou cuve mesurée à la pige).
CREATE TABLE stock_count (
    id               bigserial PRIMARY KEY,
    station_id       bigint REFERENCES station (id),
    pdv_id           bigint REFERENCES pdv (id),
    product_id       bigint NOT NULL REFERENCES product (id),
    tank_id          bigint REFERENCES tank (id),
    book_quantity    numeric(12, 2) NOT NULL,
    counted_quantity numeric(12, 2) NOT NULL CHECK (counted_quantity >= 0),
    gap              numeric(12, 2) NOT NULL,              -- compté − théorique
    adjusted         boolean NOT NULL DEFAULT true,        -- l'écart a été passé en ajustement de stock
    employee_id      bigint REFERENCES employee (id),
    at               timestamptz NOT NULL DEFAULT now(),
    note             text,
    CHECK ((station_id IS NULL) <> (pdv_id IS NULL))
);
CREATE INDEX stock_count_station ON stock_count (station_id, at DESC);
CREATE INDEX stock_count_product ON stock_count (product_id, at DESC);

-- Alarme de cuve (le niveau bas reste dans stock_alert) : ouverte tant que ended_at est nul.
CREATE TABLE tank_alarm (
    id              bigserial PRIMARY KEY,
    tank_id         bigint NOT NULL REFERENCES tank (id) ON DELETE CASCADE,
    station_id      bigint NOT NULL REFERENCES station (id),
    kind            text NOT NULL CHECK (kind IN ('NIVEAU_HAUT', 'EAU', 'TEMPERATURE', 'ECART_JAUGE')),
    started_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    value           numeric(12, 2),
    threshold       numeric(12, 2),
    acknowledged_by bigint REFERENCES employee (id),
    acknowledged_at timestamptz,
    note            text
);
CREATE UNIQUE INDEX tank_alarm_open ON tank_alarm (tank_id, kind) WHERE ended_at IS NULL;
CREATE INDEX tank_alarm_station ON tank_alarm (station_id, started_at DESC);

INSERT INTO setting (key, value) VALUES
    ('tank.reading_interval_minutes', '15'),   -- une mesure de jauge enregistrée au plus toutes les 15 min par cuve
    ('tank.high_level_pct',           '95'),   -- alarme niveau haut (% de la capacité)
    ('tank.water_alarm_mm',           '30'),   -- alarme eau en fond de cuve
    ('tank.temperature_alarm',        '40'),   -- °C
    ('tank.gauge_gap_pct',            '1.5'),  -- alarme écart jauge / théorique (% de la capacité)
    ('pump.gap_alert_liters',         '5')     -- écart compteur / ventes signalé dans les relevés
ON CONFLICT (key) DO NOTHING;

-- Référence des réceptions : DEP-2026-1001 (dépotage)
CREATE SEQUENCE ref_reception_seq START 1001;

-- Ventes par pistolet (relevés d'index)
CREATE INDEX sale_pump_at ON sale (pump_id, at) WHERE pump_id IS NOT NULL;
