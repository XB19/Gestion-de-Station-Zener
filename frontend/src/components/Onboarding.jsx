import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KeyRound, Sparkles, ChevronRight, LogOut, ShieldCheck } from 'lucide-react';
import { Button, Card } from './ui.jsx';
import { Modal } from './feedback.jsx';
import ZenerLogo from './ZenerLogo.jsx';
import { api } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime } from '../lib/format.js';

/** Première connexion avec un mot de passe provisoire : changement obligatoire avant d'accéder à l'application. */
export function FirstLogin() {
  const { user, setProfile, logout } = useSession();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    if (next.length < 8) return setError('8 caractères minimum');
    if (next !== again) return setError('Les deux mots de passe ne correspondent pas');
    setBusy(true);
    try {
      setProfile(await api('/auth/password', { method: 'POST', body: { current, next } }));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg, #f4f6fa)', padding: 16 }}>
      <Card className="card-pad" style={{ width: 'min(440px, 100%)' }}>
        <ZenerLogo size={34} />
        <div className="row" style={{ gap: 10, marginTop: 18 }}>
          <KeyRound size={20} color="var(--primary)" />
          <div className="card-title">Choisissez votre mot de passe</div>
        </div>
        <div className="muted" style={{ fontSize: 13.5, marginTop: 6, lineHeight: 1.5 }}>
          Bienvenue {user?.firstName}. Votre compte a été créé avec un mot de passe provisoire : remplacez-le par un mot de passe que vous
          seul connaissez. Toutes vos actions sont enregistrées à votre nom.
        </div>
        <form onSubmit={submit} style={{ display: 'grid', gap: 12, marginTop: 16 }}>
          <label className="field-label">Mot de passe provisoire
            <span className="input" style={{ marginTop: 6 }}><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} required autoFocus /></span>
          </label>
          <label className="field-label">Nouveau mot de passe (8 caractères minimum)
            <span className="input" style={{ marginTop: 6 }}><input type="password" value={next} onChange={(e) => setNext(e.target.value)} required /></span>
          </label>
          <label className="field-label">Confirmation
            <span className="input" style={{ marginTop: 6 }}><input type="password" value={again} onChange={(e) => setAgain(e.target.value)} required /></span>
          </label>
          {error && <div className="alert-box" style={{ background: 'var(--red-50, #fdecec)', color: 'var(--red)' }}>{error}</div>}
          <div className="row between" style={{ marginTop: 4 }}>
            <Button type="button" icon={LogOut} onClick={logout}>Se déconnecter</Button>
            <Button variant="primary" type="submit" disabled={busy}>Enregistrer et continuer</Button>
          </div>
        </form>
      </Card>
    </div>
  );
}

const TOURS = {
  ADMIN: [
    ['Tableau de bord', 'Recette en direct, stations en ligne, validations et alertes du réseau.', '/'],
    ['Mise en route', 'Ce qui reste à configurer : entreprise, comptes clés, stations, prix, cartes…', '/mise-en-route'],
    ['Actions sensibles', 'Droits des rôles, paramètres financiers, ajustements de solde et comptes Admin / DAF passent par une seconde personne (écran Validations).', '/validations'],
    ['Journal et traces', "Chaque action garde son auteur, l'appareil et l'adresse d'où elle a été faite.", '/journal'],
  ],
  DAF: [
    ['Validations', 'Les recharges au-dessus du palier, les ouvertures de points de vente et les actions sensibles de l’Admin vous attendent ici.', '/validations'],
    ['Délégations', 'Pendant une absence, confiez votre pouvoir de validation à une personne ou à un rôle, pour une période et un montant maximal.', '/validations'],
    ['Contrôles', 'Recharges fractionnées, soldes qui dorment ou s’accumulent : à examiner et clore.', '/controles'],
  ],
  COMPTABLE: [
    ['Contrôles', 'Les activités suspectes sur les cartes sont signalées ici (aucun blocage automatique).', '/controles'],
    ['Rechargements', 'Historique des recharges et lots en masse.', '/rechargements'],
    ['Validations', 'Si le DAF vous délègue son pouvoir, vous validerez ici en son nom.', '/validations'],
  ],
  GERANT: [
    ['Votre station', 'Pistolets, cuves, équipe, mode autonome et mise en route de la station.', '/stations'],
    ['Ventes & stocks', 'Stocks, alertes et prix ; les cuves vous préviennent à 50, 40, 30 et 5 %.', '/ventes-stocks'],
    ['Atelier', 'Ordres de travail sur les véhicules : vidange, graissage, lavage… encaissés avec les produits utilisés.', '/atelier'],
    ['Rapports', 'Les rapports sont générés automatiquement : vous les commentez.', '/rapports'],
  ],
  CHEF_POSTE: [
    ['Mon poste', 'Prise de poste, ventes à la pompe, reste à remettre en fin de poste.', '/mon-poste'],
    ['Atelier', 'Ordres de travail sur les véhicules des clients.', '/atelier'],
  ],
  POMPISTE: [['Mon poste', 'Prenez votre poste, enregistrez les ventes et suivez ce qu’il vous reste à remettre.', '/mon-poste']],
  AGENT_PDV: [['Points de vente', 'Vente de cartes neuves (pièce d’identité) et de produits, même hors connexion.', '/points-de-vente']],
  LIVREUR: [['Zen Mobility', 'Vos livraisons du jour et le suivi de position.', '/mobilite']],
};

/** Visite guidée de la première connexion, adaptée au rôle. */
export function Tour() {
  const { user, setProfile } = useSession();
  const navigate = useNavigate();
  const steps = TOURS[user?.role] || [];
  const [i, setI] = useState(0);
  if (!user?.needsTour || user?.mustChangePassword) return null;
  const finish = async (link) => {
    try {
      setProfile(await api('/auth/onboarded', { method: 'POST' }));
    } catch {
      /* visite fermée même hors connexion */
    }
    if (link) navigate(link);
  };
  const step = steps[i];
  return (
    <Modal title={`Bienvenue, ${user.firstName}`} onClose={() => finish()} footer={
      <>
        <Button onClick={() => finish()}>Passer</Button>
        {step && i < steps.length - 1 ? (
          <Button variant="primary" iconRight={ChevronRight} onClick={() => setI(i + 1)}>Suivant</Button>
        ) : (
          <Button variant="primary" onClick={() => finish(step?.[2])}>{step ? `Ouvrir ${step[0]}` : 'Commencer'}</Button>
        )}
      </>
    }>
      <div className="row" style={{ gap: 10 }}>
        <Sparkles size={20} color="var(--primary)" />
        <span className="muted">Vous êtes connecté en tant que <b>{user.roleLabel}</b>{user.stationName ? ` · ${user.stationName}` : ''}.</span>
      </div>
      {step && (
        <div style={{ marginTop: 16, background: 'var(--surface-2)', borderRadius: 10, padding: '14px 16px' }}>
          <div className="muted" style={{ fontSize: 12 }}>Étape {i + 1} sur {steps.length}</div>
          <div className="strong" style={{ fontSize: 16, marginTop: 4 }}>{step[0]}</div>
          <div style={{ marginTop: 6, lineHeight: 1.5 }}>{step[1]}</div>
        </div>
      )}
      {user.lastLogin && (
        <div className="row muted" style={{ gap: 8, marginTop: 14, fontSize: 12.5 }}>
          <ShieldCheck size={14} /> Dernière connexion : {dateTime(user.lastLogin.at)} · {user.lastLogin.userAgent || 'appareil inconnu'} · {user.lastLogin.ip}
        </div>
      )}
    </Modal>
  );
}
