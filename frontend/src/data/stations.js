// Données de démonstration — à remplacer par l'API
export const STATIONS = [
  { id: 'agoe-assiyeye', name: 'Zener Agoè Assiyéyé', short: 'Agoè Assiyéyé', type: 'Succursale', zone: 'Lomé', client: 'Groupe Zener', status: 'En ligne', mode: 'Conventionnel', recette: '2 845 600', cuves: [70, 55, 40], etat: 'Normal' },
  { id: 'be-kpota', name: 'Zener Bè Kpota', short: 'Bè Kpota', type: 'Gérance libre', zone: 'Lomé', client: 'Ets Amegah & Fils', status: 'En ligne', mode: 'Conventionnel', recette: '2 310 200', cuves: [75, 15, 65], etat: 'Cuve Gasoil basse' },
  { id: 'adidogome', name: 'Zener Adidogomé', short: 'Adidogomé', type: 'Succursale', zone: 'Lomé', client: 'Groupe Zener', status: 'En ligne', mode: 'Autonome', recette: '1 984 000', cuves: [50, 70, 60], etat: 'Normal' },
  { id: 'tsevie-nord', name: 'Zener Tsévié Nord', short: 'Tsévié Nord', type: 'Gérance libre', zone: 'Zio', client: 'Sté Kodjo Services', status: 'En ligne', mode: 'Conventionnel', recette: '1 205 400', cuves: [60, 50, 45], etat: 'Normal' },
  { id: 'aneho-plage', name: 'Zener Aného Plage', short: 'Aného Plage', type: 'Succursale', zone: 'Lacs', client: 'Groupe Zener', status: 'En ligne', mode: 'Conventionnel', recette: '986 300', cuves: [55, 60, 50], etat: 'Normal' },
  { id: 'kpalime-centre', name: 'Zener Kpalimé Centre', short: 'Kpalimé Centre', type: 'Gérance libre', zone: 'Kloto', client: 'Ets Mensah', status: 'Instable', mode: 'Conventionnel', recette: '874 100', cuves: [45, 55, 50], etat: 'Normal' },
  { id: 'notse', name: 'Zener Notsè', short: 'Notsè', type: 'Succursale', zone: 'Haho', client: 'Groupe Zener', status: 'En ligne', mode: 'Autonome', recette: '642 800', cuves: [60, 45, 55], etat: 'Normal' },
  { id: 'atakpame', name: 'Zener Atakpamé', short: 'Atakpamé', type: 'Gérance libre', zone: 'Ogou', client: 'Agbeko Pétrole', status: 'Hors ligne', mode: 'Conventionnel', recette: null, cuves: [55, 40, 50], etat: 'Lecteur déconnecté' },
  { id: 'sokode', name: 'Zener Sokodé', short: 'Sokodé', type: 'Succursale', zone: 'Tchaoudjo', client: 'Groupe Zener', status: 'En ligne', mode: 'Autonome', recette: '1 120 500', cuves: [60, 55, 50], etat: 'Normal' },
  { id: 'kara-centre', name: 'Zener Kara Centre', short: 'Kara Centre', type: 'Succursale', zone: 'Kozah', client: 'Groupe Zener', status: 'En ligne', mode: 'Autonome', recette: '1 512 900', cuves: [80, 75, 70], etat: 'Normal' },
  { id: 'dapaong', name: 'Zener Dapaong', short: 'Dapaong', type: 'Gérance libre', zone: 'Tône', client: 'Sté Savanes Énergie', status: 'Hors ligne', mode: 'Conventionnel', recette: null, cuves: [50, 45, 40], etat: '' },
];

export const stationById = (id) => STATIONS.find((s) => s.id === id) || STATIONS[0];
