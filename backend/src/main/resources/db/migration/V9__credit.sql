-- =====================================================================
-- V9 : cartes de crédit et factures mensuelles (phase 2e, modèle LTStation simplifié)
--   group_card_infos.is_credit_card / credit_lines → card.credit_limit (0 = carte prépayée) ; le solde d'une
--                                                    carte de crédit peut descendre jusqu'à − credit_limit
--   date_account / date_repayment                   → paramètres credit.bill_day / credit.payment_days
--   credit_card_bills                               → credit_bill (par client et par mois : report du solde impayé,
--                                                    consommation, montant dû, réglé, échéance, statut)
--   credit_card_bill_details                        → credit_bill_line (une ligne par achat payé à crédit)
--   paiement (payment_id, date_payment)             → credit_payment (règlements partiels possibles)
-- La facture est établie par client (et non par carte) : un seul relevé pour toutes ses cartes de crédit.
-- =====================================================================

ALTER TABLE card ADD COLUMN credit_limit bigint NOT NULL DEFAULT 0 CHECK (credit_limit >= 0);
ALTER TABLE card DROP CONSTRAINT card_balance_check;
ALTER TABLE card ADD CONSTRAINT card_balance_check CHECK (balance >= -credit_limit);

CREATE TABLE credit_bill (
    id            bigserial PRIMARY KEY,
    ref           text NOT NULL UNIQUE,
    holder_id     bigint NOT NULL REFERENCES holder (id),
    period_start  date NOT NULL,
    period_end    date NOT NULL,                 -- inclus
    previous_due  bigint NOT NULL DEFAULT 0,     -- impayé des relevés précédents reporté
    consumption   bigint NOT NULL DEFAULT 0,     -- achats à crédit de la période
    amount_due    bigint NOT NULL,               -- previous_due + consumption
    paid          bigint NOT NULL DEFAULT 0,
    due_date      date NOT NULL,
    status        text NOT NULL DEFAULT 'A_PAYER' CHECK (status IN ('A_PAYER', 'PARTIELLE', 'PAYEE', 'EN_RETARD', 'REPORTEE')),
    generated_at  timestamptz NOT NULL DEFAULT now(),
    paid_at       timestamptz,
    note          text,
    UNIQUE (holder_id, period_start)
);
CREATE INDEX credit_bill_status ON credit_bill (status, due_date);

CREATE TABLE credit_bill_line (
    id      bigserial PRIMARY KEY,
    bill_id bigint NOT NULL REFERENCES credit_bill (id) ON DELETE CASCADE,
    card_id bigint NOT NULL REFERENCES card (id),
    sale_id bigint REFERENCES sale (id),
    at      timestamptz NOT NULL,
    amount  bigint NOT NULL
);
CREATE INDEX credit_bill_line_bill ON credit_bill_line (bill_id);

CREATE TABLE credit_payment (
    id             bigserial PRIMARY KEY,
    bill_id        bigint NOT NULL REFERENCES credit_bill (id),
    amount         bigint NOT NULL CHECK (amount > 0),
    payment_method text NOT NULL CHECK (payment_method IN ('ESPECES', 'MOBILE_MONEY', 'VIREMENT', 'CHEQUE', 'CARTE_BANCAIRE')),
    payment_ref    text,
    at             timestamptz NOT NULL DEFAULT now(),
    employee_id    bigint REFERENCES employee (id),
    note           text
);
CREATE INDEX credit_payment_bill ON credit_payment (bill_id, at);

ALTER TABLE card_movement DROP CONSTRAINT card_movement_kind_check;
ALTER TABLE card_movement ADD CONSTRAINT card_movement_kind_check
    CHECK (kind IN ('VENTE', 'RECHARGE', 'AJUSTEMENT', 'DOTATION', 'REPRISE', 'TRANSFERT', 'FIDELITE', 'REGLEMENT'));

CREATE SEQUENCE ref_bill_seq START 1001;

INSERT INTO app_right (code, label, sort) VALUES ('CREDIT', 'Crédit clients et factures', 28);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'CREDIT'), ('COMPTABLE', 'CREDIT'), ('DAF', 'CREDIT');

INSERT INTO setting (key, value) VALUES
    ('credit.bill_day',     '1'),    -- relevé du mois précédent établi ce jour-là
    ('credit.payment_days', '15')    -- échéance : jours après l'établissement du relevé
ON CONFLICT (key) DO NOTHING;
