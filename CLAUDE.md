# ZENER Station Manager — guide pour Claude

Application de gestion du réseau de stations-service **ZENER** (Togo, aussi Bénin et Burkina), développée par des stagiaires de Kapi Consult. Elle succède à la plateforme LCT/LTStation (`../../LTStation`, même PC) mais c'est un **nouveau projet**, pas un port.

- `frontend/` : application desktop Electron + React 18 + Vite (JSX, pas de TypeScript), faite par un coéquipier. 14 écrans reproduisant `maquettes/`, **branchés sur l'API** depuis le 9 novembre 2026 (plus aucune donnée codée en dur, mise à jour en direct par le flux SSE). Couche d'accès : `src/api/` (`client.js`, `session.jsx`, `live.jsx`, `hooks.js`), formats dans `src/lib/`.
- `backend/` : API Spring Boot 3.4 / Java 17 / PostgreSQL. **Tous les modules sont écrits, lancés et vérifiés** sur les données de démonstration (voir « État et suites »). Non commitée.
- `App de Gestion de station Zener.docx` : notes de cahier des charges du coéquipier.
- Interface et messages **en français** ; code (identifiants) en anglais, commentaires en français.

## Règles de travail

- Ne jamais pousser sur GitHub (`origin` = github.com/XB19/Gestion-de-Station-Zener) sans demande explicite. Ne commiter que sur demande, sur une branche (pas `main`).
- Le frontend appartient au coéquipier (design, maquettes) : ne pas le modifier sans demande ; lister les manques à la place. Le branchement sur l'API a été fait à la demande de l'utilisateur.
- **Données de démonstration et simulateur actifs par défaut** (`DEMO_DATA` vaut `true`) pour que toute personne qui clone le dépôt ait un réseau vivant. À retirer quand les vrais tests commenceront : paquet `backend/.../demo`, défaut de `app.demo-data` dans `application.yml`, service `api` / `DEMO_DATA` de `docker-compose.yml`, `.env.example`, badges de comptes de démonstration de `Login.jsx` et section « Données de démonstration » des README.
- Applications mobiles (NFC, client final) : hors périmètre pour l'instant, sauf le **suivi GPS** (voir Zen Mobility) qui ne demande qu'une API.

## Décisions fonctionnelles (réunions avec le responsable)

- **Rôles** : Admin, Gérant, Chef de poste, Pompiste, Comptable (historique des transactions en lecture seule + validation), DAF, Agent PDV, Livreur. Gérant / chef de poste / pompiste ne voient que **leur** station.
- **Stations** : type *gérance libre* ou *succursale* ; attribuées à un client par l'Admin ; géolocalisées ; pays. Station *mobile* possible (position GPS en direct).
- **Mode autonome / conventionnel** : bascule automatique selon des plages horaires par station (jours fériés compris) + bascule manuelle à tout moment (prioritaire jusqu'au prochain changement de plage). En autonome, paiement par carte uniquement, avec PIN, selon les **permissions de la carte**.
- **Hub** : nombre fixe de ports, un appareil par port (pistolets, lecteur de cartes). Le hub envoie un flux **binaire** sur un port série, lu par un programme sur le PC de la station ; ce programme parle à l'API (le décodage binaire n'est pas dans ce dépôt).
- **Cartes** : classique **1 000 F**, VIP **5 000 F** (prix modifiables), flotte (entreprise), employé. Cartes à puce et M1 prises en charge. Une carte créée est **« à activer »** : une **autre personne** que celle qui l'a émise doit l'activer (anti-fraude, contrainte en base `card_activation_four_eyes`).
- **Carte employé** : chaque employé a une carte personnelle **créée par le gérant** ; son historique sert aux autres fonctions (employé du mois, clôture de poste).
- **Clôture du pompiste** : en fin de poste il voit son historique, ce qu'il doit remettre en espèces, ce qu'il a remis et **ce qu'il lui reste à remettre** en cas d'écart.
- **Recharges** : unitaire, à distance (nom + numéro de carte suffisent s'ils concordent), en masse (fichier). Au-dessus du **palier** (500 000 F par carte, paramétrable) : validation Comptable, puis DAF pour les cartes entreprises.
- **Prix par profil** : grand public, employé station, employé groupe, partenaire. Le profil vient du titulaire de la carte ; sans carte = grand public.
- **Stocks** : lubrifiants, bouteilles de gaz (pleines / vides / chez les clients / endommagées, échange vide ⇄ pleine, consigne), accessoires, carburant (cuves). Alerte + email quand un stock passe sous son seuil ; **seuil ajusté automatiquement** chaque nuit selon la tendance de consommation.
- **Points de vente (PDV)** : un permanent au **siège**, des temporaires (événements, campagnes) ouverts sur demande validée par l'**Admin ou le DAF**, durée réglable. On n'y vend **que des cartes neuves**, sur **pièce d'identité**, en **espèces ou mobile money**. Fonctionnent **hors connexion** (synchronisation au retour du réseau). Clôture : comptage, retour des cartes invendues, rapprochement.
- **Rapports** journaliers et de poste **générés automatiquement** ; le gérant les **commente** au lieu de les rédiger. Résumé quotidien par email.
- **Supervision** : flux en direct « qui fait quoi », stations en ligne / hors ligne, recette en direct, état des stations.
- **Employé du mois** selon les produits vendus. Photo de l'employé (affichée sur sa carte).
- **Licence** annuelle par client (renouvellement / résiliation, rappels J-30, J-7, J-1).
- **Zen Mobility** : commande de carburant de **1 000 L minimum** livrée par un agent, suivie en temps réel (position GPS envoyée par le téléphone du livreur).
- **Distributeur de gaz** : le client se sert une bouteille en introduisant sa carte (libre-service, carte obligatoire).

## Backend : architecture et conventions

Paquet `tg.zener.station`, un sous-paquet par module. Pas de JPA : SQL explicite via `common/Db` (NamedParameterJdbcTemplate) qui renvoie des `Map` aux clés **camelCase** (colonne `short_name` → clé `shortName`), sérialisées telles quelles en JSON.

- `Db.p("k", v, …)` pour les paramètres ; `db.list / one / require(…, "message 404") / value / count / sum / update / insert` (renvoie l'id) ; `db.array("text"|"bigint"|"smallint", values)` pour les tableaux PostgreSQL. Paramètre facultatif : `(CAST(:x AS bigint) IS NULL OR col = :x)`.
- Les `UPDATE … RETURNING` passent par `db.one/list/require` (requête).
- Erreurs : `ApiException` (400 par défaut, `notFound`, `forbidden`, `conflict`, `refused` = 422 pour un paiement par carte refusé). Réponse `{ code, message }` en français (`GlobalExceptionHandler`).
- Pagination : `common/Page` → `{ items, total, page, size }` (page à partir de 0, taille 25 par défaut, 200 max).
- Montants en **FCFA entiers** (`bigint`), volumes en litres (`numeric`). Le frontend formate ; l'API renvoie des nombres bruts, plus quelques libellés prêts à afficher (`statusLabel`, `modeLabel`, `typeLabel`, `numberFormatted`…) qui correspondent au `STATUS` de `frontend/src/components/ui.jsx`.
- Sécurité : JWT HS256 maison (`security/JwtService`), `Authorization: Bearer …`. Droits = autorités Spring (`@PreAuthorize("hasAuthority('CARTES_GERER')")`), liste dans `V2__reference_data.sql` (table `app_right`, matrice `role_right`). Utilisateur courant : `Auth.user()` ; périmètre station : `Auth.scopedStation(id)` / `Auth.checkStation(id)`.
- Tout changement notable est journalisé : `journal.log(type, Journal.CATEGORIE, titre, détail, montant, stationId, pdvId)` (ou `logAs(actorId, …)` hors requête). Le journal pousse aussi l'événement dans le flux temps réel (`common/LiveFeed`, SSE sur `/api/live?access_token=…`).
- Emails : `Mailer.queue(destinataires, sujet, corps, type)` → table `email_outbox` ; envoyés par SMTP seulement si `MAIL_HOST` est défini, sinon consultables via `/api/settings/emails`.
- Paramètres modifiables : table `setting` via `common/Settings` (palier de validation, délais en ligne / hors ligne, emails, etc.).
- Validations : `ValidationService.create(...)` crée la demande et ses étapes ; à la décision finale il publie l'événement `ValidationService.Decided`, écouté par `RechargeService` et `PdvService` (`@EventListener`).
- Paiement par carte : toujours via `CardService.charge(...)` (statut, expiration, PIN, produits, jours, horaires, stations, mode autonome, plafonds, solde) ; les refus sont enregistrés dans une transaction séparée (`CardRefusals`).
- Ventes : toujours via `SaleService.record(...)` (prix par profil, débit carte, stock, index du pistolet, journal). Idempotentes grâce à `uuid` (renvoi après coupure = pas de doublon).
- Migrations Flyway dans `backend/src/main/resources/db/migration`. Rien n'est encore déployé : on peut encore modifier `V1`/`V2` ; une fois partagé, ajouter `V3__…`.
- Références lisibles : `common/Refs` (VAL-2026-1001, REC-…, ACH-…, LIV-…, PDV-101).
- **Piège des blocs de texte Java** : Java retire l'indentation commune et les espaces de fin de ligne, donc `SELECT + where` collerait `…card_idWHERE` et `"… AND """ + cond` donnerait `ANDcond`. Convention : tout bloc SQL commence par l'échappement `\s` (première ligne `\sSELECT …`) et un fragment ajouté en chaîne simple commence par une espace (`" (CAST(:s AS bigint) IS NULL OR …)"`). Conséquence : les requêtes commencent par une espace (dans les tests, `contains` plutôt que `startsWith`).
- Paramètre nul dans `CASE WHEN :x IS NULL`, `upper(:x)`… : toujours le typer (`CAST(:x AS bigint)`), sinon PostgreSQL refuse le type inconnu.
- La JVM prend le fuseau `APP_TIMEZONE` au démarrage (`StationManagerApplication.main`) : `current_date` et les journées SQL suivent Africa/Lome.

### Modules (lancés et vérifiés le 9 novembre 2026)

| Module | Fichiers | Points clés |
|---|---|---|
| Auth | `auth/AuthController` | `POST /api/auth/login {email,password,remember}` → `{token,user}`, `GET /api/auth/me`, `POST /api/auth/password` |
| Paramètres | `settings/SettingsController` | `/api/settings`, jours fériés, file des emails |
| Journal | `journal/*` | `/api/journal` (filtres, pages), `/api/journal/breakdown`, `/api/feed`, `/api/live` (SSE) |
| Clients & licences | `clients/*` | `/api/clients`, `/summary`, `/{id}`, `/{id}/licence/renew|terminate|reactivate` ; rappels à 8 h |
| Stations | `stations/*` | `/api/stations` (filtres), `/summary`, `/map`, `/{id}` (pistolets, cuves, plages, équipe, activité horaire), `/{id}/mode`, `/auto-switch`, `/windows`, `/pumps`, `/tanks`, `/shift-configs`, `/api-key`, `/client` |
| Cartes | `cards/*` | `/api/cards` (onglets ALL, FLOTTE, STOCK, A_ACTIVER), `/summary`, `/lookup/{numéro}`, `/{id}`, `/{id}/operations`, `/batches`, `/{id}/permissions`, `/activate`, `/block`, `/unblock`, `/pin`, `/types` ; `/api/holders`, `/api/card-groups` |
| Recharges | `recharges/*` | `/api/recharges` (unitaire + historique), `/remote-check`, `/remote`, `/template`, `/batches` (fichier ou liste JSON), `/batches/{id}`, `/lines/{lineId}`, `/credit-ready`, `/submit` |
| Validations | `validations/*` | `/api/validations?status=EN_ATTENTE|HISTORIQUE`, `/summary`, `/{id}`, `/approve`, `/refuse` |
| Ventes & prix | `sales/*` | `/api/sales/fuel`, `/shop`, `/gas-exchange`, `/api/sales` (historique), `/summary`, `/quote` ; `/api/products`, `/api/prices` |
| Stocks | `stock/*` | `/api/stock` (niveaux + état), `/alerts`, `/gas-bottles`, `/entries`, `/inventory`, `/purchases`, `/levels/{id}/threshold`, `/recompute-thresholds` ; seuils recalculés à 1 h 30 |
| Points de vente | `pdv/*` | `/api/pdv`, `/summary`, `/{id}` (rapprochement), `/requests`, `/{id}/agents`, `/cards/allocate`, `/cards`, `/sales`, `/sync`, `/heartbeat`, `/close` ; passage « à clôturer » à minuit |
| Employés & rôles | `employees/*`, `config/WebConfig` | `/api/employees` (filtres q, rôle, station, actif), `/summary`, `/{id}`, création, modification, `DELETE` (désactivation, carte bloquée), `/reactivate`, `/password`, `/photo` (multipart, `PhotoStore`, servie sur `/files/photos/…`), `/card` (carte employé à activer), `/of-the-month?month=AAAA-MM` (défaut : mois précédent) ; `/api/roles`, `PUT /api/roles/{code}/rights`. Un gérant : sa station seulement, ni Admin, ni DAF, ni Comptable |
| Postes | `shifts/*` (table `shift_remittance`) | `/api/shifts/start`, `/pause`, `/resume`, `/me` (poste, ventes, espèces attendues, remises, **reste à remettre**, `outstanding` des postes clos, historique), `GET /api/shifts`, `/{id}`, `/{id}/remit`, `/{id}/close` (la remise est reçue par une autre personne) |
| Rapports | `reports/*` | job chaque minute : rapports du jour dès `report.daily_time` + rattrapage de la veille, résumé email à `email.summary_time` (`email.summary_last_day`) ; `/api/reports/daily?day=`, `/daily/history`, `/daily/{id}`, `PUT …/comment`, `POST …/validate`, `/daily/generate`, `/email-summary/preview`, `/email-summary/send`. Rapports de poste : une ligne par poste type (Matin, Soir, Nuit) dans le jour calendaire |
| Tableau de bord | `dashboard/DashboardController` | `/api/dashboard?period=JOUR|SEMAINE|MOIS` : recette vs période précédente au même moment, volume et parts, stations, validations, répartition, courbes cumulées, flux, états, PDV ouverts, alertes |
| Zen Mobility & GPS | `mobility/*` | `/api/mobility/orders` (création ≥ `mobility.min_quantity`, `/validate`, `/assign`, `/depart`, `/deliver` → vente canal `LIVRAISON` + sortie de cuve + débit de la carte, `/cancel`), `/summary`, `/my-deliveries` ; `/api/tracking/ping` (droit `LIVRER`), `/api/tracking/live` ; événements `location` et `delivery` |
| PC station | `hub/HubController` | `/api/hub/heartbeat`, `/authorize`, `/sales` (idempotent ; pompiste retrouvé par les pistolets de son poste), `/gas` (distributeur, carte obligatoire), `/location` (station mobile) ; événements `pump` |
| Tâches des stations | `stations/StationJobs` | chaque minute : plages horaires (bascule manuelle prioritaire jusqu'à `mode_override_until`) et passages en ligne / instable / hors ligne (colonne `station.last_status`, journal + événement `station`) |
| Démonstration | `demo/DemoSeeder`, `demo/DemoSimulator` | voir « Données de démonstration » |

## État et suites

Les 11 points de l'ancienne liste « Reste à faire » sont faits (9 novembre 2026). Vérifié :
- `./mvnw test` : 33 tests unitaires (`ModeScheduleTest`, `ThresholdServiceTest`, `CardServiceTest`, `BatchImportTest`), tous verts.
- Serveur lancé sur une base vide avec `DEMO_DATA=true` (création en ~55 s, ~16 000 ventes), puis 136 vérifications d'API par un script Python (non versionné) : lecture de tous les écrans ; recharge au-dessus du palier → Comptable → DAF → carte créditée ; activation refusée à l'émetteur ; vente PDV hors ligne synchronisée deux fois sans doublon ; refus carte (hors horaires, produit, bloquée, solde / plafond) ; postes et reste à remettre ; employés, photo, rôles ; rapports ; PC station (autonome : carte + PIN, idempotence, distributeur de gaz) ; Zen Mobility jusqu'au débit de la carte.
- Tâches planifiées observées en direct : bascule automatique par plage horaire, station hors ligne puis de nouveau en ligne.
- Frontend branché : `npm run build` sans erreur ; les 14 écrans parcourus dans le navigateur avec les données réelles, sans erreur console ; parcours testé à la main : connexion DAF → approbation d'une recharge → carte créditée, flux en direct mis à jour.

À faire ensuite :
1. Écrans manquants listés ci-dessous (Zen Mobility surtout).
2. Tests d'intégration automatisés (base PostgreSQL dédiée ou Testcontainers) reprenant les parcours vérifiés à la main.
3. Envoi réel des emails (serveur SMTP à obtenir) ; jours fériés musulmans à saisir chaque année.
4. Avant le premier déploiement : figer `V1` / `V2` (ensuite, uniquement des `V3__…`), `JWT_SECRET` de production, sauvegardes PostgreSQL.

## Manques du frontend (à signaler au coéquipier, ne pas corriger sans demande)

Faits lors du branchement : connexion et jeton (« Rester connecté » = localStorage), menus et boutons selon les droits, file des cartes « à activer » (onglet de l'écran Cartes), case « Accessoires » des permissions, clôture des postes par le chef de poste / gérant (Rapports › Rapports de poste), paliers de validation et planification du résumé email (dans Validations et Rapports).

Restent à faire :
- Écran **Zen Mobility** (commandes ≥ 1 000 L, affectation, suivi) et **carte en direct** des livreurs / stations mobiles (`/api/mobility/*`, `/api/tracking/live`, événements `location`).
- Écran du **pompiste** pour son propre poste (`/api/shifts/me` : ventes, reste à remettre, pause) et saisie des ventes à la pompe en mode conventionnel (`/api/sales/fuel`).
- Écran **Paramètres** complet (lien de la barre latérale inactif) : seuils d'alerte, délais en ligne / hors ligne, jours fériés, file des emails.
- Import de stations (bouton retiré), modification des pistolets et cuves (seul l'état d'un pistolet se change depuis la fiche station).

## Données de démonstration

`DEMO_DATA=true` sur une base **vide** (sinon rien n'est fait ; le paramètre `demo.seeded` marque une création complète, un avertissement est journalisé sinon). Pour régénérer : recréer la base.
- Mot de passe de tous les comptes : `zener-station`. Admin `ama.kpodar@zener.tg`, gérant d'Agoè `kossi.amegah@zener.tg`, chef de poste `yao.agbeko@zener.tg`, pompiste `komlan.tetteh@zener.tg`, comptables `abla.gbeasor@zener.tg` / `kafui.ekoue@zener.tg`, DAF `mawuli.kpodar@zener.tg`, agents PDV `essi.lawson@zener.tg`, `delali.agbo@zener.tg`…, livreurs `edem.akakpo@zener.tg`, `koffi.dossou@zener.tg`.
- PIN de toutes les cartes : `1234`. Clé du PC station : `X-Station-Code: ST-001` + `X-Station-Key: demo-st-001` (jusqu'à ST-011).
- `DemoSimulator` : toutes les 6 s (selon l'affluence de l'heure) une opération par les vrais services — vente espèces / mobile money, paiement par carte (y compris autonome avec PIN), boutique et gaz, distributeur de gaz, carte vendue en PDV, recharge, refus de carte, activation par la comptabilité, recharge au-dessus du palier (6 validations en attente au plus) — plus signaux de vie (`demo.online_codes`, Kpalimé instable `demo.unstable_codes`, PDV `demo.pdv_online_codes`) et états des pistolets. Chaque minute : relève des postes avec remise des espèces, livraison des cuves sous 10 %, réassort boutique, recharge des cartes flottes, cartes vierges pour les PDV, tournée Zen Mobility suivante (`demo.route`). Atakpamé et Dapaong restent hors ligne.
- Repères : Komlan Tetteh est l'employé du mois d'octobre ; Bè Kpota a la cuve de gasoil sous le seuil ; la carte BTP Kara est bloquée ; Adidogomé, Notsè, Sokodé et Kara sont en autonome manuel (`auto_switch = false`).

## Lancer le backend en local

Prérequis : Java 17, Maven Wrapper (`backend/mvnw`, les dépendances sont déjà en cache dans `~/.m2`). Docker Desktop n'est pas toujours démarré sur ce PC.

Base de test : une instance PostgreSQL 17 persistante est dans `~/zener-pg` (port 5434, utilisateur / mot de passe `zener`) ; si le port ne répond pas : `pg_ctl -D ~/zener-pg -o "-p 5434" -l ~/zener-pg/server.log start` (ou `docker compose up -d db`). Pour en recréer une :

```bash
PG="/c/Program Files/PostgreSQL/17/bin"; D="$HOME/zener-pg"
printf 'zener\n' > /tmp/pw && "$PG/initdb.exe" -D "$D" -U zener --pwfile=/tmp/pw -E UTF8 --locale=C -A scram-sha-256
"$PG/pg_ctl.exe" -D "$D" -o "-p 5434" -l "$D/server.log" start
PGPASSWORD=zener "$PG/psql.exe" -h localhost -p 5434 -U zener -d postgres -c "CREATE DATABASE zener_station"
```

(Le PostgreSQL du port 5432 a un mot de passe inconnu : ne pas l'utiliser.)

Lancement — **attention** : la variable d'environnement globale `SPRING_DATASOURCE_URL` (d'un autre projet) écrase la configuration ; la retirer :

```bash
cd backend && env -u SPRING_DATASOURCE_URL DEMO_DATA=true ./mvnw spring-boot:run
```

API sur `http://localhost:8090` (`SERVER_PORT`). Variables : `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (32 caractères min., obligatoire en production), `CORS_ORIGINS` (par défaut `http://localhost:5173,null` — `null` = Electron en `file://`), `UPLOAD_DIR`, `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` / `MAIL_PASSWORD` / `MAIL_FROM`, `APP_TIMEZONE` (Africa/Lome), `DEMO_DATA`.

Les commandes Maven et `git` qui accèdent au réseau doivent être lancées hors bac à sable (accès réseau bloqué sinon). Toutes les dépendances (tests compris) sont maintenant en cache : `./mvnw -o compile` et `./mvnw -o test` marchent hors ligne.

Frontend : `cd frontend && npm install && npm run dev` (Vite sur 5173 ; sur ce PC, 5173/5174 peuvent être pris par un autre projet).
