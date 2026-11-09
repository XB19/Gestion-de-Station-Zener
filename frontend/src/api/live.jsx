import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { API_URL, getToken } from './client.js';
import { useSession } from './session.jsx';

// Flux temps réel (Server-Sent Events sur /api/live) partagé par toute l'application.
// Événements : operation, sale, station, pump, alert, location, delivery, shift, recharge-batch.
const EVENTS = ['operation', 'sale', 'station', 'pump', 'alert', 'location', 'delivery', 'shift', 'recharge-batch'];

const LiveContext = createContext({ connected: false, subscribe: () => () => {} });

export function LiveProvider({ children }) {
  const { user } = useSession();
  const listeners = useRef(new Set());
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!user) return undefined;
    let source;
    let retry;
    let closed = false;
    const open = () => {
      source = new EventSource(`${API_URL}/api/live?access_token=${encodeURIComponent(getToken() || '')}`);
      source.addEventListener('hello', () => setConnected(true));
      source.onopen = () => setConnected(true);
      source.onerror = () => {
        setConnected(false);
        source.close();
        if (!closed) retry = setTimeout(open, 4000); // reconnexion automatique
      };
      EVENTS.forEach((type) =>
        source.addEventListener(type, (e) => {
          let data = null;
          try {
            data = JSON.parse(e.data);
          } catch {
            data = e.data;
          }
          listeners.current.forEach((fn) => fn(type, data));
        }),
      );
    };
    open();
    return () => {
      closed = true;
      clearTimeout(retry);
      source?.close();
      setConnected(false);
    };
  }, [user]);

  const subscribe = (fn) => {
    listeners.current.add(fn);
    return () => listeners.current.delete(fn);
  };

  return <LiveContext.Provider value={{ connected, subscribe }}>{children}</LiveContext.Provider>;
}

export function useLiveStatus() {
  return useContext(LiveContext).connected;
}

/** Appelle handler(type, data) pour chaque événement temps réel des types demandés (tous si vide). */
export function useLiveEvent(types, handler) {
  const { subscribe } = useContext(LiveContext);
  const ref = useRef(handler);
  ref.current = handler;
  const key = (types || []).join(',');
  useEffect(() => {
    const wanted = key ? key.split(',') : null;
    return subscribe((type, data) => {
      if (!wanted || wanted.includes(type)) ref.current(type, data);
    });
  }, [key, subscribe]);
}
