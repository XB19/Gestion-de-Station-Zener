// Accès à l'API ZENER (backend Spring Boot). L'adresse se règle avec VITE_API_URL.
export const API_URL = (import.meta.env.VITE_API_URL || 'http://localhost:8090').replace(/\/$/, '');

const TOKEN_KEY = 'zener.token';
const USER_KEY = 'zener.user';

// « Rester connecté » : jeton dans localStorage ; sinon sessionStorage (fermeture = déconnexion)
function store(remember) {
  return remember ? localStorage : sessionStorage;
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY);
}

export function getStoredUser() {
  const raw = localStorage.getItem(USER_KEY) || sessionStorage.getItem(USER_KEY);
  try {
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function saveSession(token, user, remember) {
  clearSession();
  store(remember).setItem(TOKEN_KEY, token);
  store(remember).setItem(USER_KEY, JSON.stringify(user));
}

export function updateStoredUser(user) {
  const s = localStorage.getItem(TOKEN_KEY) ? localStorage : sessionStorage;
  s.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  [localStorage, sessionStorage].forEach((s) => {
    s.removeItem(TOKEN_KEY);
    s.removeItem(USER_KEY);
  });
}

export class ApiError extends Error {
  constructor(message, code, status) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

/** Requête JSON. Les erreurs de l'API ({ code, message }) deviennent des ApiError en français. */
export async function api(path, { method = 'GET', body, form, signal } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (form) {
    payload = form; // FormData : le navigateur fixe le Content-Type
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(`${API_URL}/api${path}`, { method, headers, body: payload, signal });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(`Serveur injoignable (${API_URL}). Le backend est-il démarré ?`, 'RESEAU', 0);
  }
  if (res.status === 401 && path !== '/auth/login') {
    clearSession();
    onUnauthorized();
    throw new ApiError('Session expirée, reconnectez-vous', 'NON_CONNECTE', 401);
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    throw new ApiError((data && data.message) || `Erreur ${res.status}`, data && data.code, res.status);
  }
  return data;
}

export const get = (path, opts) => api(path, opts);
export const post = (path, body) => api(path, { method: 'POST', body: body ?? {} });
export const put = (path, body) => api(path, { method: 'PUT', body: body ?? {} });
export const del = (path) => api(path, { method: 'DELETE' });
export const upload = (path, file, field = 'file') => {
  const form = new FormData();
  form.append(field, file);
  return api(path, { method: 'POST', form });
};

/** Construit une query string en ignorant les valeurs vides. */
export function qs(params) {
  const u = new URLSearchParams();
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') u.append(k, v);
  });
  const s = u.toString();
  return s ? `?${s}` : '';
}

/** Adresse d'un fichier servi par le backend (photos). */
export function fileUrl(path) {
  return path ? `${API_URL}${path}` : null;
}

/** Téléchargement d'un fichier protégé (modèle de recharge…). */
export async function download(path, filename) {
  const res = await fetch(`${API_URL}/api${path}`, { headers: { Authorization: `Bearer ${getToken()}` } });
  if (!res.ok) throw new ApiError('Téléchargement impossible', 'TELECHARGEMENT', res.status);
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}
