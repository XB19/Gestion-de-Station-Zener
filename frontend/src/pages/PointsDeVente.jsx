import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Plus, Store, CreditCard, HandCoins, Clock, CalendarDays, Users, CloudOff, Banknote, Smartphone, Printer, CircleCheck,
  PackagePlus, BadgePlus,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Avatar, IcoBox, Async, Empty } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useInterval, useLiveReload } from '../api/hooks.js';
import { fileUrl, post } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, avatarColor, dayMonth, fcfa, isoDay, money, num, pct } from '../lib/format.js';

function subtitle(p) {
  if (p.kind === 'PERMANENT') return `Permanent · ${p.address || ''}`;
  const period = `${dayMonth(p.startDate)} → ${dayMonth(p.endDate)}`;
  if (p.status === 'DEMANDE') return `Temporaire · Demande · ${period}`;
  return `Temporaire · ${period}${p.daysLeft != null && p.daysLeft >= 0 ? ` · J-${p.daysLeft}` : ''}`;
}

function PdvModal({ temporary, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ name: '', address: '', startDate: isoDay(), endDate: isoDay(new Date(Date.now() + 5 * 86400000)), cardsPlanned: 200, reason: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal
      title={temporary ? 'Demander l’ouverture d’un point de vente temporaire' : 'Nouveau point de vente permanent'}
      submitLabel={temporary ? 'Envoyer la demande' : 'Créer'}
      onClose={onClose}
      onSubmit={async () => {
        const body = { ...f, cardsPlanned: Number(f.cardsPlanned) || null };
        if (temporary) {
          await post('/pdv/requests', body);
          notify('Demande envoyée · à valider par l’Admin ou le DAF', 'info');
        } else {
          await post('/pdv', { name: f.name, address: f.address, cardsPlanned: body.cardsPlanned });
          notify('Point de vente créé');
        }
        onSaved();
      }}
    >
      <div className="form-grid">
        <Field label="Nom" full><TextInput value={f.name} onChange={set('name')} required placeholder={temporary ? 'Campagne Université de Lomé' : 'Agence de Kara'} /></Field>
        <Field label="Adresse" full><TextInput value={f.address} onChange={set('address')} /></Field>
        {temporary && (
          <>
            <Field label="Début"><TextInput type="date" value={f.startDate} onChange={set('startDate')} required /></Field>
            <Field label="Fin"><TextInput type="date" value={f.endDate} onChange={set('endDate')} required /></Field>
          </>
        )}
        <Field label="Cartes prévues"><TextInput type="number" min="0" value={f.cardsPlanned} onChange={set('cardsPlanned')} /></Field>
        {temporary && <Field label="Motif" full><TextInput value={f.reason} onChange={set('reason')} placeholder="Événement, campagne…" /></Field>}
      </div>
    </FormModal>
  );
}

function AllocateModal({ pdv, onClose, onSaved }) {
  const notify = useToast();
  const [typeCode, setTypeCode] = useState('CLASSIQUE');
  const [quantity, setQuantity] = useState(50);
  return (
    <FormModal title={`Attribuer des cartes · ${pdv.name}`} submitLabel="Attribuer" onClose={onClose} onSubmit={async () => {
      await post(`/pdv/${pdv.id}/cards/allocate`, { typeCode, quantity: Number(quantity) });
      notify(`${quantity} cartes attribuées`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Type"><SelectInput value={typeCode} onChange={setTypeCode} options={[{ value: 'CLASSIQUE', label: 'Classique' }, { value: 'VIP', label: 'VIP' }]} /></Field>
        <Field label="Quantité"><TextInput type="number" min="1" max="2000" value={quantity} onChange={setQuantity} required /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>Les cartes vierges du stock sont attribuées dans l’ordre des numéros.</div>
    </FormModal>
  );
}

/** Vente d'une carte neuve sur pièce d'identité (espèces ou mobile money). */
function SellModal({ pdv, onClose, onSaved }) {
  const notify = useToast();
  const cards = useApi(`/pdv/${pdv.id}/cards`);
  const [f, setF] = useState({ cardNumber: '', name: '', idDocType: 'CNI', idDocNumber: '', phone: '', paymentMethod: 'ESPECES', paymentRef: '' });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  useEffect(() => {
    if (!f.cardNumber && cards.data?.length) setF((x) => ({ ...x, cardNumber: cards.data[0].number }));
  }, [cards.data]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <FormModal title={`Vendre une carte · ${pdv.name}`} submitLabel="Encaisser et remettre la carte" wide onClose={onClose} onSubmit={async () => {
      const r = await post(`/pdv/${pdv.id}/sales`, {
        uuid: crypto.randomUUID(), cardNumber: f.cardNumber, paymentMethod: f.paymentMethod, paymentRef: f.paymentRef || null,
        holder: { name: f.name, idDocType: f.idDocType, idDocNumber: f.idDocNumber, phone: f.phone || null },
      });
      notify(`Carte vendue · ${money(r.total)} · à activer par la comptabilité`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Carte" full>
          <SelectInput value={f.cardNumber} onChange={set('cardNumber')}
            options={(cards.data || []).map((c) => ({ value: c.number, label: `${c.numberFormatted || c.number} · ${c.typeLabel || c.typeCode}` }))} />
        </Field>
        <Field label="Nom de l’acheteur" full><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Pièce d’identité">
          <SelectInput value={f.idDocType} onChange={set('idDocType')} options={['CNI', 'Passeport', 'Permis', 'Carte d’électeur']} />
        </Field>
        <Field label="Numéro de la pièce"><TextInput value={f.idDocNumber} onChange={set('idDocNumber')} required /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Paiement">
          <SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={[{ value: 'ESPECES', label: 'Espèces' }, { value: 'MOBILE_MONEY', label: 'Mobile money' }]} />
        </Field>
        {f.paymentMethod === 'MOBILE_MONEY' && <Field label="Référence de la transaction" full><TextInput value={f.paymentRef} onChange={set('paymentRef')} required /></Field>}
      </div>
    </FormModal>
  );
}

function Closure({ pdv, onClosed }) {
  const notify = useToast();
  const { can } = useSession();
  const r = pdv.reconciliation;
  const [cash, setCash] = useState(String(r.expectedCash));
  const [mm, setMm] = useState(String(r.expectedMobileMoney));
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setCash(String(r.expectedCash));
    setMm(String(r.expectedMobileMoney));
  }, [pdv.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const closed = pdv.closure;
  const counted = closed ? closed.countedCash + closed.countedMm : Number(cash || 0) + Number(mm || 0);
  const gap = closed ? closed.gap : counted - r.expectedTotal;
  const canClose = !closed && ['OUVERT', 'A_CLOTURER'].includes(pdv.status) && can('PDV_GERER', 'PDV_VENDRE');
  const close = async () => {
    setBusy(true);
    try {
      await post(`/pdv/${pdv.id}/close`, { countedCash: Number(cash), countedMobileMoney: Number(mm) });
      notify('Point de vente clôturé · rapport généré');
      onClosed();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };
  const rows = [
    { icon: Banknote, l: 'Espèces', expected: r.expectedCash, counted: closed ? closed.countedCash : cash, set: setCash },
    { icon: Smartphone, l: 'Mobile money (Flooz / T-Money)', expected: r.expectedMobileMoney, counted: closed ? closed.countedMm : mm, set: setMm },
  ];
  return (
    <>
      <div className="section-label" style={{ marginTop: 18 }}>Rapprochement des cartes</div>
      <div className="grid g4" style={{ gap: 12 }}>
        {[['Cartes attribuées', r.cardsAllocated, 'var(--text)'], ['Cartes vendues', r.cardsSold, 'var(--primary)'],
          [closed ? 'Cartes rendues' : 'Cartes restantes', closed ? closed.cardsReturned : r.cardsRemaining, 'var(--green)'],
          ['Écart', r.cardsGap, r.cardsGap ? 'var(--red)' : 'var(--green)']].map(([l, v, c]) => (
          <div key={l} className="mini-stat">
            <div className="l">{l}</div>
            <div className="v num" style={{ color: c, fontSize: 24 }}>{num(v)}</div>
          </div>
        ))}
      </div>

      <div className="section-label" style={{ marginTop: 22 }}>Comptage des encaissements</div>
      {rows.map((x) => {
        const diff = Number(x.counted || 0) - x.expected;
        return (
          <div key={x.l} className="row" style={{ gap: 16, padding: '12px 0', borderBottom: '1px solid var(--border-2)' }}>
            <IcoBox icon={x.icon} tone="gray" />
            <span className="strong" style={{ flex: 1 }}>{x.l}</span>
            <div style={{ textAlign: 'right' }}>
              <div className="muted" style={{ fontSize: 12 }}>Attendu</div>
              <div className="num">{fcfa(x.expected)}</div>
            </div>
            <div>
              <div className="muted" style={{ fontSize: 12, textAlign: 'right' }}>Compté</div>
              <label className="input" style={{ width: 150 }}>
                <input className="num strong" style={{ textAlign: 'right' }} type="number" min="0" value={x.counted} disabled={!canClose}
                  onChange={(e) => x.set(e.target.value)} />
              </label>
            </div>
            <div style={{ width: 100, textAlign: 'right' }}>
              {diff === 0 ? <Badge tone="green">Conforme</Badge> : <Badge tone="red">{diff > 0 ? '+' : '−'}{fcfa(Math.abs(diff))} F</Badge>}
            </div>
          </div>
        );
      })}

      <div className="row between" style={{ background: 'var(--navy)', color: '#fff', borderRadius: 12, padding: '16px 22px', marginTop: 18 }}>
        <div>
          <div style={{ fontSize: 13, opacity: 0.75 }}>Total encaissé · {num(r.cardsSold)} cartes vendues</div>
          <div className="num" style={{ fontSize: 24, fontWeight: 700, marginTop: 4 }}>{fcfa(counted)} FCFA</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontSize: 13, opacity: 0.75 }}>Écart global</div>
          <div className="num" style={{ fontSize: 21, fontWeight: 700, color: gap ? '#ff7b7b' : '#a3e08b', marginTop: 4 }}>
            {gap === 0 ? '0 F' : `${gap > 0 ? '+' : '−'}${fcfa(Math.abs(gap))} F`}
          </div>
        </div>
      </div>
      <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
        <Button icon={Printer} onClick={() => window.print()}>Imprimer</Button>
        {canClose && <Button variant="primary" icon={CircleCheck} disabled={busy} onClick={close}>Clôturer et générer le rapport</Button>}
        {closed && <Badge tone="gray">Clôturé le {dayMonth(closed.closedAt)}</Badge>}
      </div>
    </>
  );
}

export default function PointsDeVente() {
  const { can } = useSession();
  const [params] = useSearchParams();
  const [selected, setSelected] = useState(params.get('id') ? Number(params.get('id')) : null);
  const [modal, setModal] = useState(null);
  const [, tick] = useState(0);
  const summary = useApi('/pdv/summary');
  const list = useApi('/pdv');
  const detail = useApi(selected ? `/pdv/${selected}` : null);
  const reload = () => {
    summary.reload();
    list.reload();
    detail.reload();
  };
  useLiveReload(reload, ['operation'], 5000);
  useInterval(() => tick((n) => n + 1), 30_000);

  useEffect(() => {
    if (!selected && list.data?.length) {
      const temp = list.data.find((p) => p.kind === 'TEMPORAIRE' && p.status === 'OUVERT');
      setSelected((temp || list.data[0]).id);
    }
  }, [list.data, selected]);

  const s = summary.data;
  const lastWeek = s?.cardsSoldSameDayLastWeek ? ((s.cardsSoldToday - s.cardsSoldSameDayLastWeek) / s.cardsSoldSameDayLastWeek) * 100 : null;
  const cashShare = s?.cashedToday ? Math.round((s.cashToday / s.cashedToday) * 100) : null;

  return (
    <>
      <PageHeader
        title="Points de vente"
        subtitle="Point de vente permanent au siège et points de vente temporaires · fonctionne hors connexion"
        actions={
          <>
            {can('PDV_GERER') && <Button icon={Store} onClick={() => setModal({ type: 'pdv' })}>Point permanent</Button>}
            {can('PDV_GERER', 'PDV_VENDRE') && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'pdv', temporary: true })}>Nouveau point de vente</Button>}
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Store} label="Points de vente ouverts" value={s ? s.open : '…'} foot={s ? `${s.openPermanent} permanent · ${s.openTemporary} temporaires` : ''} />
        <Kpi icon={CreditCard} tone="green" label="Cartes vendues aujourd’hui" value={s ? num(s.cardsSoldToday) : '…'}
          foot={lastWeek === null ? 'aucune la semaine dernière' : <><Trend value={pct(lastWeek)} down={lastWeek < 0} /> vs même jour la semaine dernière</>} />
        <Kpi icon={HandCoins} tone="navy" label="Encaissé aujourd’hui" value={s ? fcfa(s.cashedToday) : '…'} unit="FCFA"
          foot={cashShare === null ? '—' : `Espèces ${cashShare} % · Mobile money ${100 - cashShare} %`} />
        <Kpi icon={Clock} tone="orange" label="Demandes d’ouverture" value={s ? s.pendingRequests : '…'} foot={s?.toClose ? `${s.toClose} à clôturer` : 'en attente Admin / DAF'} />
      </div>

      <div className="grid split" style={{ gridTemplateColumns: '440px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Points de vente</div>
            {can('PDV_GERER', 'PDV_VENDRE') && <Button size="sm" icon={Plus} onClick={() => setModal({ type: 'pdv', temporary: true })}>Demander une ouverture</Button>}
          </div>
          <div className="stack" style={{ gap: 12, marginTop: 18 }}>
            {list.data?.length === 0 && <Empty>Aucun point de vente</Empty>}
            {(list.data || []).map((p) => {
              const active = p.id === selected;
              return (
                <button
                  key={p.id}
                  onClick={() => setSelected(p.id)}
                  style={{
                    textAlign: 'left', borderRadius: 10, padding: '14px 16px', background: '#fff',
                    border: active ? '2px solid var(--primary)' : '1px solid var(--border)',
                  }}
                >
                  <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}>
                    <IcoBox icon={p.kind === 'PERMANENT' ? Store : CalendarDays} tone={p.kind === 'PERMANENT' ? 'blue' : 'purple'} size={34} />
                    <div style={{ flex: 1 }}>
                      <div className="strong" style={{ fontSize: 14 }}>{p.name}</div>
                      <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{subtitle(p)}</div>
                    </div>
                    {p.statusLabel === 'Hors ligne' ? <Badge tone="orange" dot>Hors ligne</Badge> : <StatusBadge status={p.statusLabel} />}
                  </div>
                  <div className="row" style={{ marginTop: 12, gap: 18, color: 'var(--muted)', fontSize: 13 }}>
                    <span className="row" style={{ gap: 6 }}><Users size={15} /> {p.agents} agents</span>
                    <span className="row num" style={{ gap: 6 }}><CreditCard size={15} /> {num(p.cardsSold)} / {num(p.cardsPlanned || p.cardsAllocated)} cartes</span>
                    <span className="strong num" style={{ marginLeft: 'auto', color: 'var(--text)' }}>{p.amountTotal ? money(p.amountTotal) : '— F'}</span>
                  </div>
                  {p.statusLabel === 'Hors ligne' && (
                    <div className="row" style={{ gap: 6, marginTop: 10, color: 'var(--orange)', fontSize: 13 }}>
                      <CloudOff size={15} /> Dernière synchronisation {ago(p.lastSyncAt)} · ventes enregistrées sur place
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </Card>

        <div className="stack">
          {selected && (
            <Async state={detail} minHeight={300}>
              {(p) => (
                <>
                  <Card className="card-pad">
                    <div className="row between">
                      <div className="card-title">{p.name}{['OUVERT', 'A_CLOTURER', 'CLOS'].includes(p.status) ? ' · clôture' : ''}</div>
                      <span className="row" style={{ gap: 8 }}>
                        {p.status === 'OUVERT' && can('PDV_VENDRE') && <Button size="sm" icon={BadgePlus} onClick={() => setModal({ type: 'sell', pdv: p })}>Vendre une carte</Button>}
                        {['OUVERT', 'DEMANDE'].includes(p.status) && can('PDV_GERER') && <Button size="sm" icon={PackagePlus} onClick={() => setModal({ type: 'allocate', pdv: p })}>Attribuer des cartes</Button>}
                      </span>
                    </div>
                    <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
                      <Badge tone={p.kind === 'PERMANENT' ? 'blue' : 'purple'}>{p.kind === 'PERMANENT' ? 'Permanent' : 'Temporaire'}</Badge>
                      {p.kind === 'TEMPORAIRE' && <span className="muted" style={{ fontSize: 13 }}>{subtitle(p)}</span>}
                      {p.validatedBy && <Badge tone="gray">Validé par {p.validatedBy} · {dayMonth(p.validatedAt)}</Badge>}
                      {p.validationStatus === 'EN_ATTENTE' && <Badge tone="orange">Validation {p.validationRef} en attente</Badge>}
                      {p.status === 'OUVERT' && (p.statusLabel === 'Hors ligne'
                        ? <Badge tone="orange" dot>Hors ligne · {ago(p.lastSyncAt)}</Badge>
                        : <Badge tone="green" dot>Synchronisé · {ago(p.lastSyncAt)}</Badge>)}
                    </div>
                    {p.reason && <div className="muted" style={{ fontSize: 13, marginTop: 10 }}>{p.reason}</div>}
                    {p.status === 'DEMANDE' ? (
                      <Empty>En attente de validation par l’Admin ou le DAF (écran Validations).</Empty>
                    ) : (
                      <Closure pdv={p} onClosed={reload} />
                    )}
                  </Card>

                  <Card className="card-pad">
                    <div className="row between">
                      <div className="card-title">Agents affectés</div>
                      <span className="muted" style={{ fontSize: 13 }}>Ventes de cartes</span>
                    </div>
                    <div style={{ marginTop: 8 }}>
                      {(p.agentsList || []).length === 0 && <Empty>Aucun agent</Empty>}
                      {(p.agentsList || []).map((a) => (
                        <div key={a.id} className="list-item" style={{ borderBottom: 0, padding: '9px 0' }}>
                          <Avatar initials={a.initials} color={avatarColor(a.id)} size={38} photo={fileUrl(a.photoUrl)} />
                          <div style={{ flex: 1 }}>
                            <div className="strong">{a.name}</div>
                            <div className="muted" style={{ fontSize: 13 }}>{a.role === 'SUPERVISEUR' ? 'Superviseur' : 'Agent'} · pièce d’identité vérifiée à chaque vente</div>
                          </div>
                          <span className="strong num" style={{ width: 50, textAlign: 'right' }}>{num(a.cardsSold)}</span>
                          <span className="muted" style={{ width: 60, textAlign: 'right' }}>cartes</span>
                        </div>
                      ))}
                    </div>
                  </Card>
                </>
              )}
            </Async>
          )}
        </div>
      </div>

      {modal?.type === 'pdv' && <PdvModal temporary={modal.temporary} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'allocate' && <AllocateModal pdv={modal.pdv} onClose={() => setModal(null)} onSaved={reload} />}
      {modal?.type === 'sell' && <SellModal pdv={modal.pdv} onClose={() => setModal(null)} onSaved={reload} />}
    </>
  );
}
