# Gestion de Station ZENER

Application desktop de gestion du réseau de stations-service ZENER.

| Dossier / fichier | Contenu |
|---|---|
| [`frontend/`](frontend/) | Application desktop (Electron + React + Vite) — voir [frontend/README.md](frontend/README.md) |
| [`backend/`](backend/) | API REST (Spring Boot 3.4, Java 17, PostgreSQL 17) — voir la section Backend ci-dessous |
| [`maquettes/`](maquettes/) | Les 14 maquettes d'écrans |
| `App de Gestion de station Zener.docx` | Cahier des charges fonctionnel |

## Démarrage rapide

```bash
cd frontend
npm install
npm run electron:dev
```

## Backend

API REST Spring Boot 3.4 / Java 17 / PostgreSQL 17, en français (messages d'erreur `{ code, message }`).

### Démarrer

```bash
docker compose up -d
```

```bash
cd backend
DEMO_DATA=true ./mvnw spring-boot:run
```

L'API écoute sur `http://localhost:8090` (`GET /api/health`). Le schéma est créé par Flyway au démarrage.
Les variables disponibles sont décrites dans [`.env.example`](.env.example) (base, `JWT_SECRET` obligatoire en production,
origines CORS, dossier des photos, SMTP, fuseau).

Tests unitaires :

```bash
cd backend && ./mvnw test
```

### Données de démonstration

Avec `DEMO_DATA=true` et une base vide, l'application crée en une minute environ un réseau reprenant les maquettes :
7 clients et leurs licences, les 11 stations du frontend (coordonnées réelles, pistolets P1–P8 sur COM1–COM8, 3 cuves,
postes Matin / Soir / Nuit), des employés de chaque rôle, les cartes de l'écran Cartes, 4 points de vente, des validations
en attente, 14 jours de ventes et les rapports journaliers. Un simulateur envoie ensuite les signaux de vie des stations
en ligne, quelques ventes et la position du livreur en route, pour que le tableau de bord bouge.

| Compte | Rôle |
|---|---|
| `ama.kpodar@zener.tg` | Admin |
| `kossi.amegah@zener.tg` | Gérant (Agoè Assiyéyé) |
| `yao.agbeko@zener.tg` | Chef de poste |
| `komlan.tetteh@zener.tg` | Pompiste |
| `abla.gbeasor@zener.tg` | Comptable |
| `mawuli.kpodar@zener.tg` | DAF |
| `essi.lawson@zener.tg`, `delali.agbo@zener.tg` | Agents PDV |
| `edem.akakpo@zener.tg` | Livreur |

Mot de passe de tous les comptes : `zener-station`. PIN de toutes les cartes : `1234`.
Clé du PC station : en-têtes `X-Station-Code: ST-001` et `X-Station-Key: demo-st-001` (idem pour ST-002…ST-011).

### Modules

| Préfixe | Contenu |
|---|---|
| `/api/auth` | Connexion (`POST /login` → jeton JWT), profil, mot de passe |
| `/api/dashboard` | Tableau de bord (`?period=` JOUR, SEMAINE ou MOIS) |
| `/api/stations` | Stations, carte, fiche, mode autonome / conventionnel, plages horaires, pistolets, cuves |
| `/api/cards`, `/api/holders`, `/api/card-groups` | Cartes, titulaires, groupes, permissions, activation (quatre yeux) |
| `/api/recharges` | Recharges unitaires, à distance, en masse (fichier) |
| `/api/validations` | Validations Comptable / DAF / Admin |
| `/api/sales`, `/api/products`, `/api/prices` | Ventes, historique des transactions, prix par profil |
| `/api/stock` | Stocks, alertes, bouteilles de gaz, achats, inventaires |
| `/api/pdv` | Points de vente (permanent et temporaires), vente de cartes hors ligne, clôture |
| `/api/employees`, `/api/roles` | Employés, photo, carte employé, employé du mois, rôles et droits |
| `/api/shifts` | Postes : ouverture, pause, remises d'espèces, clôture, reste à remettre |
| `/api/reports` | Rapports journaliers (générés, commentés, validés), résumé email |
| `/api/mobility`, `/api/tracking` | Zen Mobility (commandes ≥ 1 000 L) et suivi GPS en direct |
| `/api/hub` | PC station : signal de vie, pré-autorisation de carte, ventes, distributeur de gaz, station mobile |
| `/api/journal`, `/api/feed`, `/api/live` | Journal d'opérations, flux récent, temps réel (SSE, `?access_token=`) |
| `/api/clients`, `/api/settings` | Clients et licences, paramètres, jours fériés, file des emails |

Les requêtes authentifiées portent l'en-tête `Authorization: Bearer <jeton>`. Les photos des employés sont servies sur `/files/…`.

