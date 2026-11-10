-- =====================================================================
-- V3 : module Gestionnaire (extractions de données)
-- Droit EXTRACTIONS (donné à l'Admin ; le gestionnaire a un compte Admin),
-- catégorie de journal pour tracer chaque export, index des filtres.
-- =====================================================================

INSERT INTO app_right (code, label, sort) VALUES ('EXTRACTIONS', 'Extractions de données (Gestionnaire)', 25);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'EXTRACTIONS');

ALTER TABLE operation_log DROP CONSTRAINT operation_log_category_check;
ALTER TABLE operation_log ADD CONSTRAINT operation_log_category_check
    CHECK (category IN ('VENTES_CARBURANT', 'RECHARGES', 'VENTES_CARTES', 'VENTES_BOUTIQUE',
                        'VALIDATIONS', 'MODIFICATIONS', 'SYSTEME', 'LIVRAISONS', 'EXTRACTIONS'));

-- Recherche d'une carte par ses 5 derniers chiffres
CREATE INDEX card_last5 ON card (right(number, 5));
CREATE INDEX card_group_ix ON card (group_id) WHERE group_id IS NOT NULL;

-- Ventes : par carte, par période, par poste ; lignes par vente et par produit
CREATE INDEX sale_card_at ON sale (card_id, at) WHERE card_id IS NOT NULL;
CREATE INDEX sale_at ON sale (at);
CREATE INDEX sale_shift ON sale (employee_shift_id) WHERE employee_shift_id IS NOT NULL;
CREATE INDEX sale_line_sale ON sale_line (sale_id);
CREATE INDEX sale_line_product ON sale_line (product_id, sale_id);

-- Mouvements, recharges, ordres de travail, journal, postes
CREATE INDEX card_movement_at ON card_movement (at);
CREATE INDEX recharge_created ON recharge (created_at);
CREATE INDEX delivery_order_holder ON delivery_order (holder_id, created_at);
CREATE INDEX operation_log_actor ON operation_log (actor_id, at DESC);
CREATE INDEX employee_shift_employee ON employee_shift (employee_id, started_at DESC);
