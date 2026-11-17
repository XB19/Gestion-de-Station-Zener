-- =====================================================================
-- V10 : notifications et rappels (réunion du 17 novembre 2026)
-- Un seul point d'entrée (Notifier) : chaque événement produit une notification par destinataire
-- (cloche de l'application, lu / non lu) et, selon la règle, un email. Les rappels datés sont des
-- lignes à échéance : ceux manqués pendant un arrêt du serveur partent au redémarrage.
-- Niveaux des cuves de carburant : 50 %, 40 %, 30 %, 5 % de la capacité, priorité croissante.
-- =====================================================================

CREATE TABLE notification_rule (
    kind     text PRIMARY KEY,
    label    text NOT NULL,
    roles    text[] NOT NULL DEFAULT '{}',   -- rôles destinataires (ceux rattachés à une station : seulement la leur)
    email    boolean NOT NULL DEFAULT false, -- envoi d'un email en plus de la notification
    active   boolean NOT NULL DEFAULT true,
    sort     int NOT NULL DEFAULT 100
);

INSERT INTO notification_rule (kind, label, roles, email, sort) VALUES
    ('CUVE_50',        'Cuve sous 50 %',                       '{GERANT,CHEF_POSTE}',              false, 1),
    ('CUVE_40',        'Cuve sous 40 %',                       '{GERANT,CHEF_POSTE}',              true,  2),
    ('CUVE_30',        'Cuve sous 30 % (commande à passer)',   '{GERANT,ADMIN,DAF}',               true,  3),
    ('CUVE_5',         'Cuve sous 5 % (critique)',             '{GERANT,CHEF_POSTE,ADMIN,DAF}',    true,  4),
    ('ALERTE_STOCK',   'Stock d''article sous le seuil',       '{GERANT}',                         true,  5),
    ('VALIDATION',     'Validation à faire',                   '{DAF}',                            true,  6),
    ('VALIDATION_DECISION', 'Décision sur ma demande',         '{}',                               true,  7),
    ('LICENCE',        'Échéance de licence',                  '{ADMIN,DAF}',                      true,  8),
    ('CLUB',           'Carte Club : échéance et renouvellement', '{ADMIN,COMPTABLE}',             false, 9),
    ('SUSPICION',      'Activité suspecte sur une carte',      '{COMPTABLE,DAF}',                  true, 10),
    ('ADMIN_DEMANDE',  'Action sensible à approuver',          '{ADMIN,DAF}',                      true, 11),
    ('LIVRAISON',      'Livraison Zen Mobility',               '{}',                               true, 12),
    ('PDV',            'Point de vente',                       '{ADMIN,DAF}',                      false, 13),
    ('CONTRAT',        'Contrat de gérance libre',             '{ADMIN,DAF}',                      true, 14),
    ('ORDRE_TRAVAIL',  'Ordre de travail atelier',             '{GERANT,CHEF_POSTE}',              false, 15),
    ('BIENVENUE',      'Bienvenue et mise en route',           '{}',                               true, 16);

CREATE TABLE notification (
    id          bigserial PRIMARY KEY,
    employee_id bigint NOT NULL REFERENCES employee (id),
    kind        text NOT NULL,
    priority    text NOT NULL DEFAULT 'INFO' CHECK (priority IN ('INFO', 'ATTENTION', 'URGENT', 'CRITIQUE')),
    title       text NOT NULL,
    body        text,
    link        text,                         -- route de l'application (#/…)
    station_id  bigint REFERENCES station (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz
);
CREATE INDEX notification_employee ON notification (employee_id, created_at DESC);
CREATE INDEX notification_unread ON notification (employee_id) WHERE read_at IS NULL;

-- Rappels datés (licences, cartes Club, contrats de gérance…) : envoyés une fois l'échéance atteinte
CREATE TABLE reminder (
    id         bigserial PRIMARY KEY,
    kind       text NOT NULL,                 -- règle de notification utilisée
    ref_type   text NOT NULL,                 -- LICENCE, CARTE, CONTRAT
    ref_id     bigint NOT NULL,
    due_on     date NOT NULL,
    priority   text NOT NULL DEFAULT 'ATTENTION',
    title      text NOT NULL,
    body       text,
    link       text,
    station_id bigint REFERENCES station (id),
    extra_recipients bigint[] NOT NULL DEFAULT '{}',
    extra_emails text[] NOT NULL DEFAULT '{}',
    sent_at    timestamptz,
    cancelled_at timestamptz,
    UNIQUE (ref_type, ref_id, kind, due_on)
);
CREATE INDEX reminder_due ON reminder (due_on) WHERE sent_at IS NULL AND cancelled_at IS NULL;

-- Niveau d'alerte en cours d'une cuve (palier le plus bas déjà notifié ; remis à zéro au-dessus de 50 %)
ALTER TABLE tank
    ADD COLUMN alert_tier      int,
    ADD COLUMN alert_notified_at timestamptz;

INSERT INTO setting (key, value) VALUES
    ('alerts.tank_levels',           '50,40,30,5'),   -- paliers des cuves en % de la capacité
    ('alerts.critical_repeat_hours', '4')             -- sous le dernier palier : rappel toutes les 4 h jusqu'à la livraison
ON CONFLICT (key) DO NOTHING;
