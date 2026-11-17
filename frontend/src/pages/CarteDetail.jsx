import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ChevronRight, Lock, LockOpen, Pencil, Wallet, Fuel, Droplet, Flame, Clock, Package, KeyRound, ShieldCheck, Save,
  ArrowLeftRight, RefreshCw,
} from 'lucide-react';
import {
  PageHeader, Card, Badge, StatusBadge, Button, Checkbox, Toggle, KV, BankCard, Progress, Async, Empty, Pager,
} from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import CardPicker from '../components/CardPicker.jsx';
import { dateTime, fcfa, hhmm, longDate, masked, money, signed } from '../lib/format.js';

const PRODUCTS = [
  { code: 'SUPER', name: 'Super', icon: Fuel, color: 'var(--primary)' },
  { code: 'GASOIL', name: 'Gasoil', icon: Fuel, color: 'var(--green)' },
  { code: 'PETROLE', name: 'Pétrole', icon: Fuel, color: 'var(--orange)' },
  { code: 'LUBRIFIANT', name: 'Lubrifiants', icon: Droplet, color: 'var(--primary)' },
  { code: 'GAZ', name: 'Gaz', icon: Flame, color: 'var(--orange)' },
  { code: 'ACCESSOIRE', name: 'Accessoires', icon: Package, color: 'var(--muted)' },
];
const DAYS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];
const TECHNO = { PUCE: 'Carte à puce', M1: 'Carte M1' };
const TYPE_TONE = { FLOTTE: 'blue', CLUB: 'navy', CLASSIQUE: 'gray', EMPLOYE: 'green' };
const CLUB_TONE = { ACTIF: 'green', GRACE: 'orange', EXPIRE: 'red' };
const MEMBERSHIP = { ADHESION: 'Adhésion', RENOUVELLEMENT_AUTO: 'Renouvellement d’office', RENOUVELLEMENT_PAYE: 'Renouvellement payé' };
const REPLACE_REASON = { PERTE: 'Perte', VOL: 'Vol', DETERIORATION: 'Carte abîmée', AUTRE: 'Autre motif' };
const expiry = (d) => (d ? `${String(d).slice(5, 7)}/${String(d).slice(2, 4)}` : '—');

function operationLabel(o) {
  if (o.kind === 'REFUS') return `Refus · ${o.detail || ''}`;
  if (o.kind === 'EVENEMENT') return o.detail;
  if (o.kind === 'RECHARGE') {
    const c = { DISTANCE: 'à distance', MASSE: 'en masse', UNITAIRE: 'au guichet' }[o.channel] || '';
    return `Recharge ${c}`.trim();
  }
  if (o.kind === 'AJUSTEMENT') return `Ajustement · ${o.detail || ''}`;
  if (['TRANSFERT', 'FRAIS'].includes(o.kind)) return o.detail;
  return /^vente/i.test(o.detail || '') ? o.detail : `Vente ${o.detail || ''}`.trim();
}

function operationDetail(o) {
  if (o.kind === 'VENTE') return [o.pumpCode, o.mode === 'AUTONOME' ? 'Autonome' : null].filter(Boolean).join(' · ') || '—';
  if (o.kind === 'RECHARGE') return [o.rechargeRef, o.validationStatus === 'APPROUVEE' ? 'validée' : null].filter(Boolean).join(' · ') || '—';
  return '—';
}

function EditCardModal({ card, onClose, onSaved }) {
  const notify = useToast();
  const groups = useApi(card.holderId ? `/card-groups?holderId=${card.holderId}` : null);
  const [f, setF] = useState({
    groupId: card.groupId || '', vehiclePlate: card.vehiclePlate || '', vehicleLabel: card.vehicleLabel || '',
    driverName: card.driverName || '', expiresOn: card.expiresOn || '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Modifier la carte ${masked(card.number)}`} onClose={onClose} onSubmit={async () => {
      await put(`/cards/${card.id}`, {
        groupId: f.groupId ? Number(f.groupId) : null, vehiclePlate: f.vehiclePlate || null, vehicleLabel: f.vehicleLabel || null,
        driverName: f.driverName || null, expiresOn: f.expiresOn || null,
      });
      notify('Carte modifiée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Groupe" full>
          <SelectInput value={f.groupId} onChange={set('groupId')} placeholder="Aucun groupe"
            options={(groups.data || []).map((g) => ({ value: g.id, label: g.name }))} />
        </Field>
        <Field label="Immatriculation"><TextInput value={f.vehiclePlate} onChange={set('vehiclePlate')} /></Field>
        <Field label="Véhicule"><TextInput value={f.vehicleLabel} onChange={set('vehicleLabel')} /></Field>
        <Field label="Chauffeur"><TextInput value={f.driverName} onChange={set('driverName')} /></Field>
        <Field label="Expiration"><TextInput type="date" value={f.expiresOn} onChange={set('expiresOn')} /></Field>
      </div>
    </FormModal>
  );
}

function BlockModal({ card, onClose, onSaved }) {
  const notify = useToast();
  const [reason, setReason] = useState('Perte déclarée par le titulaire');
  return (
    <FormModal title={`Bloquer la carte ${masked(card.number)}`} danger submitLabel="Bloquer" onClose={onClose} onSubmit={async () => {
      await post(`/cards/${card.id}/block`, { reason });
      notify('Carte bloquée');
      onSaved();
    }}>
      <Field label="Motif">
        <SelectInput value={reason} onChange={setReason} options={['Perte déclarée par le titulaire', 'Vol déclaré', 'Plafond mensuel atteint',
          'Suspicion de fraude', 'Demande de l’entreprise']} />
      </Field>
      <div className="muted" style={{ marginTop: 12, fontSize: 13 }}>Plus aucun paiement ne sera accepté, dans aucune station.</div>
    </FormModal>
  );
}

function PinModal({ card, onClose }) {
  const notify = useToast();
  const [pin, setPin] = useState('');
  return (
    <FormModal title="Nouveau code PIN" onClose={onClose} onSubmit={async () => {
      await post(`/cards/${card.id}/pin`, { pin });
      notify('Code PIN modifié');
    }}>
      <Field label="Code PIN (4 à 6 chiffres)" hint="Obligatoire pour payer en mode autonome">
        <TextInput value={pin} onChange={setPin} type="password" inputMode="numeric" pattern="[0-9]{4,6}" required autoFocus />
      </Field>
    </FormModal>
  );
}

function TransferModal({ card, onClose, onSaved }) {
  const notify = useToast();
  const [to, setTo] = useState(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  return (
    <FormModal title={`Transférer depuis la carte ${masked(card.number)}`} submitLabel="Transférer" onClose={onClose} onSubmit={async () => {
      if (!to) throw new Error('Choisissez la carte qui reçoit le montant');
      await post(`/cards/${card.id}/transfer`, { toCardId: to.id, amount: Number(amount), note: note || null });
      notify(`${fcfa(amount)} F transférés vers ${masked(to.number)}`);
      onSaved();
    }}>
      <Field label="Carte qui reçoit" hint="Une autre carte du même titulaire (ou de la même entreprise)">
        <CardPicker value={to} onChange={setTo} autoFocus />
      </Field>
      <div className="form-grid" style={{ marginTop: 12 }}>
        <Field label="Montant (F)" hint={`Disponible : ${fcfa(card.balance)} F`}>
          <TextInput type="number" min="1" max={card.balance} value={amount} onChange={setAmount} required />
        </Field>
        <Field label="Motif"><TextInput value={note} onChange={setNote} placeholder="Facultatif" /></Field>
      </div>
    </FormModal>
  );
}

function ReplaceModal({ card, onClose, onDone }) {
  const notify = useToast();
  const [f, setF] = useState({ reason: 'PERTE', newNumber: '', note: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={`Remplacer la carte ${masked(card.number)}`} submitLabel="Émettre la nouvelle carte" onClose={onClose} onSubmit={async () => {
      const r = await post(`/cards/${card.id}/replace`, { reason: f.reason, newNumber: f.newNumber || null, note: f.note || null });
      notify(`Nouvelle carte ${masked(r.newNumber)} émise (à activer) · ${fcfa(r.balanceTransferred)} F reportés`);
      onDone(r);
    }}>
      <div className="form-grid">
        <Field label="Motif">
          <SelectInput value={f.reason} onChange={set('reason')} options={Object.entries(REPLACE_REASON).map(([value, label]) => ({ value, label }))} />
        </Field>
        <Field label="Numéro de la nouvelle carte" hint="Vide : prochain numéro libre">
          <TextInput value={f.newNumber} onChange={set('newNumber')} inputMode="numeric" />
        </Field>
        <Field label="Remarque" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
      <div className="muted" style={{ marginTop: 12, fontSize: 13, lineHeight: 1.5 }}>
        La carte actuelle est bloquée (et mise en liste noire en cas de perte ou de vol). Le solde, les permissions, le véhicule,
        le chauffeur et le PIN passent sur la nouvelle carte, moins les frais de remplacement. La nouvelle carte doit être activée par une autre personne.
      </div>
    </FormModal>
  );
}

/** Adhésion Club : échéance, consommation sur 12 mois (renouvellement d'office), renouvellement payé, historique. */
function ClubCard({ card, can, onSaved }) {
  const notify = useToast();
  const [renewing, setRenewing] = useState(false);
  const [joining, setJoining] = useState(false);
  const c = card.club;
  if (!c || !c.status) return null;
  const pct = Math.min(100, Math.round((c.spent12m / c.autoRenewSpend) * 100));
  return (
    <Card className="card-pad" style={{ background: '#16181d', color: '#f4f4f5' }}>
      <div className="row between">
        <span className="card-title" style={{ color: '#fff' }}>Carte Club</span>
        <Badge tone={CLUB_TONE[c.status]}>{c.statusLabel}</Badge>
      </div>
      {c.until ? (
        <div style={{ marginTop: 10, fontSize: 13.5, lineHeight: 1.6 }}>
          Adhésion jusqu'au <b>{longDate(c.until)}</b>{c.status !== 'ACTIF' && <> · grâce jusqu'au {longDate(c.graceUntil)}</>}
          <div style={{ marginTop: 10, opacity: 0.85 }}>Consommation sur 12 mois : <b>{fcfa(c.spent12m)} F</b> / {fcfa(c.autoRenewSpend)} F pour un renouvellement d'office</div>
          <div style={{ marginTop: 6 }}><Progress value={pct} color={c.autoRenewReached ? 'var(--green-500)' : '#c9a227'} /></div>
          <div style={{ marginTop: 6, opacity: 0.75, fontSize: 12.5 }}>
            {c.autoRenewReached ? 'Renouvellement d’office à l’échéance.' : `Sinon renouvellement payé : ${fcfa(c.renewalFee)} F.`} Avantages : prix Club, plafonds plus élevés.
          </div>
        </div>
      ) : <div style={{ marginTop: 10, opacity: 0.85, fontSize: 13.5 }}>Pas encore d'adhésion Club (dépôt initial à créditer).</div>}
      {can('CARTES_GERER', 'RECHARGER') && card.status === 'ACTIVE' && (
        <div className="row" style={{ gap: 8, marginTop: 12 }}>
          {c.until ? <Button size="sm" icon={RefreshCw} onClick={() => setRenewing(true)}>Renouveler ({fcfa(c.renewalFee)} F)</Button>
            : <Button size="sm" onClick={() => setJoining(true)}>Ouvrir l'adhésion</Button>}
        </div>
      )}
      {(c.memberships || []).length > 0 && (
        <div style={{ marginTop: 12, borderTop: '1px solid #2c2f36', paddingTop: 8, fontSize: 12.5, opacity: 0.85 }}>
          {c.memberships.map((m, i) => (
            <div key={i}>{MEMBERSHIP[m.kind]} · {longDate(m.periodStart)} → {longDate(m.periodEnd)}{m.fee ? ` · ${fcfa(m.fee)} F` : ''}{m.deposit ? ` · dépôt ${fcfa(m.deposit)} F` : ''}</div>
          ))}
        </div>
      )}
      {renewing && (
        <FormModal title="Renouveler la carte Club" submitLabel={`Encaisser ${fcfa(c.renewalFee)} F`} onClose={() => setRenewing(false)} onSubmit={async () => {
          await post(`/cards/${card.id}/club/renew`, { paymentMethod: document.getElementById('club-pm').value });
          notify('Adhésion Club renouvelée pour 12 mois');
          onSaved();
        }}>
          <Field label="Paiement"><label className="input"><select id="club-pm" defaultValue="ESPECES">
            <option value="ESPECES">Espèces</option><option value="MOBILE_MONEY">Mobile money</option><option value="VIREMENT">Virement</option>
          </select></label></Field>
        </FormModal>
      )}
      {joining && (
        <FormModal title="Adhésion Club" submitLabel="Créditer et ouvrir l'adhésion" onClose={() => setJoining(false)} onSubmit={async () => {
          await post(`/cards/${card.id}/club/join`, { deposit: Number(document.getElementById('club-dep').value), paymentMethod: 'ESPECES' });
          notify('Adhésion Club ouverte');
          onSaved();
        }}>
          <Field label="Dépôt initial (F)" hint="200 000 F minimum, crédité sur la carte"><label className="input"><input id="club-dep" type="number" defaultValue="200000" min="1" /></label></Field>
        </FormModal>
      )}
    </Card>
  );
}

/** Ajustement manuel du solde : appliqué après l'approbation d'une seconde personne (Admin ou DAF). */
function AdjustModal({ card, onClose }) {
  const notify = useToast();
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  return (
    <FormModal title={`Ajuster le solde · ${masked(card.number)}`} submitLabel="Demander l'ajustement" onClose={onClose} onSubmit={async () => {
      const r = await post(`/cards/${card.id}/adjust`, { amount: Number(amount), reason });
      notify(r.message, 'info');
    }}>
      <div className="form-grid">
        <Field label="Montant (F, négatif pour retirer)" hint={`Solde actuel : ${fcfa(card.balance)} F`}><TextInput type="number" value={amount} onChange={setAmount} required /></Field>
        <Field label="Motif"><TextInput value={reason} onChange={setReason} required placeholder="Geste commercial, erreur de saisie…" /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>Action sensible : une seconde personne (Admin ou DAF) doit l'approuver dans Validations.</div>
    </FormModal>
  );
}

/** Remplacement : lien vers la nouvelle carte (ou l'ancienne). */
function ReplacementNote({ card }) {
  const r = card.replacement;
  if (!r) return null;
  return (
    <Card className="card-pad" style={{ fontSize: 13.5 }}>
      <div className="row" style={{ gap: 8 }}>
        <RefreshCw size={16} color="var(--primary)" />
        {r.oldCardId === card.id ? (
          <span>Remplacée le {longDate(r.at)} ({REPLACE_REASON[r.reason]?.toLowerCase()}) par <Link to={`/cartes/${r.newCardId}`} className="strong num">{masked(r.newNumber)}</Link></span>
        ) : (
          <span>Remplace <Link to={`/cartes/${r.oldCardId}`} className="strong num">{masked(r.oldNumber)}</Link> depuis le {longDate(r.at)} ({REPLACE_REASON[r.reason]?.toLowerCase()})</span>
        )}
      </div>
    </Card>
  );
}

/** Éditeur des permissions de la carte (produits, plafonds, jours, horaires, stations, mode autonome). */
function Permissions({ card, onSaved, editable }) {
  const notify = useToast();
  const stations = useApi('/stations');
  const init = () => {
    const p = card.permissions || {};
    return {
      products: p.products || PRODUCTS.map((x) => x.code),
      dailyLimit: p.dailyLimit ?? '',
      monthlyLimit: p.monthlyLimit ?? '',
      perTxLimit: p.perTxLimit ?? '',
      days: p.days || [1, 2, 3, 4, 5, 6, 7],
      timeFrom: p.timeFrom ? hhmm(p.timeFrom) : '',
      timeTo: p.timeTo ? hhmm(p.timeTo) : '',
      stationIds: p.stationIds || null,
      autonomousAllowed: p.autonomousAllowed ?? true,
      dailyVolumeLimit: p.dailyVolumeLimit ?? '',
      monthlyVolumeLimit: p.monthlyVolumeLimit ?? '',
      dailyFillsLimit: p.dailyFillsLimit ?? '',
      minBalance: p.minBalance ?? '',
    };
  };
  const [f, setF] = useState(init);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!dirty) setF(init());
  }, [card]); // eslint-disable-line react-hooks/exhaustive-deps
  const change = (patch) => {
    if (!editable) return;
    setF((x) => ({ ...x, ...patch }));
    setDirty(true);
  };
  const usage = card.usage || {};
  const limits = [
    ['dailyLimit', 'Plafond journalier', usage.spentToday, 'var(--primary)'],
    ['monthlyLimit', 'Plafond mensuel', usage.spentMonth, 'var(--green-500)'],
    ['perTxLimit', 'Plafond par transaction', null, 'var(--primary)'],
  ];
  const save = async () => {
    setBusy(true);
    try {
      await put(`/cards/${card.id}/permissions`, {
        products: f.products,
        dailyLimit: f.dailyLimit === '' ? null : Number(f.dailyLimit),
        monthlyLimit: f.monthlyLimit === '' ? null : Number(f.monthlyLimit),
        perTxLimit: f.perTxLimit === '' ? null : Number(f.perTxLimit),
        days: f.days,
        timeFrom: f.timeFrom ? `${f.timeFrom}:00` : null,
        timeTo: f.timeTo ? `${f.timeTo}:00` : null,
        stationIds: f.stationIds,
        autonomousAllowed: f.autonomousAllowed,
        dailyVolumeLimit: f.dailyVolumeLimit === '' ? null : Number(f.dailyVolumeLimit),
        monthlyVolumeLimit: f.monthlyVolumeLimit === '' ? null : Number(f.monthlyVolumeLimit),
        dailyFillsLimit: f.dailyFillsLimit === '' ? null : Number(f.dailyFillsLimit),
        minBalance: f.minBalance === '' ? null : Number(f.minBalance),
      });
      notify('Permissions enregistrées · appliquées immédiatement dans toutes les stations');
      setDirty(false);
      onSaved();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="card-pad">
      <div className="row between">
        <div className="card-title">Permissions</div>
        <span className="row" style={{ gap: 10 }}>
          <span className="muted" style={{ fontSize: 13 }}>Appliquées par toutes les stations, y compris en mode autonome</span>
          {dirty && (
            <>
              <Button size="sm" onClick={() => { setF(init()); setDirty(false); }}>Annuler</Button>
              <Button size="sm" variant="primary" icon={Save} disabled={busy} onClick={save}>Enregistrer</Button>
            </>
          )}
        </span>
      </div>

      <div className="section-label" style={{ marginTop: 16 }}>Produits autorisés</div>
      <div className="grid auto-cols" style={{ gridTemplateColumns: 'repeat(6, 1fr)', gap: 10, '--col': '118px' }}>
        {PRODUCTS.map((p) => {
          const Icon = p.icon;
          const on = f.products.includes(p.code);
          return (
            <button
              key={p.code}
              type="button"
              onClick={() => change({ products: on ? f.products.filter((x) => x !== p.code) : [...f.products, p.code] })}
              className="row"
              style={{
                gap: 8, height: 44, padding: '0 10px', borderRadius: 9, background: '#fff', fontWeight: 600, fontSize: 13,
                border: on ? '1.5px solid var(--primary)' : '1px solid var(--border)', cursor: editable ? 'pointer' : 'default',
              }}
            >
              <Checkbox checked={on} />
              <Icon size={16} color={p.color} strokeWidth={1.8} />
              {p.name}
            </button>
          );
        })}
      </div>

      <div className="grid g3" style={{ gap: 14, marginTop: 18 }}>
        {limits.map(([key, label, used, color]) => {
          const limit = f[key] === '' ? null : Number(f[key]);
          const ratio = limit && used != null ? Math.min(100, Math.round((used / limit) * 100)) : 0;
          return (
            <div key={key} style={{ border: '1px solid var(--border)', borderRadius: 10, padding: '16px 18px' }}>
              <div className="row between">
                <span className="strong">{label}</span>
                <Pencil size={15} color="var(--muted-2)" />
              </div>
              <label className="input" style={{ marginTop: 10, height: 40 }}>
                <input className="num" style={{ fontSize: 18, fontWeight: 700 }} type="number" min="0" placeholder="Sans plafond"
                  value={f[key]} disabled={!editable} onChange={(e) => change({ [key]: e.target.value })} />
                <span className="muted" style={{ fontSize: 12.5 }}>FCFA</span>
              </label>
              <div className="row" style={{ gap: 12, marginTop: 14 }}>
                <Progress value={ratio} color={ratio > 85 ? 'var(--red-500)' : color} />
                <span className="muted num" style={{ fontSize: 12.5 }}>{limit ? `${ratio} %` : '—'}</span>
              </div>
              <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>
                {used != null ? `${fcfa(used)} F utilisés` : 'Contrôlé à chaque paiement'}
              </div>
            </div>
          );
        })}
      </div>

      <div className="grid g4" style={{ gap: 12, marginTop: 14 }}>
        {[
          ['dailyVolumeLimit', 'Litres par jour', 'L'],
          ['monthlyVolumeLimit', 'Litres par mois', 'L'],
          ['dailyFillsLimit', 'Pleins par jour', 'pleins'],
          ['minBalance', 'Solde à garder', 'FCFA'],
        ].map(([key, label, unit]) => (
          <div key={key}>
            <div className="muted" style={{ fontSize: 12.5, marginBottom: 6 }}>{label}</div>
            <label className="input" style={{ height: 38 }}>
              <input className="num" type="number" min="0" placeholder="Sans limite" value={f[key]} disabled={!editable}
                onChange={(e) => change({ [key]: e.target.value })} />
              <span className="muted" style={{ fontSize: 12 }}>{unit}</span>
            </label>
          </div>
        ))}
      </div>

      <div className="row" style={{ gap: 28, marginTop: 20, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div>
          <div className="section-label">Jours autorisés</div>
          <div className="row" style={{ gap: 8 }}>
            {DAYS.map((d, i) => {
              const on = f.days.includes(i + 1);
              return (
                <button
                  key={i}
                  type="button"
                  onClick={() => change({ days: on ? f.days.filter((x) => x !== i + 1) : [...f.days, i + 1].sort() })}
                  style={{
                    width: 38, height: 34, borderRadius: 7, border: 0, fontWeight: 600,
                    background: on ? 'var(--primary)' : 'var(--chip)', color: on ? '#fff' : 'var(--muted-2)',
                  }}
                >
                  {d}
                </button>
              );
            })}
          </div>
        </div>
        <div>
          <div className="section-label">Horaires</div>
          <div className="row" style={{ gap: 10 }}>
            <label className="input num" style={{ width: 130 }}>
              <Clock size={16} color="var(--muted-2)" />
              <input type="time" value={f.timeFrom} disabled={!editable} onChange={(e) => change({ timeFrom: e.target.value })} />
            </label>
            <span className="muted">à</span>
            <label className="input num" style={{ width: 130 }}>
              <Clock size={16} color="var(--muted-2)" />
              <input type="time" value={f.timeTo} disabled={!editable} onChange={(e) => change({ timeTo: e.target.value })} />
            </label>
          </div>
          <div className="muted" style={{ fontSize: 12.5, marginTop: 6 }}>Vide : à toute heure</div>
        </div>
        <div style={{ marginLeft: 'auto' }}>
          <div className="section-label">Stations</div>
          <label className="select" style={{ height: 40 }}>
            <Fuel size={16} />
            <select
              value={f.stationIds === null ? '' : 'SEL'}
              disabled={!editable}
              onChange={(e) => change({ stationIds: e.target.value === '' ? null : f.stationIds || [] })}
            >
              <option value="">Toutes ({stations.data?.length ?? '…'})</option>
              <option value="SEL">Stations choisies ({f.stationIds?.length ?? 0})</option>
            </select>
          </label>
        </div>
      </div>
      {f.stationIds !== null && (
        <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          {(stations.data || []).map((s) => {
            const on = f.stationIds.includes(s.id);
            return (
              <button key={s.id} type="button" className={`badge ${on ? 'blue' : 'gray'}`} style={{ border: 0, height: 28, cursor: 'pointer' }}
                onClick={() => change({ stationIds: on ? f.stationIds.filter((x) => x !== s.id) : [...f.stationIds, s.id] })}>
                {s.shortName}
              </button>
            );
          })}
        </div>
      )}

      <div className="grid g2" style={{ gap: 14, marginTop: 20 }}>
        <div className="row" style={{ gap: 14, background: 'var(--surface-2)', borderRadius: 10, padding: '16px 18px' }}>
          <Toggle on={f.autonomousAllowed} onChange={(v) => change({ autonomousAllowed: v })} disabled={!editable} />
          <div>
            <div className="strong">Utilisable en mode autonome</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>La carte peut servir sans pompiste, dans les limites ci-dessus (PIN obligatoire)</div>
          </div>
        </div>
        <div className="row" style={{ gap: 14, background: 'var(--surface-2)', borderRadius: 10, padding: '16px 18px' }}>
          <ShieldCheck size={22} color="var(--primary)" />
          <div>
            <div className="strong">Validation des recharges élevées</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
              Au-dessus de {fcfa(card.validation?.threshold)} F : {card.validation?.approvers}
            </div>
          </div>
        </div>
      </div>
    </Card>
  );
}

export default function CarteDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const notify = useToast();
  const { can } = useSession();
  const card = useApi(`/cards/${id}`);
  const [page, setPage] = useState(0);
  const ops = useApi(`/cards/${id}/operations?page=${page}&size=10`);
  const [modal, setModal] = useState(null);
  const reload = () => {
    card.reload();
    ops.reload();
  };
  useLiveReload(reload, ['operation'], 4000);

  const act = async (path, message) => {
    try {
      await post(`/cards/${id}/${path}`);
      notify(message);
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Async state={card} minHeight={400}>
      {(c) => {
        const blocked = c.status === 'BLOQUEE';
        return (
          <>
            <PageHeader
              breadcrumb={
                <div className="breadcrumb">
                  <Link to="/cartes">Cartes</Link> <ChevronRight size={14} /> <Link to="/cartes">{c.typeCode === 'FLOTTE' ? 'Cartes flottes' : `Cartes ${c.typeLabel.toLowerCase()}`}</Link>
                  <ChevronRight size={14} /> <span className="cur num">{c.numberFormatted}</span>
                </div>
              }
              title={`Carte ${masked(c.number)} · ${c.holderName || 'sans titulaire'}`}
              tags={
                <>
                  <StatusBadge status={c.statusLabel} />
                  <Badge tone={TYPE_TONE[c.typeCode]}>{c.typeLabel}</Badge>
                  <Badge tone="gray">{TECHNO[c.techno] || c.techno}</Badge>
                  {c.groupName && <Badge tone="blue" style={{ background: 'var(--primary-50)' }}>Groupe : {c.groupName}</Badge>}
                  {blocked && c.blockedReason && <Badge tone="red">{c.blockedReason}</Badge>}
                </>
              }
              actions={
                <>
                  {c.status === 'A_ACTIVER' && can('CARTES_ACTIVER') && (
                    <Button variant="success" icon={ShieldCheck} onClick={() => act('activate', 'Carte activée')}>Activer</Button>
                  )}
                  {can('CARTES_GERER') && !blocked && ['ACTIVE', 'A_ACTIVER'].includes(c.status) && (
                    <Button variant="danger" icon={Lock} onClick={() => setModal('block')}>Bloquer</Button>
                  )}
                  {can('CARTES_GERER') && blocked && <Button icon={LockOpen} onClick={() => act('unblock', 'Carte débloquée')}>Débloquer</Button>}
                  {can('CARTES_GERER', 'RECHARGER') && <Button icon={KeyRound} onClick={() => setModal('pin')}>Code PIN</Button>}
                  {can('RECHARGER', 'CARTES_GERER', 'FLOTTES') && c.status === 'ACTIVE' && c.balance > 0 && (
                    <Button icon={ArrowLeftRight} onClick={() => setModal('transfer')}>Transférer</Button>
                  )}
                  {can('CARTES_GERER') && c.holderId && ['ACTIVE', 'BLOQUEE', 'A_ACTIVER'].includes(c.status) && !(c.replacement && c.replacement.oldCardId === c.id) && (
                    <Button icon={RefreshCw} onClick={() => setModal('replace')}>Remplacer</Button>
                  )}
                  {can('CARTES_GERER') && ['ACTIVE', 'BLOQUEE'].includes(c.status) && (
                    <Button icon={Wallet} onClick={() => setModal('adjust')}>Ajuster le solde</Button>
                  )}
                  {can('CARTES_GERER') && <Button icon={Pencil} onClick={() => setModal('edit')}>Modifier</Button>}
                  {can('RECHARGER') && c.status === 'ACTIVE' && (
                    <Button variant="primary" icon={Wallet} onClick={() => navigate(`/rechargements?card=${c.number}`)}>Recharger</Button>
                  )}
                </>
              }
            />

            <div className="grid split" style={{ gridTemplateColumns: '350px minmax(0, 1fr)', alignItems: 'start' }}>
              <div className="stack">
                <BankCard number={c.numberFormatted} holder={(c.holderName || '').toUpperCase()} exp={expiry(c.expiresOn)} kind={c.typeLabel.toUpperCase()} large />

                <Card className="card-pad">
                  <div className="muted">Solde disponible</div>
                  <div className="num" style={{ fontSize: 30, fontWeight: 700, margin: '12px 0 18px', letterSpacing: '-0.02em' }}>
                    {fcfa(c.balance)} <small className="muted" style={{ fontSize: 14, fontWeight: 600 }}>FCFA</small>
                  </div>
                  <div className="grid g2" style={{ gap: 12 }}>
                    {[['Rechargé ce mois', money(c.usage?.rechargedMonth)], ['Consommé ce mois', money(c.usage?.spentMonth)]].map(([l, v]) => (
                      <div key={l} style={{ background: 'var(--surface-2)', borderRadius: 9, padding: '12px 14px' }}>
                        <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                        <div className="strong num" style={{ marginTop: 4 }}>{v}</div>
                      </div>
                    ))}
                  </div>
                </Card>

                <Card className="card-pad">
                  <div className="row between">
                    <div className="card-title">Informations</div>
                    {can('CARTES_GERER') && <button className="link-more" onClick={() => setModal('edit')}><Pencil size={16} /></button>}
                  </div>
                  <div style={{ marginTop: 8 }}>
                    <KV k={c.holderKind === 'ENTREPRISE' ? 'Entreprise' : 'Titulaire'} v={c.holderName || '—'} />
                    {c.idDocNumber && <KV k={c.idDocType || 'Pièce'} v={c.idDocNumber} />}
                    {c.holderPhone && <KV k="Téléphone" v={c.holderPhone} />}
                    {(c.vehiclePlate || c.vehicleLabel) && <KV k="Véhicule" v={[c.vehiclePlate, c.vehicleLabel].filter(Boolean).join(' · ')} />}
                    {c.driverName && <KV k="Chauffeur" v={c.driverName} />}
                    <KV k="Émise par" v={[c.issuedByName, c.issuedAtPlace].filter(Boolean).join(' · ') || '—'} />
                    <KV k="Activée" v={c.activatedAt ? `${longDate(c.activatedAt)} · ${c.activatedByName || ''}` : 'Pas encore'} />
                    <KV k="Expiration" v={longDate(c.expiresOn)} />
                    <KV k="Code PIN" v={c.hasPin ? 'Défini' : 'Non défini'} />
                  </div>
                </Card>

                <ClubCard card={c} can={can} onSaved={reload} />
                <ReplacementNote card={c} />
              </div>

              <div className="stack">
                <Permissions card={c} onSaved={card.reload} editable={can('CARTES_GERER')} />

                <Card>
                  <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
                    <div className="card-title">Opérations</div>
                    <span className="muted" style={{ fontSize: 13 }}>Ventes, recharges, refus et opérations sur la carte, avec l'opérateur</span>
                  </div>
                  <Async state={ops}>
                    {(data) => (data.items.length === 0 ? <Empty>Aucune opération</Empty> : (
                      <>
                        <div className="table-wrap"><table className="table">
                          <thead>
                            <tr><th>Date</th><th>Opération</th><th>Station</th><th className="r">Montant (F)</th><th className="r">Solde</th><th>Opérateur</th><th>Détail</th></tr>
                          </thead>
                          <tbody>
                            {data.items.map((o, i) => (
                              <tr key={`${o.at}-${i}`}>
                                <td className="muted num">{dateTime(o.at)}</td>
                                <td className="strong" style={{ color: o.kind === 'REFUS' ? 'var(--red)' : o.kind === 'EVENEMENT' ? 'var(--navy-700)' : undefined }}>{operationLabel(o)}</td>
                                <td>{o.stationName || (o.kind === 'RECHARGE' ? 'À distance' : '—')}</td>
                                <td className="r num strong" style={{ color: o.amount > 0 ? 'var(--green)' : o.kind === 'REFUS' ? 'var(--red)' : undefined }}>
                                  {o.kind === 'EVENEMENT' ? '' : signed(o.amount)}
                                </td>
                                <td className="r num muted">{o.balanceAfter != null ? fcfa(o.balanceAfter) : '—'}</td>
                                <td>{o.employeeName || (o.mode === 'AUTONOME' ? 'Libre-service' : '—')}</td>
                                <td className="muted">{operationDetail(o)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table></div>
                        <Pager data={data} page={page} onPage={setPage} label="opérations" />
                      </>
                    ))}
                  </Async>
                </Card>
              </div>
            </div>

            {modal === 'edit' && <EditCardModal card={c} onClose={() => setModal(null)} onSaved={reload} />}
            {modal === 'block' && <BlockModal card={c} onClose={() => setModal(null)} onSaved={reload} />}
            {modal === 'pin' && <PinModal card={c} onClose={() => setModal(null)} />}
            {modal === 'transfer' && <TransferModal card={c} onClose={() => setModal(null)} onSaved={reload} />}
            {modal === 'adjust' && <AdjustModal card={c} onClose={() => setModal(null)} />}
            {modal === 'replace' && <ReplaceModal card={c} onClose={() => setModal(null)} onDone={(r) => navigate(`/cartes/${r.newCardId}`)} />}
          </>
        );
      }}
    </Async>
  );
}
