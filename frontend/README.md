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

L'application s'ouvre sur l'écran de connexion ; « Se connecter » mène au tableau de bord.

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
  data/stations.js    données de démonstration partagées
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

Les données affichées sont des données de démonstration codées dans chaque page
(ou `src/data/`) : c'est l'endroit à brancher sur l'API backend.
