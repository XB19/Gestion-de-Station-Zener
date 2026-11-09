-- ZENER Station Manager : schéma initial
-- Montants en FCFA (bigint), volumes en litres (numeric).

-- ---------------------------------------------------------------- Paramètres
CREATE TABLE setting (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- Clients et licences
CREATE TABLE client (
    id           bigserial PRIMARY KEY,
    name         text NOT NULL,
    kind         text NOT NULL CHECK (kind IN ('GROUPE', 'GERANT')),
    country      text NOT NULL DEFAULT 'Togo',
    contact_name text,
    phone        text,
    email        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE licence (
    id                bigserial PRIMARY KEY,
    client_id         bigint NOT NULL REFERENCES client (id),
    start_date        date NOT NULL,
    end_date          date NOT NULL,
    amount_per_station bigint NOT NULL DEFAULT 0,
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RESILIEE', 'REMPLACEE')),
    terminated_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date > start_date)
);
CREATE INDEX licence_client ON licence (client_id, status);

-- ---------------------------------------------------------------- Rôles et employés
CREATE TABLE role (
    code  text PRIMARY KEY,
    label text NOT NULL,
    sort  int NOT NULL,
    station_bound boolean NOT NULL  -- les utilisateurs de ce rôle ne voient que leur station
);

CREATE TABLE app_right (
    code  text PRIMARY KEY,
    label text NOT NULL,
    sort  int NOT NULL
);

CREATE TABLE role_right (
    role_code  text NOT NULL REFERENCES role (code),
    right_code text NOT NULL REFERENCES app_right (code),
    PRIMARY KEY (role_code, right_code)
);

-- ---------------------------------------------------------------- Stations
CREATE TABLE station (
    id               bigserial PRIMARY KEY,
    code             text NOT NULL UNIQUE,
    name             text NOT NULL,
    short_name       text NOT NULL,
    type             text NOT NULL CHECK (type IN ('SUCCURSALE', 'GERANCE_LIBRE')),
    kind             text NOT NULL DEFAULT 'FIXE' CHECK (kind IN ('FIXE', 'MOBILE')),
    client_id        bigint REFERENCES client (id),
    country          text NOT NULL DEFAULT 'Togo',
    zone             text,
    address          text,
    latitude         numeric(9, 6),
    longitude        numeric(9, 6),
    opened_on        date,
    manager_id       bigint,
    mode             text NOT NULL DEFAULT 'CONVENTIONNEL' CHECK (mode IN ('CONVENTIONNEL', 'AUTONOME')),
    auto_switch      boolean NOT NULL DEFAULT true,
    mode_override_until timestamptz,           -- bascule manuelle : prioritaire jusqu'à la prochaine plage
    hub_ports        int NOT NULL DEFAULT 12 CHECK (hub_ports > 0),
    reader_port      text,
    reader_connected boolean NOT NULL DEFAULT false,
    serial_port      text,
    serial_baud      int,
    api_key_hash     text,                     -- clé du PC station (lecture du hub)
    last_seen_at     timestamptz,
    last_status      text,                     -- dernier état connu (EN_LIGNE, INSTABLE, HORS_LIGNE) : détection des changements
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE employee (
    id            bigserial PRIMARY KEY,
    matricule     text NOT NULL UNIQUE,
    first_name    text NOT NULL,
    last_name     text NOT NULL,
    email         text UNIQUE,
    phone         text,
    password_hash text,
    role_code     text NOT NULL REFERENCES role (code),
    station_id    bigint REFERENCES station (id),
    photo_path    text,
    id_doc_type   text,
    id_doc_number text,
    active        boolean NOT NULL DEFAULT true,
    created_by    bigint REFERENCES employee (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE station ADD CONSTRAINT station_manager FOREIGN KEY (manager_id) REFERENCES employee (id);

-- Plages horaires du mode conventionnel ; en dehors, la station passe en autonome
CREATE TABLE station_mode_window (
    id                bigserial PRIMARY KEY,
    station_id        bigint NOT NULL REFERENCES station (id) ON DELETE CASCADE,
    label             text NOT NULL,
    days              smallint[] NOT NULL,     -- jours ISO 1 (lundi) à 7 (dimanche)
    holidays          boolean NOT NULL DEFAULT false,
    conventional_from time NOT NULL,
    conventional_to   time NOT NULL
);

CREATE TABLE public_holiday (
    day   date PRIMARY KEY,
    label text NOT NULL
);

CREATE TABLE mode_change (
    id         bigserial PRIMARY KEY,
    station_id bigint NOT NULL REFERENCES station (id),
    from_mode  text NOT NULL,
    to_mode    text NOT NULL,
    trigger    text NOT NULL CHECK (trigger IN ('PLAGE', 'MANUEL')),
    user_id    bigint REFERENCES employee (id),
    at         timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- Produits et prix
CREATE TABLE product (
    id             bigserial PRIMARY KEY,
    code           text NOT NULL UNIQUE,
    name           text NOT NULL,
    category       text NOT NULL CHECK (category IN ('CARBURANT', 'LUBRIFIANT', 'GAZ', 'ACCESSOIRE', 'CARTE')),
    unit           text NOT NULL,
    gas_size_kg    numeric(5, 1),
    deposit_amount bigint,                     -- consigne d'une bouteille de gaz
    card_type_code text,                       -- produit « carte » : type de carte vendu
    active         boolean NOT NULL DEFAULT true
);

CREATE TABLE product_price (
    product_id bigint NOT NULL REFERENCES product (id),
    profile    text NOT NULL CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE')),
    price      bigint NOT NULL CHECK (price >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by bigint REFERENCES employee (id),
    PRIMARY KEY (product_id, profile)
);

-- ---------------------------------------------------------------- Équipement des stations
CREATE TABLE pump (
    id             bigserial PRIMARY KEY,
    station_id     bigint NOT NULL REFERENCES station (id) ON DELETE CASCADE,
    code           text NOT NULL,
    port           text NOT NULL,
    product_id     bigint NOT NULL REFERENCES product (id),
    state          text NOT NULL DEFAULT 'LIBRE' CHECK (state IN ('LIBRE', 'DISTRIBUE', 'HORS_SERVICE')),
    current_volume numeric(10, 2),
    index_total    numeric(14, 2) NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (station_id, code),
    UNIQUE (station_id, port)
);

CREATE TABLE tank (
    id               bigserial PRIMARY KEY,
    station_id       bigint NOT NULL REFERENCES station (id) ON DELETE CASCADE,
    product_id       bigint NOT NULL REFERENCES product (id),
    name             text NOT NULL,
    capacity         numeric(12, 2) NOT NULL CHECK (capacity > 0),
    volume           numeric(12, 2) NOT NULL DEFAULT 0,
    threshold_manual numeric(12, 2),
    threshold_auto   numeric(12, 2),
    lead_days        int NOT NULL DEFAULT 3,
    trend_pct        numeric(6, 1),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- Postes
CREATE TABLE shift_config (
    id         bigserial PRIMARY KEY,
    station_id bigint NOT NULL REFERENCES station (id) ON DELETE CASCADE,
    name       text NOT NULL,
    start_time time NOT NULL,
    end_time   time NOT NULL
);

-- Poste d'un employé (pompiste, chef de poste) : clôture avec remise d'espèces
CREATE TABLE employee_shift (
    id             bigserial PRIMARY KEY,
    employee_id    bigint NOT NULL REFERENCES employee (id),
    station_id     bigint NOT NULL REFERENCES station (id),
    config_id      bigint REFERENCES shift_config (id),
    pumps          text,
    status         text NOT NULL DEFAULT 'EN_POSTE' CHECK (status IN ('EN_POSTE', 'PAUSE', 'CLOS')),
    started_at     timestamptz NOT NULL DEFAULT now(),
    ended_at       timestamptz,
    expected_cash  bigint,
    remitted_cash  bigint,
    closed_by      bigint REFERENCES employee (id),
    note           text
);
CREATE UNIQUE INDEX employee_shift_open ON employee_shift (employee_id) WHERE status <> 'CLOS';

-- Remises d'espèces d'un poste (partielles en cours de poste, solde à la clôture ou après)
CREATE TABLE shift_remittance (
    id          bigserial PRIMARY KEY,
    shift_id    bigint NOT NULL REFERENCES employee_shift (id) ON DELETE CASCADE,
    amount      bigint NOT NULL CHECK (amount > 0),
    received_by bigint NOT NULL REFERENCES employee (id),
    note        text,
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shift_remittance_shift ON shift_remittance (shift_id);
CREATE INDEX employee_shift_station ON employee_shift (station_id, started_at DESC);

-- ---------------------------------------------------------------- Points de vente
CREATE TABLE pdv (
    id            bigserial PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    name          text NOT NULL,
    kind          text NOT NULL CHECK (kind IN ('PERMANENT', 'TEMPORAIRE')),
    address       text,
    latitude      numeric(9, 6),
    longitude     numeric(9, 6),
    start_date    date,
    end_date      date,
    status        text NOT NULL CHECK (status IN ('DEMANDE', 'OUVERT', 'A_CLOTURER', 'CLOS', 'REFUSE')),
    cards_planned int,
    reason        text,
    requested_by  bigint REFERENCES employee (id),
    validation_id bigint,
    last_sync_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (kind = 'PERMANENT' OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date))
);

CREATE TABLE pdv_agent (
    pdv_id      bigint NOT NULL REFERENCES pdv (id) ON DELETE CASCADE,
    employee_id bigint NOT NULL REFERENCES employee (id),
    role        text NOT NULL DEFAULT 'AGENT' CHECK (role IN ('AGENT', 'SUPERVISEUR')),
    PRIMARY KEY (pdv_id, employee_id)
);

CREATE TABLE pdv_closure (
    pdv_id         bigint PRIMARY KEY REFERENCES pdv (id),
    cards_allocated int NOT NULL,
    cards_sold     int NOT NULL,
    cards_returned int NOT NULL,
    expected_cash  bigint NOT NULL,
    counted_cash   bigint NOT NULL,
    expected_mm    bigint NOT NULL,
    counted_mm     bigint NOT NULL,
    gap            bigint NOT NULL,
    note           text,
    closed_by      bigint NOT NULL REFERENCES employee (id),
    closed_at      timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- Cartes
CREATE TABLE card_type (
    code   text PRIMARY KEY,
    label  text NOT NULL,
    price  bigint NOT NULL CHECK (price >= 0),
    sold_to_public boolean NOT NULL DEFAULT true,
    sort   int NOT NULL
);

-- Titulaire d'une carte : particulier, entreprise (flotte) ou employé
CREATE TABLE holder (
    id            bigserial PRIMARY KEY,
    kind          text NOT NULL CHECK (kind IN ('PARTICULIER', 'ENTREPRISE', 'EMPLOYE')),
    name          text NOT NULL,
    profile       text NOT NULL DEFAULT 'GRAND_PUBLIC'
                  CHECK (profile IN ('GRAND_PUBLIC', 'EMPLOYE_STATION', 'EMPLOYE_GROUPE', 'PARTENAIRE')),
    id_doc_type   text,
    id_doc_number text,
    phone         text,
    email         text,
    address       text,
    employee_id   bigint UNIQUE REFERENCES employee (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX holder_name ON holder (lower(name));

CREATE TABLE card_group (
    id         bigserial PRIMARY KEY,
    holder_id  bigint NOT NULL REFERENCES holder (id),
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (holder_id, name)
);

CREATE TABLE card_batch (
    id           bigserial PRIMARY KEY,
    type_code    text NOT NULL REFERENCES card_type (code),
    techno       text NOT NULL,
    first_number text NOT NULL,
    quantity     int NOT NULL,
    created_by   bigint REFERENCES employee (id),
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Cycle de vie : STOCK → PDV (attribuée) → A_ACTIVER (vendue / créée) → ACTIVE ; BLOQUEE / INACTIVE
CREATE TABLE card (
    id             bigserial PRIMARY KEY,
    number         text NOT NULL UNIQUE CHECK (number ~ '^[0-9]{12}$'),
    type_code      text NOT NULL REFERENCES card_type (code),
    techno         text NOT NULL CHECK (techno IN ('PUCE', 'M1')),
    status         text NOT NULL CHECK (status IN ('STOCK', 'PDV', 'A_ACTIVER', 'ACTIVE', 'BLOQUEE', 'INACTIVE')),
    holder_id      bigint REFERENCES holder (id),
    group_id       bigint REFERENCES card_group (id),
    balance        bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
    pin_hash       text,
    expires_on     date,
    vehicle_plate  text,
    vehicle_label  text,
    driver_name    text,
    batch_id       bigint REFERENCES card_batch (id),
    pdv_id         bigint REFERENCES pdv (id),
    sold_at        timestamptz,
    sold_by        bigint REFERENCES employee (id),
    sold_pdv_id    bigint REFERENCES pdv (id),
    created_by     bigint REFERENCES employee (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    issued_by      bigint REFERENCES employee (id),   -- qui a émis la carte (vente / création)
    activated_by   bigint REFERENCES employee (id),
    activated_at   timestamptz,
    blocked_reason text,
    -- garde-fou anti-fraude : la personne qui active n'est pas celle qui a émis la carte
    CONSTRAINT card_activation_four_eyes CHECK (activated_by IS NULL OR issued_by IS NULL OR activated_by <> issued_by)
);
CREATE INDEX card_holder ON card (holder_id);
CREATE INDEX card_status ON card (status);

CREATE TABLE pdv_card (
    pdv_id       bigint NOT NULL REFERENCES pdv (id),
    card_id      bigint NOT NULL REFERENCES card (id),
    allocated_at timestamptz NOT NULL DEFAULT now(),
    sold_at      timestamptz,
    returned_at  timestamptz,
    PRIMARY KEY (pdv_id, card_id)
);

CREATE TABLE card_permission (
    card_id             bigint PRIMARY KEY REFERENCES card (id) ON DELETE CASCADE,
    products            text[] NOT NULL DEFAULT ARRAY['SUPER', 'GASOIL', 'PETROLE', 'LUBRIFIANT', 'GAZ', 'ACCESSOIRE'],
    daily_limit         bigint,
    monthly_limit       bigint,
    per_tx_limit        bigint,
    days                smallint[] NOT NULL DEFAULT ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[],
    time_from           time,
    time_to             time,
    station_ids         bigint[],              -- null = toutes les stations
    autonomous_allowed  boolean NOT NULL DEFAULT true,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          bigint REFERENCES employee (id)
);

-- ---------------------------------------------------------------- Validations
CREATE TABLE validation_request (
    id            bigserial PRIMARY KEY,
    ref           text NOT NULL UNIQUE,
    kind          text NOT NULL CHECK (kind IN ('RECHARGE', 'RECHARGE_MASSE', 'OUVERTURE_PDV')),
    beneficiary   text NOT NULL,
    summary       text NOT NULL,
    amount        bigint,
    company_card  boolean NOT NULL DEFAULT false,
    status        text NOT NULL DEFAULT 'EN_ATTENTE' CHECK (status IN ('EN_ATTENTE', 'APPROUVEE', 'REFUSEE')),
    source        text,
    payment_info  text,
    target_id     bigint,                      -- recharge, lot ou point de vente concerné
    requested_by  bigint NOT NULL REFERENCES employee (id),
    requested_at  timestamptz NOT NULL DEFAULT now(),
    decided_at    timestamptz
);
CREATE INDEX validation_status ON validation_request (status, requested_at);

CREATE TABLE validation_step (
    id          bigserial PRIMARY KEY,
    request_id  bigint NOT NULL REFERENCES validation_request (id) ON DELETE CASCADE,
    step_no     int NOT NULL,
    roles       text[] NOT NULL,              -- rôles autorisés à décider cette étape
    status      text NOT NULL CHECK (status IN ('A_VENIR', 'EN_ATTENTE', 'APPROUVE', 'REFUSE')),
    decided_by  bigint REFERENCES employee (id),
    decided_at  timestamptz,
    comment     text,
    UNIQUE (request_id, step_no)
);
ALTER TABLE pdv ADD CONSTRAINT pdv_validation FOREIGN KEY (validation_id) REFERENCES validation_request (id);

-- ---------------------------------------------------------------- Recharges
CREATE TABLE recharge_batch (
    id             bigserial PRIMARY KEY,
    file_name      text,
    status         text NOT NULL DEFAULT 'BROUILLON' CHECK (status IN ('BROUILLON', 'SOUMIS', 'TERMINE', 'ANNULE')),
    payment_method text NOT NULL,
    payment_ref    text,
    created_by     bigint NOT NULL REFERENCES employee (id),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE recharge (
    id             bigserial PRIMARY KEY,
    ref            text NOT NULL UNIQUE,
    card_id        bigint NOT NULL REFERENCES card (id),
    amount         bigint NOT NULL CHECK (amount > 0),
    channel        text NOT NULL CHECK (channel IN ('UNITAIRE', 'DISTANCE', 'MASSE')),
    payment_method text NOT NULL CHECK (payment_method IN ('ESPECES', 'MOBILE_MONEY', 'VIREMENT', 'CARTE_BANCAIRE')),
    payment_ref    text,
    status         text NOT NULL CHECK (status IN ('EN_VALIDATION', 'CREDITEE', 'REFUSEE')),
    batch_id       bigint REFERENCES recharge_batch (id),
    validation_id  bigint REFERENCES validation_request (id),
    station_id     bigint REFERENCES station (id),
    pdv_id         bigint REFERENCES pdv (id),
    requested_by   bigint NOT NULL REFERENCES employee (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    credited_at    timestamptz
);
CREATE INDEX recharge_card ON recharge (card_id);

CREATE TABLE recharge_batch_line (
    id           bigserial PRIMARY KEY,
    batch_id     bigint NOT NULL REFERENCES recharge_batch (id) ON DELETE CASCADE,
    line_no      int NOT NULL,
    card_number  text NOT NULL,
    card_id      bigint REFERENCES card (id),
    holder_label text,
    amount       bigint,
    check_status text NOT NULL CHECK (check_status IN ('PRETE', 'VALIDATION_REQUISE', 'CARTE_INTROUVABLE', 'CARTE_INACTIVE', 'MONTANT_INVALIDE', 'DOUBLON')),
    included     boolean NOT NULL DEFAULT true,
    recharge_id  bigint REFERENCES recharge (id)
);

-- ---------------------------------------------------------------- Ventes
CREATE TABLE sale (
    id               bigserial PRIMARY KEY,
    uuid             uuid NOT NULL UNIQUE,      -- identifiant fourni par le poste (idempotence, mode hors ligne)
    at               timestamptz NOT NULL DEFAULT now(),
    kind             text NOT NULL CHECK (kind IN ('CARBURANT', 'BOUTIQUE', 'CARTE', 'ECHANGE_GAZ')),
    channel          text NOT NULL CHECK (channel IN ('POMPE', 'BOUTIQUE', 'DISTRIBUTEUR_GAZ', 'PDV', 'LIVRAISON')),
    station_id       bigint REFERENCES station (id),
    pdv_id           bigint REFERENCES pdv (id),
    pump_id          bigint REFERENCES pump (id),
    mode             text CHECK (mode IN ('CONVENTIONNEL', 'AUTONOME')),
    employee_id      bigint REFERENCES employee (id),
    employee_shift_id bigint REFERENCES employee_shift (id),
    payment_method   text NOT NULL CHECK (payment_method IN ('ESPECES', 'MOBILE_MONEY', 'CARTE', 'VIREMENT')),
    payment_ref      text,
    card_id          bigint REFERENCES card (id),
    buyer_profile    text NOT NULL DEFAULT 'GRAND_PUBLIC',
    total            bigint NOT NULL CHECK (total >= 0),
    synced_at        timestamptz,               -- renseigné pour une opération reçue en différé
    CHECK (station_id IS NOT NULL OR pdv_id IS NOT NULL)
);
CREATE INDEX sale_station_at ON sale (station_id, at);
CREATE INDEX sale_pdv ON sale (pdv_id);
CREATE INDEX sale_employee ON sale (employee_id, at);

CREATE TABLE sale_line (
    id         bigserial PRIMARY KEY,
    sale_id    bigint NOT NULL REFERENCES sale (id) ON DELETE CASCADE,
    product_id bigint NOT NULL REFERENCES product (id),
    quantity   numeric(12, 2) NOT NULL CHECK (quantity > 0),
    unit_price bigint NOT NULL,
    amount     bigint NOT NULL,
    card_id    bigint REFERENCES card (id)          -- carte vendue (vente de carte)
);

-- Refus de paiement par carte (permissions, solde…)
CREATE TABLE card_refusal (
    id         bigserial PRIMARY KEY,
    card_id    bigint NOT NULL REFERENCES card (id),
    station_id bigint REFERENCES station (id),
    at         timestamptz NOT NULL DEFAULT now(),
    amount     bigint,
    reason     text NOT NULL,
    detail     text
);

-- Mouvements de solde des cartes
CREATE TABLE card_movement (
    id            bigserial PRIMARY KEY,
    card_id       bigint NOT NULL REFERENCES card (id),
    at            timestamptz NOT NULL DEFAULT now(),
    kind          text NOT NULL CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT')),
    amount        bigint NOT NULL,
    balance_after bigint NOT NULL,
    station_id    bigint REFERENCES station (id),
    sale_id       bigint REFERENCES sale (id),
    recharge_id   bigint REFERENCES recharge (id),
    detail        text
);
CREATE INDEX card_movement_card ON card_movement (card_id, at);

-- ---------------------------------------------------------------- Stocks
-- Stock d'un produit (hors carburant, suivi par les cuves) dans une station ou un point de vente
CREATE TABLE stock_level (
    id               bigserial PRIMARY KEY,
    station_id       bigint REFERENCES station (id),
    pdv_id           bigint REFERENCES pdv (id),
    product_id       bigint NOT NULL REFERENCES product (id),
    quantity         numeric(12, 2) NOT NULL DEFAULT 0,
    capacity         numeric(12, 2),
    threshold_manual numeric(12, 2),
    threshold_auto   numeric(12, 2),
    lead_days        int NOT NULL DEFAULT 3,
    trend_pct        numeric(6, 1),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK ((station_id IS NULL) <> (pdv_id IS NULL))
);
CREATE UNIQUE INDEX stock_level_station ON stock_level (station_id, product_id) WHERE station_id IS NOT NULL;
CREATE UNIQUE INDEX stock_level_pdv ON stock_level (pdv_id, product_id) WHERE pdv_id IS NOT NULL;

CREATE TABLE stock_movement (
    id          bigserial PRIMARY KEY,
    station_id  bigint REFERENCES station (id),
    pdv_id      bigint REFERENCES pdv (id),
    product_id  bigint NOT NULL REFERENCES product (id),
    tank_id     bigint REFERENCES tank (id),
    quantity    numeric(12, 2) NOT NULL,       -- positif : entrée, négatif : sortie
    kind        text NOT NULL CHECK (kind IN ('ENTREE', 'VENTE', 'ECHANGE', 'AJUSTEMENT', 'INVENTAIRE', 'LIVRAISON')),
    ref         text,
    note        text,
    user_id     bigint REFERENCES employee (id),
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX stock_movement_lookup ON stock_movement (station_id, product_id, at);

-- Parc de bouteilles de gaz d'une station, par format.
-- Les bouteilles pleines sont le stock du produit (stock_level.quantity).
CREATE TABLE gas_bottle_stock (
    station_id     bigint NOT NULL REFERENCES station (id),
    product_id     bigint NOT NULL REFERENCES product (id),
    empty_count    int NOT NULL DEFAULT 0 CHECK (empty_count >= 0),
    customer_count int NOT NULL DEFAULT 0 CHECK (customer_count >= 0),
    damaged_count  int NOT NULL DEFAULT 0 CHECK (damaged_count >= 0),
    PRIMARY KEY (station_id, product_id)
);

CREATE TABLE purchase (
    id         bigserial PRIMARY KEY,
    ref        text NOT NULL UNIQUE,
    supplier   text NOT NULL,
    station_id bigint REFERENCES station (id),
    pdv_id     bigint REFERENCES pdv (id),
    total      bigint NOT NULL DEFAULT 0,
    note       text,
    created_by bigint NOT NULL REFERENCES employee (id),
    at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE purchase_line (
    id          bigserial PRIMARY KEY,
    purchase_id bigint NOT NULL REFERENCES purchase (id) ON DELETE CASCADE,
    product_id  bigint NOT NULL REFERENCES product (id),
    quantity    numeric(12, 2) NOT NULL CHECK (quantity > 0),
    unit_cost   bigint NOT NULL DEFAULT 0
);

CREATE TABLE stock_alert (
    id          bigserial PRIMARY KEY,
    station_id  bigint REFERENCES station (id),
    pdv_id      bigint REFERENCES pdv (id),
    product_id  bigint NOT NULL REFERENCES product (id),
    tank_id     bigint REFERENCES tank (id),
    level       numeric(12, 2) NOT NULL,
    threshold   numeric(12, 2) NOT NULL,
    severity    text NOT NULL CHECK (severity IN ('CRITIQUE', 'BAS')),
    raised_at   timestamptz NOT NULL DEFAULT now(),
    emailed_at  timestamptz,
    resolved_at timestamptz
);
CREATE INDEX stock_alert_open ON stock_alert (resolved_at) WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------- Rapports
CREATE TABLE daily_report (
    id           bigserial PRIMARY KEY,
    station_id   bigint NOT NULL REFERENCES station (id),
    day          date NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    data         jsonb NOT NULL,
    status       text NOT NULL DEFAULT 'A_COMMENTER' CHECK (status IN ('A_COMMENTER', 'COMMENTE', 'INCOMPLET')),
    comment      text,
    comment_by   bigint REFERENCES employee (id),
    comment_at   timestamptz,
    validated_at timestamptz,
    UNIQUE (station_id, day)
);

-- ---------------------------------------------------------------- Journal, emails, temps réel
CREATE TABLE operation_log (
    id          bigserial PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor_id    bigint REFERENCES employee (id),
    station_id  bigint REFERENCES station (id),
    pdv_id      bigint REFERENCES pdv (id),
    type        text NOT NULL,
    category    text NOT NULL CHECK (category IN ('VENTES_CARBURANT', 'RECHARGES', 'VENTES_CARTES', 'VENTES_BOUTIQUE',
                                                  'VALIDATIONS', 'MODIFICATIONS', 'SYSTEME', 'LIVRAISONS')),
    title       text NOT NULL,
    detail      text,
    amount      bigint
);
CREATE INDEX operation_log_at ON operation_log (at DESC);
CREATE INDEX operation_log_station ON operation_log (station_id, at DESC);

CREATE TABLE email_outbox (
    id         bigserial PRIMARY KEY,
    recipients text[] NOT NULL,
    subject    text NOT NULL,
    body       text NOT NULL,
    kind       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at    timestamptz,
    error      text
);

-- ---------------------------------------------------------------- Zen Mobility et suivi GPS
CREATE TABLE delivery_order (
    id                 bigserial PRIMARY KEY,
    ref                text NOT NULL UNIQUE,
    holder_id          bigint NOT NULL REFERENCES holder (id),
    card_id            bigint REFERENCES card (id),
    product_id         bigint NOT NULL REFERENCES product (id),
    quantity           numeric(10, 2) NOT NULL CHECK (quantity >= 1000),
    unit_price         bigint NOT NULL,
    address            text NOT NULL,
    latitude           numeric(9, 6),
    longitude          numeric(9, 6),
    requested_for      timestamptz,
    status             text NOT NULL DEFAULT 'DEMANDEE'
                       CHECK (status IN ('DEMANDEE', 'VALIDEE', 'EN_ROUTE', 'LIVREE', 'ANNULEE')),
    source_station_id  bigint REFERENCES station (id),
    agent_id           bigint REFERENCES employee (id),
    vehicle            text,
    delivered_quantity numeric(10, 2),
    payment_method     text NOT NULL DEFAULT 'VIREMENT' CHECK (payment_method IN ('CARTE', 'ESPECES', 'MOBILE_MONEY', 'VIREMENT')),
    note               text,
    cancel_reason      text,
    created_by         bigint REFERENCES employee (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    departed_at        timestamptz,
    delivered_at       timestamptz,
    sale_id            bigint REFERENCES sale (id)
);
CREATE INDEX delivery_order_status ON delivery_order (status, created_at DESC);

-- Positions envoyées par les téléphones des livreurs et des stations mobiles
CREATE TABLE location_ping (
    id          bigserial PRIMARY KEY,
    subject     text NOT NULL CHECK (subject IN ('LIVREUR', 'STATION_MOBILE')),
    subject_id  bigint NOT NULL,
    delivery_id bigint REFERENCES delivery_order (id),
    latitude    numeric(9, 6) NOT NULL,
    longitude   numeric(9, 6) NOT NULL,
    accuracy_m  numeric(7, 1),
    speed_kmh   numeric(6, 1),
    heading     numeric(5, 1),
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX location_ping_subject ON location_ping (subject, subject_id, at DESC);

-- ---------------------------------------------------------------- Références lisibles (VAL-2026-1001…)
CREATE SEQUENCE ref_validation_seq START 1001;
CREATE SEQUENCE ref_recharge_seq START 1001;
CREATE SEQUENCE ref_purchase_seq START 1001;
CREATE SEQUENCE ref_delivery_seq START 1001;
CREATE SEQUENCE ref_pdv_seq START 101;
