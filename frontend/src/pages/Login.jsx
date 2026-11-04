import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Mail, Lock, Eye, EyeOff, ArrowRight, ShieldCheck, Radio, Banknote, Wifi, CreditCard, Fuel, Wallet,
} from 'lucide-react';
import { ZenerMark, ZenerWordmark } from '../components/ZenerLogo.jsx';
import { Checkbox, IcoBox } from '../components/ui.jsx';
import './login.css';

const STATS = [
  { icon: Banknote, label: 'Recette du jour', value: '18,45 M', sub: 'FCFA · +12,4 %' },
  { icon: Wifi, label: 'Stations en ligne', value: '38 / 42', sub: '3 pays' },
  { icon: CreditCard, label: 'Cartes actives', value: '12 480', sub: 'dont 312 flottes' },
];

const FEED = [
  { icon: Fuel, tone: 'blue', t: 'Vente · 42,5 L Super', s: 'Agoè Assiyéyé · Pompe 3', v: '28 900 F' },
  { icon: Wallet, tone: 'green', t: 'Recharge carte ···2847', s: 'Siège Lomé', v: '50 000 F' },
  { icon: ShieldCheck, tone: 'orange', t: 'Montant validé par le DAF', s: 'Translom SARL', v: '2,5 M F' },
];

const ROLES = ['Admin', 'Gérant', 'Chef de poste', 'Pompiste', 'Comptable', 'DAF'];

export default function Login() {
  const navigate = useNavigate();
  const [show, setShow] = useState(false);
  const [remember, setRemember] = useState(true);
  const [email, setEmail] = useState('ama.kpodar@zener.tg');
  const [pwd, setPwd] = useState('zener-station');

  const submit = (e) => {
    e.preventDefault();
    navigate('/');
  };

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
            {STATS.map(({ icon: Icon, label, value, sub }) => (
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
            {FEED.map((f) => (
              <div key={f.t} className="list-item" style={{ padding: '11px 0' }}>
                <IcoBox icon={f.icon} tone={f.tone} size={30} />
                <div style={{ flex: 1 }}>
                  <div className="strong" style={{ fontSize: 13.5 }}>{f.t}</div>
                  <div className="muted" style={{ fontSize: 12.5 }}>{f.s}</div>
                </div>
                <div className="strong num">{f.v}</div>
              </div>
            ))}
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

          <button type="submit" className="btn btn-primary btn-block" style={{ height: 46, marginTop: 24, fontSize: 15 }}>
            Se connecter <ArrowRight size={17} />
          </button>

          <div className="divider-text" style={{ marginTop: 30 }}>Accès selon votre rôle</div>
          <div className="row" style={{ flexWrap: 'wrap', gap: 8, marginTop: 18 }}>
            {ROLES.map((r) => (
              <span key={r} className="badge gray" style={{ height: 28, padding: '0 10px' }}>{r}</span>
            ))}
          </div>

          <div className="row muted" style={{ marginTop: 34, fontSize: 13, gap: 8 }}>
            <ShieldCheck size={16} color="var(--green)" /> Connexion chiffrée · Licence active jusqu’au 31/12/2026
          </div>
        </form>
      </section>
    </div>
  );
}
