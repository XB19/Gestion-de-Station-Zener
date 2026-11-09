import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { api, clearSession, getStoredUser, getToken, saveSession, setUnauthorizedHandler, updateStoredUser } from './client.js';

const SessionContext = createContext(null);

export function SessionProvider({ children }) {
  const navigate = useNavigate();
  const [user, setUser] = useState(() => (getToken() ? getStoredUser() : null));

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      navigate('/login', { replace: true });
    });
  }, [navigate]);

  // profil à jour (droits modifiés, photo…) au chargement
  useEffect(() => {
    if (!getToken()) return;
    api('/auth/me')
      .then((u) => {
        setUser(u);
        updateStoredUser(u);
      })
      .catch(() => {});
  }, []);

  const login = useCallback(async (email, password, remember) => {
    const res = await api('/auth/login', { method: 'POST', body: { email, password, remember } });
    saveSession(res.token, res.user, remember);
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
    navigate('/login', { replace: true });
  }, [navigate]);

  const value = useMemo(
    () => ({
      user,
      login,
      logout,
      can: (...rights) => !!user && rights.some((r) => user.rights?.includes(r)),
    }),
    [user, login, logout],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  return useContext(SessionContext);
}

/** Pages réservées aux utilisateurs connectés. */
export function RequireAuth({ children }) {
  const { user } = useSession();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}
