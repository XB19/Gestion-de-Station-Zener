-- Données de référence (nécessaires en production comme en démonstration)

INSERT INTO role (code, label, sort, station_bound) VALUES
    ('ADMIN',      'Admin',          1, false),
    ('GERANT',     'Gérant',         2, true),
    ('CHEF_POSTE', 'Chef de poste',  3, true),
    ('POMPISTE',   'Pompiste',       4, true),
    ('COMPTABLE',  'Comptable',      5, false),
    ('DAF',        'DAF',            6, false),
    ('AGENT_PDV',  'Agent PDV',      7, false),
    ('LIVREUR',    'Livreur',        8, false);

-- Les 7 premiers droits sont ceux de la matrice de l'écran « Employés & rôles »
INSERT INTO app_right (code, label, sort) VALUES
    ('STATIONS_GERER',            'Créer et attribuer stations',               1),
    ('STATION_EXPLOITER',         'Gérer la station',                          2),
    ('VENTES_POMPE',              'Ventes à la pompe',                         3),
    ('RAPPORTS',                  'Rapports journaliers',                      4),
    ('TRANSACTIONS',              'Historique transactions',                   5),
    ('VALIDER_MONTANTS',          'Valider montants élevés',                   6),
    ('VALIDER_CARTES_ENTREPRISE', 'Valider cartes entreprises',                7),
    ('CARTES_GERER',              'Créer et modifier les cartes',              8),
    ('CARTES_ACTIVER',            'Activer les cartes',                        9),
    ('CARTES_EMPLOYES',           'Créer les cartes des employés',            10),
    ('RECHARGER',                 'Recharger les cartes',                     11),
    ('VENTES_BOUTIQUE',           'Ventes boutique et gaz',                   12),
    ('CLOTURER_POSTE',            'Clôturer les postes',                      13),
    ('STOCKS',                    'Gérer les stocks et les achats',           14),
    ('PRIX',                      'Modifier les prix',                        15),
    ('PDV_GERER',                 'Gérer les points de vente',                16),
    ('PDV_VALIDER',               'Valider l''ouverture d''un point de vente', 17),
    ('PDV_VENDRE',                'Vendre des cartes en point de vente',      18),
    ('EMPLOYES',                  'Gérer les employés',                       19),
    ('CLIENTS_LICENCES',          'Clients et licences',                      20),
    ('JOURNAL',                   'Journal d''opérations',                    21),
    ('LIVRAISONS',                'Zen Mobility : commandes',                 22),
    ('LIVRER',                    'Zen Mobility : effectuer les livraisons',  23),
    ('PARAMETRES',                'Paramètres',                               24);

INSERT INTO role_right (role_code, right_code) VALUES
    ('ADMIN', 'STATIONS_GERER'), ('ADMIN', 'STATION_EXPLOITER'), ('ADMIN', 'RAPPORTS'), ('ADMIN', 'TRANSACTIONS'),
    ('ADMIN', 'CARTES_GERER'), ('ADMIN', 'CARTES_ACTIVER'), ('ADMIN', 'CARTES_EMPLOYES'), ('ADMIN', 'RECHARGER'),
    ('ADMIN', 'STOCKS'), ('ADMIN', 'PRIX'), ('ADMIN', 'PDV_GERER'), ('ADMIN', 'PDV_VALIDER'), ('ADMIN', 'PDV_VENDRE'),
    ('ADMIN', 'EMPLOYES'), ('ADMIN', 'CLIENTS_LICENCES'), ('ADMIN', 'JOURNAL'), ('ADMIN', 'LIVRAISONS'),
    ('ADMIN', 'PARAMETRES'),

    ('GERANT', 'STATION_EXPLOITER'), ('GERANT', 'RAPPORTS'), ('GERANT', 'TRANSACTIONS'), ('GERANT', 'CARTES_GERER'),
    ('GERANT', 'CARTES_EMPLOYES'), ('GERANT', 'RECHARGER'), ('GERANT', 'VENTES_BOUTIQUE'), ('GERANT', 'CLOTURER_POSTE'),
    ('GERANT', 'STOCKS'), ('GERANT', 'EMPLOYES'), ('GERANT', 'JOURNAL'), ('GERANT', 'LIVRAISONS'),

    ('CHEF_POSTE', 'VENTES_POMPE'), ('CHEF_POSTE', 'RAPPORTS'), ('CHEF_POSTE', 'RECHARGER'),
    ('CHEF_POSTE', 'VENTES_BOUTIQUE'), ('CHEF_POSTE', 'CLOTURER_POSTE'),

    ('POMPISTE', 'VENTES_POMPE'), ('POMPISTE', 'VENTES_BOUTIQUE'),

    ('COMPTABLE', 'TRANSACTIONS'), ('COMPTABLE', 'VALIDER_MONTANTS'), ('COMPTABLE', 'CARTES_ACTIVER'),
    ('COMPTABLE', 'JOURNAL'),

    ('DAF', 'TRANSACTIONS'), ('DAF', 'VALIDER_MONTANTS'), ('DAF', 'VALIDER_CARTES_ENTREPRISE'),
    ('DAF', 'CARTES_ACTIVER'), ('DAF', 'PDV_VALIDER'), ('DAF', 'JOURNAL'),

    ('AGENT_PDV', 'PDV_VENDRE'), ('AGENT_PDV', 'RECHARGER'),

    ('LIVREUR', 'LIVRER');

INSERT INTO card_type (code, label, price, sold_to_public, sort) VALUES
    ('CLASSIQUE', 'Classique', 1000, true,  1),
    ('VIP',       'VIP',       5000, true,  2),
    ('FLOTTE',    'Flotte',    0,    false, 3),
    ('EMPLOYE',   'Employé',   0,    false, 4);

INSERT INTO product (code, name, category, unit, gas_size_kg, deposit_amount, card_type_code) VALUES
    ('SUPER',          'Super',                    'CARBURANT',  'L',      NULL, NULL,  NULL),
    ('GASOIL',         'Gasoil',                   'CARBURANT',  'L',      NULL, NULL,  NULL),
    ('PETROLE',        'Pétrole',                  'CARBURANT',  'L',      NULL, NULL,  NULL),
    ('CARTE_CLASSIQUE','Carte classique',          'CARTE',      'unité',  NULL, NULL,  'CLASSIQUE'),
    ('CARTE_VIP',      'Carte VIP',                'CARTE',      'unité',  NULL, NULL,  'VIP'),
    ('GAZ_6',          'Gaz butane 6 kg',          'GAZ',        'bouteille', 6,    10000, NULL),
    ('GAZ_12_5',       'Gaz butane 12,5 kg',       'GAZ',        'bouteille', 12.5, 15000, NULL),
    ('HUILE_5W30_5L',  'Huile moteur 5W30 · 5 L',  'LUBRIFIANT', 'unité',  NULL, NULL,  NULL),
    ('HUILE_2T_1L',    'Huile 2T · 1 L',           'LUBRIFIANT', 'unité',  NULL, NULL,  NULL),
    ('BRULEUR_GAZ',    'Brûleur gaz',              'ACCESSOIRE', 'unité',  NULL, NULL,  NULL);

-- Prix par profil d'acheteur : grand public, employé station, employé groupe, partenaire
INSERT INTO product_price (product_id, profile, price)
SELECT p.id, v.profile, v.price
FROM product p
JOIN (VALUES
    ('SUPER', 'GRAND_PUBLIC', 680), ('SUPER', 'EMPLOYE_STATION', 680), ('SUPER', 'EMPLOYE_GROUPE', 680), ('SUPER', 'PARTENAIRE', 680),
    ('GASOIL', 'GRAND_PUBLIC', 675), ('GASOIL', 'EMPLOYE_STATION', 675), ('GASOIL', 'EMPLOYE_GROUPE', 675), ('GASOIL', 'PARTENAIRE', 675),
    ('PETROLE', 'GRAND_PUBLIC', 600), ('PETROLE', 'EMPLOYE_STATION', 600), ('PETROLE', 'EMPLOYE_GROUPE', 600), ('PETROLE', 'PARTENAIRE', 600),
    ('CARTE_CLASSIQUE', 'GRAND_PUBLIC', 1000), ('CARTE_CLASSIQUE', 'EMPLOYE_STATION', 500), ('CARTE_CLASSIQUE', 'EMPLOYE_GROUPE', 500), ('CARTE_CLASSIQUE', 'PARTENAIRE', 800),
    ('CARTE_VIP', 'GRAND_PUBLIC', 5000), ('CARTE_VIP', 'EMPLOYE_STATION', 3000), ('CARTE_VIP', 'EMPLOYE_GROUPE', 3000), ('CARTE_VIP', 'PARTENAIRE', 4000),
    ('GAZ_6', 'GRAND_PUBLIC', 4500), ('GAZ_6', 'EMPLOYE_STATION', 4100), ('GAZ_6', 'EMPLOYE_GROUPE', 4200), ('GAZ_6', 'PARTENAIRE', 4300),
    ('GAZ_12_5', 'GRAND_PUBLIC', 6500), ('GAZ_12_5', 'EMPLOYE_STATION', 6000), ('GAZ_12_5', 'EMPLOYE_GROUPE', 6100), ('GAZ_12_5', 'PARTENAIRE', 6250),
    ('HUILE_5W30_5L', 'GRAND_PUBLIC', 22500), ('HUILE_5W30_5L', 'EMPLOYE_STATION', 19500), ('HUILE_5W30_5L', 'EMPLOYE_GROUPE', 20000), ('HUILE_5W30_5L', 'PARTENAIRE', 21000),
    ('HUILE_2T_1L', 'GRAND_PUBLIC', 3500), ('HUILE_2T_1L', 'EMPLOYE_STATION', 3000), ('HUILE_2T_1L', 'EMPLOYE_GROUPE', 3100), ('HUILE_2T_1L', 'PARTENAIRE', 3200),
    ('BRULEUR_GAZ', 'GRAND_PUBLIC', 3000), ('BRULEUR_GAZ', 'EMPLOYE_STATION', 2500), ('BRULEUR_GAZ', 'EMPLOYE_GROUPE', 2600), ('BRULEUR_GAZ', 'PARTENAIRE', 2800)
) AS v (code, profile, price) ON v.code = p.code;

INSERT INTO setting (key, value) VALUES
    ('validation.threshold',          '500000'),   -- palier : au-dessus, la recharge doit être validée
    ('validation.company_needs_daf',  'true'),     -- cartes entreprises : Comptable puis DAF
    ('card.number_prefix',            '6011'),
    ('station.online_seconds',        '120'),      -- dernier signal du PC station < 2 min : en ligne
    ('station.unstable_seconds',      '900'),      -- < 15 min : instable, au-delà : hors ligne
    ('pdv.offline_seconds',           '300'),
    ('alerts.safety_days',            '2'),        -- jours de marge ajoutés au délai de livraison
    ('alerts.critical_ratio',         '0.6'),      -- sous 60 % du seuil : critique
    ('alerts.logistics_emails',       'logistique@zener.tg'),
    ('report.daily_time',             '23:59'),
    ('email.summary_time',            '23:59'),
    ('email.summary_recipients',      'direction@zener.tg,daf@zener.tg'),
    ('email.summary_to_managers',     'true'),
    ('licence.reminder_days',         '30,7,1'),
    ('mobility.min_quantity',         '1000'),
    ('mobility.tracking_stale_seconds', '180');

-- Jours fériés fixes et chrétiens 2026 (les fêtes musulmanes sont à ajouter chaque année)
INSERT INTO public_holiday (day, label) VALUES
    ('2026-01-01', 'Jour de l''an'),
    ('2026-01-13', 'Fête de la libération nationale'),
    ('2026-04-06', 'Lundi de Pâques'),
    ('2026-04-27', 'Fête de l''indépendance'),
    ('2026-05-01', 'Fête du travail'),
    ('2026-05-14', 'Ascension'),
    ('2026-05-25', 'Lundi de Pentecôte'),
    ('2026-06-21', 'Journée des martyrs'),
    ('2026-08-15', 'Assomption'),
    ('2026-11-01', 'Toussaint'),
    ('2026-12-25', 'Noël');
