-- =====================================================================
-- V11 : traces, limites de l'Admin et validation par le DAF (réunion du 17 novembre 2026)
-- - Chaque entrée du journal garde d'où l'action a été faite : adresse IP, appareil (navigateur),
--   session de connexion ; les modifications sensibles gardent l'avant / après et le motif.
-- - Connexions : sessions (une par jeton, révocables) et tentatives (réussies ou non).
-- - Actions sensibles soumises à une seconde personne (Admin ou DAF, jamais le demandeur) :
--   droits d'un rôle, paramètres financiers, ajustement manuel de solde, compte Admin / DAF.
-- - Validation des montants élevés : par le DAF seul (une étape), avec délégation possible
--   à un autre utilisateur ou à un rôle, pour une période (refonte complète prévue plus tard).
-- =====================================================================

ALTER TABLE operation_log
    ADD COLUMN ip          text,
    ADD COLUMN user_agent  text,
    ADD COLUMN session_id  uuid,
    ADD COLUMN before_value jsonb,
    ADD COLUMN after_value  jsonb,
    ADD COLUMN reason      text;
ALTER TABLE operation_log DROP CONSTRAINT operation_log_category_check;
ALTER TABLE operation_log ADD CONSTRAINT operation_log_category_check
    CHECK (category IN ('VENTES_CARBURANT', 'RECHARGES', 'VENTES_CARTES', 'VENTES_BOUTIQUE', 'VALIDATIONS', 'MODIFICATIONS',
                        'SYSTEME', 'LIVRAISONS', 'EXTRACTIONS', 'SECURITE'));
-- le journal ne se modifie ni ne s'efface
CREATE FUNCTION operation_log_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Le journal d''opérations ne peut être ni modifié ni supprimé';
END $$;
CREATE TRIGGER operation_log_no_change BEFORE UPDATE OR DELETE ON operation_log
    FOR EACH ROW EXECUTE FUNCTION operation_log_immutable();

CREATE TABLE auth_session (
    id           uuid PRIMARY KEY,
    employee_id  bigint NOT NULL REFERENCES employee (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    ip           text,
    user_agent   text,
    revoked_at   timestamptz,
    revoked_by   bigint REFERENCES employee (id)
);
CREATE INDEX auth_session_employee ON auth_session (employee_id, created_at DESC);

CREATE TABLE login_attempt (
    id          bigserial PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    email       text NOT NULL,
    employee_id bigint REFERENCES employee (id),
    success     boolean NOT NULL,
    reason      text,
    ip          text,
    user_agent  text,
    session_id  uuid
);
CREATE INDEX login_attempt_at ON login_attempt (at DESC);
CREATE INDEX login_attempt_employee ON login_attempt (employee_id, at DESC);

-- Demandes d'actions sensibles : le contenu à appliquer est conservé jusqu'à la décision
ALTER TABLE validation_request ADD COLUMN payload jsonb;
ALTER TABLE validation_request DROP CONSTRAINT validation_request_kind_check;
ALTER TABLE validation_request ADD CONSTRAINT validation_request_kind_check
    CHECK (kind IN ('RECHARGE', 'RECHARGE_MASSE', 'OUVERTURE_PDV', 'DROITS_ROLE', 'PARAMETRES', 'AJUSTEMENT_SOLDE', 'COMPTE_SENSIBLE'));

-- Validation au nom d'un autre (délégation)
ALTER TABLE validation_step ADD COLUMN delegated_from bigint REFERENCES employee (id);

CREATE TABLE validation_delegation (
    id             bigserial PRIMARY KEY,
    from_employee  bigint NOT NULL REFERENCES employee (id),   -- titulaire du pouvoir (le DAF)
    to_employee    bigint REFERENCES employee (id),            -- délégué : un utilisateur…
    to_role        text REFERENCES role (code),                -- … ou tous les membres d'un rôle
    kinds          text[],                                     -- types de demandes (nul : tous)
    max_amount     bigint CHECK (max_amount IS NULL OR max_amount > 0),
    starts_at      timestamptz NOT NULL DEFAULT now(),
    ends_at        timestamptz,
    note           text,
    created_by     bigint NOT NULL REFERENCES employee (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz,
    revoked_by     bigint REFERENCES employee (id),
    CHECK ((to_employee IS NULL) <> (to_role IS NULL)),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX validation_delegation_active ON validation_delegation (from_employee) WHERE revoked_at IS NULL;

INSERT INTO app_right (code, label, sort) VALUES
    ('AUDIT', 'Consulter les connexions et les traces', 32);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'AUDIT'), ('DAF', 'AUDIT');

INSERT INTO setting (key, value) VALUES
    ('validation.approver_roles', 'DAF'),   -- rôles qui valident les montants au-dessus du palier
    ('security.sensitive_settings', 'validation.threshold,validation.approver_roles,card.replacement_fee,club.price,club.initial_deposit,club.renewal_fee,club.auto_renew_spend,security.sensitive_settings')
ON CONFLICT (key) DO NOTHING;
UPDATE setting SET value = 'false' WHERE key = 'validation.company_needs_daf';

-- Première connexion : mot de passe provisoire à changer, visite guidée du rôle (mise en route)
ALTER TABLE employee
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN password_changed_at  timestamptz,
    ADD COLUMN onboarded_at         timestamptz;
UPDATE employee SET onboarded_at = now();   -- comptes existants : déjà en service
