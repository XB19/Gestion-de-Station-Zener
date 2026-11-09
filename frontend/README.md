# ZENER Station Manager — frontend desktop

Application desktop (Electron + React + Vite) de gestion du réseau de stations ZENER,
reproduite à partir des maquettes du dossier `maquettes/`.

## Démarrer

```bash
npm install
npm run electron:dev   # app desktop avec rechargement à chaud
npm run dev            # même interface dans le navigateur (http://localhost:5173)
npm run electron       # build de production puis ouverture dans Electron
npm run dist           # installeur Windows (.exe) dans dist/ via electron-builder
```

L'application parle à l'API backend (`http://localhost:8090` par défaut, réglable avec `VITE_API_URL`,
voir `.env.example`). Démarrez d'abord le backend (voir le README à la racine), puis connectez-vous :
les badges « Accès selon votre rôle » de l'écran de connexion remplissent les comptes de démonstration
(mot de passe `zener-station`).

## Structure

```
electron/
  main.cjs            fenêtre Electron (charge Vite en dev, dist/ en prod)
  launch.cjs          lanceur (retire ELECTRON_RUN_AS_NODE défini par VS Code)
src/
  styles/global.css   design system : couleurs, cartes, badges, tables, boutons…
  components/
    Layout.jsx        barre latérale + barre du haut
    ui.jsx            composants réutilisables (Kpi, Badge, Button, Select, BankCard…)
    charts.jsx        graphiques SVG (courbe, barres, anneaux) sans dépendance
    ZenerLogo.jsx     logo ZENER en SVG
    feedback.jsx      notifications, fenêtres modales, champs de formulaire
  api/
    client.js         appels à l'API (jeton JWT, erreurs en français, téléchargements)
    session.jsx       connexion, utilisateur courant et droits (can(...))
    live.jsx          flux temps réel SSE (/api/live) partagé par les écrans
    hooks.js          useApi (chargement), useLiveReload (rafraîchissement en direct)
  lib/                formats (FCFA, dates), icônes du journal
  data/togo.js        frontière du Togo pour la carte
  pages/              un fichier par écran de maquette (14 écrans)
```

| Maquette | Route | Fichier |
|---|---|---|
| 01 Connexion | `#/login` | `Login.jsx` |
| 02 Tableau de bord | `#/` | `Dashboard.jsx` |
| 03 Stations | `#/stations` | `Stations.jsx` |
| 04 Détail station | `#/stations/:id` | `StationDetail.jsx` |
| 05 Cartes | `#/cartes` | `Cartes.jsx` |
| 06 Fiche carte | `#/cartes/:id` | `CarteDetail.jsx` |
| 07 Rechargements | `#/rechargements` | `Rechargements.jsx` |
| 08 Validations | `#/validations` | `Validations.jsx` |
| 09 Ventes & stocks | `#/ventes-stocks` | `VentesStocks.jsx` |
| 10 Points de vente | `#/points-de-vente` | `PointsDeVente.jsx` |
| 11 Employés & rôles | `#/employes` | `Employes.jsx` |
| 12 Rapports | `#/rapports` | `Rapports.jsx` |
| 13 Journal d'opérations | `#/journal` | `Journal.jsx` |
| 14 Clients & licences | `#/clients` | `Clients.jsx` |

Toutes les données viennent de l'API ; les écrans se mettent à jour en direct grâce au flux temps réel
(opérations, ventes, pistolets, stations). Les boutons n'apparaissent que si l'utilisateur a le droit
correspondant (ex. « Approuver » pour la Comptable et le DAF, « Nouvelle station » pour l'Admin).
