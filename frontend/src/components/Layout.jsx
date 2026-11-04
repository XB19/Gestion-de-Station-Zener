import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  LayoutGrid, Fuel, Store, CreditCard, Wallet, ShieldCheck, ShoppingCart, Users, Building2,
  FileBarChart, ScrollText, Settings, LogOut, Search, Bell, ChevronDown, Globe,
} from 'lucide-react';
import ZenerLogo from './ZenerLogo.jsx';
import { Avatar } from './ui.jsx';

const NAV = [
  {
    section: 'Supervision',
    items: [
      { to: '/', label: 'Tableau de bord', icon: LayoutGrid, end: true },
      { to: '/stations', label: 'Stations', icon: Fuel },
      { to: '/points-de-vente', label: 'Points de vente', icon: Store },
    ],
  },
  {
    section: 'Cartes',
    items: [
      { to: '/cartes', label: 'Cartes', icon: CreditCard },
      { to: '/rechargements', label: 'Rechargements', icon: Wallet },
      { to: '/validations', label: 'Validations', icon: ShieldCheck, badge: 4 },
    ],
  },
  { section: 'Commerce', items: [{ to: '/ventes-stocks', label: 'Ventes & stocks', icon: ShoppingCart }] },
  {
    section: 'Organisation',
    items: [
      { to: '/employes', label: 'Employés & rôles', icon: Users },
      { to: '/clients', label: 'Clients & licences', icon: Building2 },
    ],
  },
  {
    section: 'Analyse',
    items: [
      { to: '/rapports', label: 'Rapports', icon: FileBarChart },
      { to: '/journal', label: "Journal d'opérations", icon: ScrollText },
    ],
  },
];

function Sidebar() {
  const navigate = useNavigate();
  return (
    <aside className="sidebar">
      <div className="sidebar-logo">
        <ZenerLogo size={34} />
      </div>

      <button className="org-switch">
        <span className="org-icon">
          <Globe size={16} strokeWidth={1.8} />
        </span>
        <span>
          <div className="t">Zener Togo</div>
          <div className="s">42 stations · 3 pays</div>
        </span>
        <ChevronDown size={16} className="chev" />
      </button>

      <nav>
        {NAV.map((group) => (
          <div key={group.section}>
            <div className="nav-section">{group.section}</div>
            {group.items.map(({ to, label, icon: Icon, end, badge }) => (
              <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                <Icon size={19} strokeWidth={1.7} />
                {label}
                {badge && <span className="nav-badge">{badge}</span>}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      <div className="sidebar-bottom">
        <a className="nav-item" href="#/">
          <Settings size={19} strokeWidth={1.7} />
          Paramètres
        </a>
        <div className="sidebar-user">
          <Avatar initials="AK" color="navy" size={38} />
          <div>
            <div className="n">Ama Kpodar</div>
            <div className="r">Administratrice</div>
          </div>
          <button className="logout" title="Se déconnecter" onClick={() => navigate('/login')}>
            <LogOut size={18} strokeWidth={1.7} />
          </button>
        </div>
      </div>
    </aside>
  );
}

function Topbar() {
  return (
    <header className="topbar">
      <label className="global-search">
        <Search size={17} strokeWidth={1.8} />
        <input placeholder="Rechercher une carte, une station, un employé…" />
        <span className="kbd">Ctrl K</span>
      </label>
      <div className="topbar-right">
        <span className="live-pill">
          <span className="pulse" /> Temps réel
        </span>
        <button className="select" style={{ height: 38 }}>
          <Fuel size={16} strokeWidth={1.8} />
          Toutes les stations
          <ChevronDown size={15} />
        </button>
        <button className="icon-btn" title="Notifications">
          <Bell size={18} strokeWidth={1.8} />
          <span className="dot" />
        </button>
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
