-- =====================================================================
-- V13 : contrôles des soldes et des recharges (réunion du 17 novembre 2026)
-- Détection d'activités suspectes, sans blocage automatique : chaque cas ouvre une alerte de contrôle à examiner
-- par la comptabilité ou le DAF (ouverte → en revue → close, justifiée ou non, avec une note).
-- Règles (seuils dans Paramètres) :
--   FRACTIONNEMENT        plusieurs recharges juste sous le palier de validation en 24 h (contournement de la validation)
--   RECHARGE_ATYPIQUE     recharge très supérieure aux habitudes du titulaire
--   RECHARGES_RAPPROCHEES nombreuses recharges en 24 h sur une carte
--   HORS_HORAIRES         recharge la nuit
--   MEME_OPERATEUR        le même opérateur recharge souvent la même carte
--   SOLDE_DORMANT         solde élevé sans aucun achat depuis longtemps
--   ACCUMULATION          recharges bien supérieures aux dépenses sur 90 jours
--   VA_ET_VIENT           transferts dans les deux sens entre deux cartes
-- =====================================================================

CREATE TABLE suspicion (
    id          bigserial PRIMARY KEY,
    card_id     bigint NOT NULL REFERENCES card (id),
    rule        text NOT NULL,
    severity    text NOT NULL CHECK (severity IN ('MOYENNE', 'ELEVEE')),
    title       text NOT NULL,
    detail      text,
    amount      bigint,
    status      text NOT NULL DEFAULT 'OUVERTE' CHECK (status IN ('OUVERTE', 'EN_REVUE', 'CLOSE')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    assigned_to bigint REFERENCES employee (id),
    closed_by   bigint REFERENCES employee (id),
    closed_at   timestamptz,
    justified   boolean,                       -- close : activité justifiée (true) ou anomalie confirmée (false)
    resolution  text
);
-- une seule alerte non close par carte et par règle
CREATE UNIQUE INDEX suspicion_open ON suspicion (card_id, rule) WHERE status <> 'CLOSE';
CREATE INDEX suspicion_status ON suspicion (status, created_at DESC);

INSERT INTO app_right (code, label, sort) VALUES ('CONTROLES', 'Contrôles des soldes et recharges', 33);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'CONTROLES'), ('COMPTABLE', 'CONTROLES'), ('DAF', 'CONTROLES');

INSERT INTO setting (key, value) VALUES
    ('controls.split_ratio',         '0.8'),       -- recharge entre 80 % et 100 % du palier…
    ('controls.split_count',         '3'),         -- … au moins 3 fois en 24 h sur une carte
    ('controls.atypical_factor',     '5'),         -- recharge ≥ 5 × la recharge moyenne des 90 jours précédents…
    ('controls.atypical_min',        '300000'),    -- … et d'au moins 300 000 F
    ('controls.rapid_count',         '5'),         -- 5 recharges ou plus en 24 h
    ('controls.night_from',          '22:00'),
    ('controls.night_to',            '05:00'),
    ('controls.same_operator_count', '5'),         -- même opérateur, même carte, 5 recharges en 7 jours
    ('controls.dormant_balance',     '1000000'),   -- solde ≥ 1 000 000 F…
    ('controls.dormant_days',        '30'),        -- … sans achat depuis 30 jours
    ('controls.accumulation_ratio',  '3'),         -- recharges ≥ 3 × dépenses sur 90 jours…
    ('controls.accumulation_min',    '1000000')    -- … et recharges ≥ 1 000 000 F
ON CONFLICT (key) DO NOTHING;
