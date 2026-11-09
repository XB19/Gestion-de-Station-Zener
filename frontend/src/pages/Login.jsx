import { useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import {
  Mail, Lock, Eye, EyeOff, ArrowRight, ShieldCheck, Radio, Banknote, Wifi, CreditCard, Fuel, Wallet,
} from 'lucide-react';
import { ZenerMark, ZenerWordmark } from '../components/ZenerLogo.jsx';
import { Checkbox, IcoBox } from '../components/ui.jsx';
import { api } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, compact, date, num, pct } from '../lib/format.js';
import './login.css';

// Comptes de démonstration (mot de passe commun « zener-station ») : un clic remplit le formulaire
const ROLES = [
  ['Admin', 'ama.kpodar@zener.tg'],
  ['Gérant', 'kossi.amegah@zener.tg'],
  ['Chef de poste', 'yao.agbeko@zener.tg'],
  ['Pompiste', 'komlan.tetteh@zener.tg'],
  ['Comptable', 'abla.gbeasor@zener.tg'],
  ['DAF', 'mawuli.kpodar@zener.tg'],
  ['Agent PDV', 'delali.agbo@zener.tg'],
  ['Livreur', 'edem.akakpo@zener.tg'],
];

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, login } = useSession();
  const [show, setShow] = useState(false);
  const [remember, setRemember] = useState(true);
  const [email, setEmail] = useState('ama.kpodar@zener.tg');
  const [pwd, setPwd] = useState('zener-station');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [overview, setOverview] = useState(null);

  // chiffres du réseau et dernières opérations (sans connexion), rafraîchis toutes les 10 s
  useEffect(() => {
    let alive = true;
    const load = () => api('/public/overview').then((o) => alive && setOverview(o)).catch(() => {});
    load();
    const id = setInterval(load, 10_000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, []);

  if (user) return <Navigate to={location.state?.from || '/'} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), pwd, remember);
      navigate(location.state?.from || '/', { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const o = overview || {};
  const change = o.revenueYesterday ? ((o.revenueToday - o.revenueYesterday) / o.revenueYesterday) * 100 : null;
  const stats = [
    { icon: Banknote, label: 'Recette du jour', value: overview ? compact(o.revenueToday) : '…',
      sub: change === null ? 'FCFA' : `FCFA · ${change >= 0 ? '+' : '−'}${pct(change)}` },
    { icon: Wifi, label: 'Stations en ligne', value: overview ? `${o.stationsOnline} / ${o.stationsTotal}` : '…',
      sub: overview ? `${o.countries} pays` : '' },
    { icon: CreditCard, label: 'Cartes actives', value: overview ? num(o.activeCards) : '…',
      sub: overview ? `dont ${num(o.fleetCards)} flottes` : '' },
  ];
  const FEED_ICON = { VENTES_CARBURANT: [Fuel, 'blue'], RECHARGES: [Wallet, 'green'], VALIDATIONS: [ShieldCheck, 'orange'],
    VENTES_CARTES: [CreditCard, 'navy'] };

  return (
    <div className="login">
      {/* Panneau gauche */}
      <section className="login-hero">
        <div className="hero-rings" />
        <div className="hero-brand">
          <span className="hero-logo">
            <ZenerMark size={34} />
          </span>
          Station Manager
        </div>

        <div className="hero-body">
          <span className="hero-chip">
            <Radio size={14} strokeWidth={2} /> Supervision en temps réel
          </span>
          <h1>Pilotez tout votre réseau de stations depuis un seul écran.</h1>
          <p>
            Cartes, recharges, ventes, stocks et points de vente : chaque opération est tracée, validée et visible en
            direct.
          </p>

          <div className="hero-stats">
            {stats.map(({ icon: Icon, label, value, sub }) => (
              <div key={label} className="hero-stat">
                <div className="l">
                  <Icon size={16} strokeWidth={1.8} /> {label}
                </div>
                <div className="v num">{value}</div>
                <div className="s">{sub}</div>
              </div>
            ))}
          </div>

          <div className="hero-feed">
            <div className="row between" style={{ marginBottom: 6 }}>
              <span className="strong" style={{ fontSize: 14 }}>Flux en direct</span>
              <span className="live-pill" style={{ height: 24, fontSize: 12 }}>
                <span className="pulse" /> Live
              </span>
            </div>
            {(o.feed || []).map((f, i) => {
              const [icon, tone] = FEED_ICON[f.category] || [Fuel, 'blue'];
              return (
                <div key={`${f.at}-${i}`} className="list-item" style={{ padding: '11px 0' }}>
                  <IcoBox icon={icon} tone={tone} size={30} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="strong" style={{ fontSize: 13.5 }}>{f.title}</div>
                    <div className="muted" style={{ fontSize: 12.5 }}>{[f.place, ago(f.at)].filter(Boolean).join(' · ')}</div>
                  </div>
                  <div className="strong num">{f.amount ? `${compact(f.amount)} F` : ''}</div>
                </div>
              );
            })}
            {!overview && <div className="muted" style={{ padding: '12px 0', fontSize: 13 }}>Connexion au serveur…</div>}
          </div>
        </div>

        <div className="hero-foot">© 2026 ZENER · Système de gestion des stations d’essence</div>
      </section>

      {/* Formulaire */}
      <section className="login-form-wrap">
        <form className="login-form" onSubmit={submit}>
          <div className="login-logo">
            <ZenerMark size={72} />
            <ZenerWordmark size={38} />
          </div>

          <h2>Connexion</h2>
          <p className="muted" style={{ fontSize: 14, marginTop: 8 }}>Accédez à votre espace de gestion.</p>

          <label className="field-label" style={{ marginTop: 30 }}>Adresse email</label>
          <label className="input login-input">
            <Mail size={18} strokeWidth={1.7} />
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>

          <label className="field-label" style={{ marginTop: 18 }}>Mot de passe</label>
          <label className="input login-input">
            <Lock size={18} strokeWidth={1.7} />
            <input type={show ? 'text' : 'password'} value={pwd} onChange={(e) => setPwd(e.target.value)} />
            <button type="button" className="link-more" onClick={() => setShow(!show)} title="Afficher">
              {show ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </label>

          <div className="row between" style={{ marginTop: 16 }}>
            <span className="row" style={{ gap: 10, color: 'var(--text-2)' }}>
              <Checkbox checked={remember} onChange={setRemember} /> Rester connecté
            </span>
            <a className="strong" style={{ color: 'var(--primary)' }} href="#/login">Mot de passe oublié ?</a>
          </div>

          {error && <div className="form-error" style={{ marginTop: 18 }}>{error}</div>}

          <button type="submit" disabled={busy} className="btn btn-primary btn-block" style={{ height: 46, marginTop: 24, fontSize: 15 }}>
            {busy ? <span className="spinner" style={{ width: 16, height: 16, borderTopColor: '#fff' }} /> : null}
            Se connecter <ArrowRight size={17} />
          </button>

          <div className="divider-text" style={{ marginTop: 30 }}>Accès selon votre rôle · comptes de démonstration</div>
          <div className="row" style={{ flexWrap: 'wrap', gap: 8, marginTop: 18 }}>
            {ROLES.map(([r, mail]) => (
              <button
                type="button"
                key={r}
                className={`badge ${email === mail ? 'blue' : 'gray'}`}
                style={{ height: 28, padding: '0 10px', border: 0, cursor: 'pointer' }}
                title={mail}
                onClick={() => {
                  setEmail(mail);
                  setPwd('zener-station');
                }}
              >
                {r}
              </button>
            ))}
          </div>

          <div className="row muted" style={{ marginTop: 34, fontSize: 13, gap: 8 }}>
            <ShieldCheck size={16} color="var(--green)" /> Connexion chiffrée
            {o.licenceEnd ? ` · Licence active jusqu’au ${date(o.licenceEnd)}` : ''}
          </div>
        </form>
      </section>
    </div>
  );
}
