import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  LayoutGrid, Fuel, Store, CreditCard, Wallet, ShieldCheck, ShoppingCart, Users, Building2,
  FileBarChart, ScrollText, Settings, LogOut, Search, Bell, ChevronDown, Globe, TriangleAlert, Truck, Timer,
} from 'lucide-react';
import ZenerLogo from './ZenerLogo.jsx';
import { Avatar, IcoBox } from './ui.jsx';
import { useSession } from '../api/session.jsx';
import { useLiveStatus } from '../api/live.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, fileUrl, qs } from '../api/client.js';
import { ago, avatarColor, cardNumber, money } from '../lib/format.js';

// Chaque entrée n'est visible qu'avec au moins un des droits indiqués (null = tout le monde)
const NAV = [
  {
    section: 'Supervision',
    items: [
      { to: '/', label: 'Tableau de bord', icon: LayoutGrid, end: true, rights: null },
      { to: '/stations', label: 'Stations', icon: Fuel, rights: ['STATIONS_GERER', 'STATION_EXPLOITER', 'VENTES_POMPE', 'RAPPORTS'] },
      { to: '/points-de-vente', label: 'Points de vente', icon: Store, rights: ['PDV_GERER', 'PDV_VENDRE', 'PDV_VALIDER'] },
      { to: '/mobilite', label: 'Zen Mobility', icon: Truck, rights: ['LIVRAISONS', 'LIVRER'] },
    ],
  },
  {
    section: 'Cartes',
    items: [
      { to: '/cartes', label: 'Cartes', icon: CreditCard, rights: ['CARTES_GERER', 'CARTES_ACTIVER', 'RECHARGER', 'CARTES_EMPLOYES'] },
      { to: '/rechargements', label: 'Rechargements', icon: Wallet, rights: ['RECHARGER', 'TRANSACTIONS'] },
      { to: '/validations', label: 'Validations', icon: ShieldCheck, badge: true,
        rights: ['VALIDER_MONTANTS', 'PDV_VALIDER', 'RECHARGER', 'TRANSACTIONS'] },
    ],
  },
  {
    section: 'Commerce',
    items: [
      { to: '/mon-poste', label: 'Mon poste', icon: Timer, rights: ['VENTES_POMPE'] },
      { to: '/ventes-stocks', label: 'Ventes & stocks', icon: ShoppingCart,
        rights: ['STOCKS', 'VENTES_BOUTIQUE', 'TRANSACTIONS', 'PRIX', 'VENTES_POMPE'] },
    ],
  },
  {
    section: 'Organisation',
    items: [
      { to: '/employes', label: 'Employés & rôles', icon: Users, rights: ['EMPLOYES', 'CARTES_EMPLOYES'] },
      { to: '/clients', label: 'Clients & licences', icon: Building2, rights: ['CLIENTS_LICENCES'] },
    ],
  },
  {
    section: 'Analyse',
    items: [
      { to: '/rapports', label: 'Rapports', icon: FileBarChart, rights: ['RAPPORTS'] },
      { to: '/journal', label: "Journal d'opérations", icon: ScrollText, rights: ['JOURNAL'] },
    ],
  },
];

const ROLE_TITLES = { ADMIN: 'Administrateur', GERANT: 'Gérant', CHEF_POSTE: 'Chef de poste', POMPISTE: 'Pompiste',
  COMPTABLE: 'Comptable', DAF: 'DAF', AGENT_PDV: 'Agent PDV', LIVREUR: 'Livreur' };

function Sidebar() {
  const { user, can, logout } = useSession();
  const summary = useApi('/stations/summary');
  const pending = useApi(can('VALIDER_MONTANTS', 'PDV_VALIDER', 'RECHARGER', 'TRANSACTIONS') ? '/validations/summary' : null);
  useLiveReload(pending.reload, ['operation'], 4000);

  const s = summary.data;
  const countries = s?.byCountry?.length || 1;
  return (
    <aside className="sidebar">
      <NavLink to="/" className="sidebar-logo" title="Tableau de bord" style={{ display: 'block' }}>
        <ZenerLogo size={34} />
      </NavLink>

      <button className="org-switch" type="button">
        <span className="org-icon">
          <Globe size={16} strokeWidth={1.8} />
        </span>
        <span>
          <div className="t">{user?.stationName || 'Réseau Zener'}</div>
          <div className="s">{s ? `${s.total} station${s.total > 1 ? 's' : ''} · ${countries} pays` : '…'}</div>
        </span>
        <ChevronDown size={16} className="chev" />
      </button>

      <nav>
        {NAV.map((group) => {
          const items = group.items.filter((i) => !i.rights || can(...i.rights));
          if (!items.length) return null;
          return (
            <div key={group.section}>
              <div className="nav-section">{group.section}</div>
              {items.map(({ to, label, icon: Icon, end, badge }) => (
                <NavLink key={to} to={to} end={end} title={label} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                  <Icon size={19} strokeWidth={1.7} />
                  {label}
                  {badge && pending.data?.pending > 0 && <span className="nav-badge">{pending.data.pending}</span>}
                </NavLink>
              ))}
            </div>
          );
        })}
      </nav>

      <div className="sidebar-bottom">
        {can('PARAMETRES', 'JOURNAL') && (
          <NavLink to="/parametres" title="Paramètres" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <Settings size={19} strokeWidth={1.7} />
            Paramètres
          </NavLink>
        )}
        <div className="sidebar-user">
          <Avatar initials={user?.initials} color={avatarColor(user?.id)} size={38} photo={fileUrl(user?.photoUrl)} />
          <div style={{ minWidth: 0 }}>
            <div className="n">{user?.name}</div>
            <div className="r">{ROLE_TITLES[user?.role] || user?.roleLabel}</div>
          </div>
          <button className="logout" title="Se déconnecter" onClick={logout}>
            <LogOut size={18} strokeWidth={1.7} />
          </button>
        </div>
      </div>
    </aside>
  );
}

/** Ferme une liste déroulante au clic en dehors. */
function useOutside(ref, onOutside) {
  useEffect(() => {
    const fn = (e) => ref.current && !ref.current.contains(e.target) && onOutside();
    document.addEventListener('mousedown', fn);
    return () => document.removeEventListener('mousedown', fn);
  }, [ref, onOutside]);
}

/** Recherche globale : cartes, stations, employés. */
function GlobalSearch() {
  const navigate = useNavigate();
  const { can } = useSession();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState(null);
  const box = useRef(null);
  const input = useRef(null);
  useOutside(box, () => setOpen(false));

  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    const term = q.trim();
    if (term.length < 2) {
      setResults(null);
      return undefined;
    }
    const t = setTimeout(async () => {
      const [cards, stations, employees] = await Promise.all([
        can('CARTES_GERER', 'CARTES_ACTIVER', 'RECHARGER') ? api(`/cards${qs({ q: term, size: 5 })}`).catch(() => null) : null,
        api(`/stations${qs({ q: term })}`).catch(() => null),
        can('EMPLOYES', 'CARTES_EMPLOYES') ? api(`/employees${qs({ q: term, size: 5 })}`).catch(() => null) : null,
      ]);
      setResults({ cards: cards?.items || [], stations: (stations || []).slice(0, 5), employees: employees?.items || [] });
      setOpen(true);
    }, 250);
    return () => clearTimeout(t);
  }, [q, can]);

  const go = (path) => {
    setOpen(false);
    setQ('');
    navigate(path);
  };
  const empty = results && !results.cards.length && !results.stations.length && !results.employees.length;

  return (
    <div ref={box} style={{ position: 'relative' }}>
      <label className="global-search">
        <Search size={17} strokeWidth={1.8} />
        <input
          ref={input}
          placeholder="Rechercher une carte, une station, un employé…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onFocus={() => results && setOpen(true)}
        />
        <span className="kbd">Ctrl K</span>
      </label>
      {open && results && (
        <div className="dropdown" style={{ left: 0, right: 'auto', width: 420 }}>
          {empty && <div className="muted" style={{ padding: 14 }}>Aucun résultat</div>}
          {results.cards.length > 0 && <div className="dd-section">Cartes</div>}
          {results.cards.map((c) => (
            <button key={`c${c.id}`} className="dd-item" onClick={() => go(`/cartes/${c.id}`)}>
              <IcoBox icon={CreditCard} tone="navy" size={30} />
              <span style={{ flex: 1 }}>
                <div className="strong num">{cardNumber(c.number)}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{c.holderName || 'Sans titulaire'} · {c.statusLabel}</div>
              </span>
              <span className="num muted">{money(c.balance)}</span>
            </button>
          ))}
          {results.stations.length > 0 && <div className="dd-section">Stations</div>}
          {results.stations.map((s) => (
            <button key={`s${s.id}`} className="dd-item" onClick={() => go(`/stations/${s.id}`)}>
              <IcoBox icon={Fuel} tone="blue" size={30} />
              <span style={{ flex: 1 }}>
                <div className="strong">{s.name}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{s.typeLabel} · {s.statusLabel}</div>
              </span>
            </button>
          ))}
          {results.employees.length > 0 && <div className="dd-section">Employés</div>}
          {results.employees.map((e) => (
            <button key={`e${e.id}`} className="dd-item" onClick={() => go(`/employes?id=${e.id}`)}>
              <Avatar initials={e.initials} color={avatarColor(e.id)} size={30} photo={fileUrl(e.photoUrl)} />
              <span style={{ flex: 1 }}>
                <div className="strong">{e.name}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{e.roleLabel}{e.stationShortName ? ` · ${e.stationShortName}` : ''}</div>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Choix rapide d'une station : ouvre sa fiche. */
function StationPicker() {
  const navigate = useNavigate();
  const stations = useApi('/stations');
  const list = stations.data || [];
  return (
    <label className="select" style={{ height: 38 }}>
      <Fuel size={16} strokeWidth={1.8} />
      <select value="" onChange={(e) => e.target.value && navigate(`/stations/${e.target.value}`)}>
        <option value="">{list.length === 1 ? list[0].shortName : 'Toutes les stations'}</option>
        {list.map((s) => <option key={s.id} value={s.id}>{s.shortName}</option>)}
      </select>
      <ChevronDown size={15} />
    </label>
  );
}

/** Alertes : validations en attente et alertes de stock ouvertes. */
function Notifications() {
  const navigate = useNavigate();
  const { can } = useSession();
  const [open, setOpen] = useState(false);
  const box = useRef(null);
  useOutside(box, () => setOpen(false));
  const alerts = useApi('/stock/alerts');
  const validations = useApi(can('VALIDER_MONTANTS', 'PDV_VALIDER') ? '/validations?status=EN_ATTENTE&size=5' : null);
  useLiveReload(() => {
    alerts.reload();
    validations.reload();
  }, ['alert', 'operation'], 5000);
  const a = alerts.data || [];
  const v = validations.data?.items || [];
  const count = a.length + (validations.data?.total || 0);
  return (
    <div ref={box} style={{ position: 'relative' }}>
      <button className="icon-btn" title="Notifications" onClick={() => setOpen(!open)}>
        <Bell size={18} strokeWidth={1.8} />
        {count > 0 && <span className="dot" />}
      </button>
      {open && (
        <div className="dropdown" style={{ width: 380 }}>
          {v.length > 0 && <div className="dd-section">Validations en attente</div>}
          {v.map((r) => (
            <button key={`v${r.id}`} className="dd-item" onClick={() => { setOpen(false); navigate(`/validations?id=${r.id}`); }}>
              <IcoBox icon={ShieldCheck} tone="orange" size={30} />
              <span style={{ flex: 1 }}>
                <div className="strong">{r.beneficiary}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{r.statusLabel} · {ago(r.requestedAt)}</div>
              </span>
              <span className="num strong">{r.amount ? money(r.amount) : ''}</span>
            </button>
          ))}
          <div className="dd-section">Alertes de stock ({a.length})</div>
          {a.slice(0, 8).map((x) => (
            <button key={`a${x.id}`} className="dd-item" onClick={() => { setOpen(false); navigate('/ventes-stocks'); }}>
              <IcoBox icon={TriangleAlert} tone={x.severity === 'CRITIQUE' ? 'red' : 'orange'} size={30} />
              <span style={{ flex: 1 }}>
                <div className="strong">{x.productName} · {x.place}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{x.severity === 'CRITIQUE' ? 'Critique' : 'Bas'} · {ago(x.raisedAt)}</div>
              </span>
            </button>
          ))}
          {a.length === 0 && <div className="muted" style={{ padding: '6px 12px 12px' }}>Aucune alerte</div>}
        </div>
      )}
    </div>
  );
}

function Topbar() {
  const connected = useLiveStatus();
  return (
    <header className="topbar">
      <GlobalSearch />
      <div className="topbar-right">
        <span className={`live-pill ${connected ? '' : 'off'}`} title={connected ? 'Flux temps réel connecté' : 'Reconnexion…'}>
          <span className="pulse" /> {connected ? 'Temps réel' : 'Hors ligne'}
        </span>
        <StationPicker />
        <Notifications />
      </div>
    </header>
  );
}

export default function Layout() {
  return (
    <div className="app">
      <Sidebar />
      <div className="main">
        <Topbar />
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
