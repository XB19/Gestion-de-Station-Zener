import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ChevronRight, Lock, LockOpen, Pencil, Wallet, Fuel, Droplet, Flame, Clock, Package, KeyRound, ShieldCheck, Save,
} from 'lucide-react';
import {
  PageHeader, Card, Badge, StatusBadge, Button, Checkbox, Toggle, KV, BankCard, Progress, Async, Empty, Pager,
} from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, fcfa, hhmm, longDate, masked, money, shortName, signed } from '../lib/format.js';

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
const TYPE_TONE = { FLOTTE: 'blue', VIP: 'purple', CLASSIQUE: 'gray', EMPLOYE: 'green' };
const expiry = (d) => (d ? `${String(d).slice(5, 7)}/${String(d).slice(2, 4)}` : '—');

function operationLabel(o) {
  if (o.kind === 'REFUS') return `Refus · ${o.detail || ''}`;
  if (o.kind === 'RECHARGE') {
    const c = { DISTANCE: 'à distance', MASSE: 'en masse', UNITAIRE: 'au guichet' }[o.channel] || '';
    return `Recharge ${c}`.trim();
  }
  if (o.kind === 'AJUSTEMENT') return `Ajustement · ${o.detail || ''}`;
  return /^vente/i.test(o.detail || '') ? o.detail : `Vente ${o.detail || ''}`.trim();
}

function operationDetail(o) {
  if (o.kind === 'VENTE') {
    return [o.pumpCode, o.mode === 'AUTONOME' ? 'Autonome' : o.employeeName ? shortName(o.employeeName) : null].filter(Boolean).join(' · ') || '—';
  }
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
      <div className="grid" style={{ gridTemplateColumns: 'repeat(6, 1fr)', gap: 10 }}>
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
                  {can('CARTES_GERER') && <Button icon={Pencil} onClick={() => setModal('edit')}>Modifier</Button>}
                  {can('RECHARGER') && c.status === 'ACTIVE' && (
                    <Button variant="primary" icon={Wallet} onClick={() => navigate(`/rechargements?card=${c.number}`)}>Recharger</Button>
                  )}
                </>
              }
            />

            <div className="grid" style={{ gridTemplateColumns: '350px minmax(0, 1fr)', alignItems: 'start' }}>
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
              </div>

              <div className="stack">
                <Permissions card={c} onSaved={card.reload} editable={can('CARTES_GERER')} />

                <Card>
                  <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
                    <div className="card-title">Opérations</div>
                    <span className="muted" style={{ fontSize: 13 }}>Ventes, recharges et refus</span>
                  </div>
                  <Async state={ops}>
                    {(data) => (data.items.length === 0 ? <Empty>Aucune opération</Empty> : (
                      <>
                        <table className="table">
                          <thead>
                            <tr><th>Date</th><th>Opération</th><th>Station</th><th className="r">Montant (F)</th><th className="r">Solde</th><th>Détail</th></tr>
                          </thead>
                          <tbody>
                            {data.items.map((o, i) => (
                              <tr key={`${o.at}-${i}`}>
                                <td className="muted num">{dateTime(o.at)}</td>
                                <td className="strong" style={{ color: o.kind === 'REFUS' ? 'var(--red)' : undefined }}>{operationLabel(o)}</td>
                                <td>{o.stationName || (o.kind === 'RECHARGE' ? 'À distance' : '—')}</td>
                                <td className="r num strong" style={{ color: o.amount > 0 ? 'var(--green)' : o.kind === 'REFUS' ? 'var(--red)' : undefined }}>
                                  {signed(o.amount)}
                                </td>
                                <td className="r num muted">{o.balanceAfter != null ? fcfa(o.balanceAfter) : '—'}</td>
                                <td className="muted">{operationDetail(o)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
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
          </>
        );
      }}
    </Async>
  );
}
