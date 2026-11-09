import {
  Fuel, Wallet, ShieldCheck, CreditCard, ArrowLeftRight, Flame, Mail, Pencil, Building2, CloudOff, Wifi, WifiOff, LogIn,
  ShoppingCart, Truck, CircleCheck, CircleX, Ban, Users, FileBarChart, Package, Coins, MapPin,
} from 'lucide-react';

// Icône et couleur d'une entrée du journal d'opérations (type puis catégorie).
const BY_TYPE = {
  VENTE_CARBURANT: [Fuel, 'blue'],
  REFUS_CARTE: [Ban, 'red'],
  RECHARGE: [Wallet, 'green'],
  RECHARGE_MASSE: [Wallet, 'green'],
  VALIDATION_DEMANDEE: [ShieldCheck, 'orange'],
  VALIDATION_ETAPE: [CircleCheck, 'green'],
  VALIDATION_APPROUVEE: [CircleCheck, 'green'],
  VALIDATION_REFUSEE: [CircleX, 'red'],
  VENTE_CARTE: [CreditCard, 'navy'],
  CARTE_ACTIVEE: [CreditCard, 'green'],
  CARTE_CREEE: [CreditCard, 'blue'],
  CARTE_EMPLOYE: [CreditCard, 'blue'],
  ECHANGE_GAZ: [Flame, 'red'],
  DISTRIBUTEUR_GAZ: [Flame, 'orange'],
  VENTE_BOUTIQUE: [ShoppingCart, 'purple'],
  BASCULE_MODE: [ArrowLeftRight, 'green'],
  ALERTE_STOCK: [Mail, 'red'],
  CONNEXION_STATION: [Wifi, 'gray'],
  CONNEXION: [LogIn, 'gray'],
  STATION_ATTRIBUEE: [Building2, 'purple'],
  SYNCHRO_PDV: [CloudOff, 'orange'],
  POSTE_OUVERT: [Users, 'blue'],
  POSTE_CLOS: [Coins, 'green'],
  REMISE_ESPECES: [Coins, 'green'],
  RAPPORT_VALIDE: [FileBarChart, 'blue'],
  RAPPORTS_GENERES: [FileBarChart, 'gray'],
  LIVRAISON_CARBURANT: [Truck, 'green'],
  ENTREE_STOCK: [Package, 'green'],
};

const BY_CATEGORY = {
  VENTES_CARBURANT: [Fuel, 'blue'],
  RECHARGES: [Wallet, 'green'],
  VENTES_CARTES: [CreditCard, 'navy'],
  VENTES_BOUTIQUE: [ShoppingCart, 'purple'],
  VALIDATIONS: [ShieldCheck, 'orange'],
  MODIFICATIONS: [Pencil, 'gray'],
  SYSTEME: [Wifi, 'gray'],
  LIVRAISONS: [Truck, 'teal'],
};

export function operationIcon(op) {
  if (op?.type === 'CONNEXION_STATION' && /hors ligne/i.test(op.title || '')) return [WifiOff, 'red'];
  if (op?.type?.startsWith('LIVRAISON')) return [Truck, 'teal'];
  return BY_TYPE[op?.type] || BY_CATEGORY[op?.category] || [MapPin, 'gray'];
}

export const CATEGORY_LABELS = {
  VENTES_CARBURANT: 'Ventes carburant',
  RECHARGES: 'Recharges',
  VENTES_CARTES: 'Ventes cartes',
  VENTES_BOUTIQUE: 'Boutique & gaz',
  VALIDATIONS: 'Validations',
  MODIFICATIONS: 'Modifications',
  SYSTEME: 'Système',
  LIVRAISONS: 'Livraisons',
};

export const CATEGORY_COLORS = {
  VENTES_CARBURANT: '#1b66b8',
  RECHARGES: '#5aa935',
  VENTES_CARTES: '#283c8f',
  VENTES_BOUTIQUE: '#8b5cf6',
  VALIDATIONS: '#f29e1f',
  MODIFICATIONS: '#94a3b8',
  SYSTEME: '#cbd5e1',
  LIVRAISONS: '#13917d',
};
