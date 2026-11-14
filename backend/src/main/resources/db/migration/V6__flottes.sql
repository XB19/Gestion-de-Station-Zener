-- =====================================================================
-- V6 : flottes (phase 2b, version simple)
-- Une flotte est un groupe de cartes d'une entreprise (card_group, V1) : on y attribue des cartes et on la
-- retrouve dans les filtres et les extractions. Pas de compte flotte, de véhicules ni de chauffeurs pour
-- l'instant (le véhicule et le chauffeur restent les champs texte de la carte).
-- =====================================================================

INSERT INTO app_right (code, label, sort) VALUES ('FLOTTES', 'Flottes (groupes de cartes)', 26);
INSERT INTO role_right (role_code, right_code) VALUES ('ADMIN', 'FLOTTES');

ALTER TABLE card_group
    ADD COLUMN contact_name text,
    ADD COLUMN phone        text,
    ADD COLUMN note         text,
    ADD COLUMN active       boolean NOT NULL DEFAULT true;

-- attribution d'une carte à une flotte (ou retrait) : événement de la carte
ALTER TABLE card_event DROP CONSTRAINT card_event_kind_check;
ALTER TABLE card_event ADD CONSTRAINT card_event_kind_check
    CHECK (kind IN ('EMISSION', 'MODIFICATION', 'PERMISSIONS', 'ACTIVATION', 'BLOCAGE', 'DEBLOCAGE', 'PIN', 'ATTRIBUTION_PDV',
                    'RETOUR_STOCK', 'VENTE_PDV', 'DESACTIVATION', 'REMPLACEMENT', 'AFFECTATION'));
