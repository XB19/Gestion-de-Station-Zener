import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './client.js';
import { useLiveEvent } from './live.jsx';

/**
 * Charge une ressource de l'API. path = null : rien n'est chargé.
 * Les données précédentes restent affichées pendant un rechargement (pas de clignotement).
 */
export function useApi(path) {
  const [state, setState] = useState({ data: null, error: null, loading: !!path });
  const seq = useRef(0);

  const load = useCallback(
    (silent) => {
      if (!path) {
        setState({ data: null, error: null, loading: false });
        return Promise.resolve(null);
      }
      const n = ++seq.current;
      if (!silent) setState((s) => ({ ...s, loading: true }));
      return api(path)
        .then((data) => {
          if (n === seq.current) setState({ data, error: null, loading: false });
          return data;
        })
        .catch((error) => {
          if (n === seq.current) setState((s) => ({ ...s, error, loading: false }));
          return null;
        });
    },
    [path],
  );

  useEffect(() => {
    load();
  }, [load]);

  return { ...state, reload: () => load(true), setData: (data) => setState((s) => ({ ...s, data })) };
}

/**
 * Recharge silencieusement quand un événement temps réel arrive (au plus une fois toutes les
 * `wait` millisecondes) : les écrans restent à jour sans action de l'utilisateur.
 */
export function useLiveReload(reload, types, wait = 2500) {
  const timer = useRef(null);
  const last = useRef(0);
  useLiveEvent(types, () => {
    if (timer.current) return;
    const delay = Math.max(0, wait - (Date.now() - last.current));
    timer.current = setTimeout(() => {
      timer.current = null;
      last.current = Date.now();
      reload();
    }, delay);
  });
  useEffect(() => () => clearTimeout(timer.current), []);
}

/** Rechargement périodique (ancienneté « il y a x min », états des stations…). */
export function useInterval(fn, ms) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    const id = setInterval(() => ref.current(), ms);
    return () => clearInterval(id);
  }, [ms]);
}

/** Action asynchrone avec indicateur d'attente. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const run = useCallback(async (fn) => {
    setBusy(true);
    try {
      return await fn();
    } finally {
      setBusy(false);
    }
  }, []);
  return [busy, run];
}
