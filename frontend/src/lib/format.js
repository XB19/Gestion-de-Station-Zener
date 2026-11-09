// Formats d'affichage (français, FCFA). L'API renvoie des nombres bruts.
const NBSP = ' ';

export function num(n, digits = 0) {
  if (n === null || n === undefined || n === '' || Number.isNaN(Number(n))) return '—';
  return Number(n)
    .toLocaleString('fr-FR', { minimumFractionDigits: digits, maximumFractionDigits: digits })
    .replace(/ | /g, ' ');
}

/** 1 234 500 (montant FCFA sans unité). */
export const fcfa = (n) => num(Math.round(Number(n) || 0));

/** 1 234 500 F */
export const money = (n) => (n === null || n === undefined ? '—' : `${fcfa(n)} F`);

/** Montant avec signe : +2 500 000 / −22 500 */
export function signed(n) {
  if (!n) return '0';
  return `${n > 0 ? '+' : '−'}${fcfa(Math.abs(n))}`;
}

/** 18,45 M · 842 k · 950 */
export function compact(n) {
  const v = Number(n) || 0;
  if (Math.abs(v) >= 1e6) return `${num(v / 1e6, v >= 1e8 ? 0 : 2).replace(/,00$/, '')} M`;
  if (Math.abs(v) >= 1e4) return `${num(v / 1e3, 0)} k`;
  return num(v);
}

export const liters = (n, digits = 0) => (n === null || n === undefined ? '—' : `${num(n, digits)} L`);

export function pct(n, digits = 1) {
  if (n === null || n === undefined) return '—';
  return `${num(Math.abs(n), digits).replace(/,0$/, '')}${NBSP}%`;
}

// ------------------------------------------------------------------ dates

const toDate = (d) => (d instanceof Date ? d : new Date(d));

export function time(d, seconds = false) {
  if (!d) return '—';
  return toDate(d).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', ...(seconds ? { second: '2-digit' } : {}) });
}

export function hhmm(t) {
  // "06:00:00" → "06:00"
  return t ? String(t).slice(0, 5) : '—';
}

export function date(d) {
  if (!d) return '—';
  return toDate(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

export function shortDate(d) {
  if (!d) return '—';
  return toDate(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
}

export function dayMonth(d) {
  if (!d) return '—';
  return toDate(d).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
}

export function longDate(d) {
  if (!d) return '—';
  return toDate(d).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
}

export function dateTime(d) {
  if (!d) return '—';
  return `${shortDate(d)} ${time(d)}`;
}

export function todayLabel() {
  const s = new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** « à l'instant », « il y a 3 min », « il y a 2 h », « hier », date. */
export function ago(d) {
  if (!d) return '—';
  const s = Math.round((Date.now() - toDate(d).getTime()) / 1000);
  if (s < 45) return 'à l’instant';
  if (s < 3600) return `il y a ${Math.max(1, Math.round(s / 60))} min`;
  if (s < 86400) return `il y a ${Math.round(s / 3600)} h`;
  if (s < 172800) return 'hier';
  return dayMonth(d);
}

export function isoDay(d = new Date()) {
  const x = toDate(d);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
}

// ------------------------------------------------------------------ libellés

export const FUEL_COLORS = { SUPER: '#1b66b8', GASOIL: '#5aa935', PETROLE: '#f29e1f' };

export const ROLE_TONES = {
  ADMIN: 'indigo', GERANT: 'blue', CHEF_POSTE: 'navy', POMPISTE: 'gray', COMPTABLE: 'green', DAF: 'orange',
  AGENT_PDV: 'purple', LIVREUR: 'teal',
};

const AVATAR_COLORS = ['blue', 'green', 'navy', 'teal', 'orange', 'purple', 'pink', 'forest', 'indigo'];
/** Couleur d'avatar stable pour un identifiant ou un nom. */
export function avatarColor(key) {
  const s = String(key ?? '');
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 997;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

export function initials(name) {
  const parts = String(name || '').trim().split(/\s+/);
  return ((parts[0]?.[0] || '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function shortName(name) {
  const parts = String(name || '').trim().split(/\s+/);
  return parts.length > 1 ? `${parts[0]} ${parts[parts.length - 1][0]}.` : name;
}

export const masked = (number) => (number ? `···${String(number).slice(-4)}` : '');

export function cardNumber(number) {
  const n = String(number || '').replace(/\D/g, '');
  return n.length === 12 ? `${n.slice(0, 4)} ${n.slice(4, 8)} ${n.slice(8)}` : number;
}

export const PAYMENT_LABELS = {
  ESPECES: 'Espèces', MOBILE_MONEY: 'Mobile money', CARTE: 'Carte', VIREMENT: 'Virement', CARTE_BANCAIRE: 'Carte bancaire',
};

/** Export CSV (séparateur ; pour Excel en français). */
export function exportCsv(filename, columns, rows) {
  const esc = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [columns.map((c) => esc(c.label)).join(';')];
  rows.forEach((r) => lines.push(columns.map((c) => esc(typeof c.value === 'function' ? c.value(r) : r[c.value])).join(';')));
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}
