import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  CreditCard, Building2, Wallet, Package, Plus, Search, Radio, ArrowRight, Store, Banknote, CircleCheck,
  ChevronRight, Pencil, Layers, CircleDot, Cpu, Download, ShieldCheck,
} from 'lucide-react';
import {
  PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Select, SearchInput, KV, BankCard, IcoBox, Async, Empty, Pager,
} from '../components/ui.jsx';
import CardPicker from '../components/CardPicker.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, compact, exportCsv, fcfa, masked, money, num } from '../lib/format.js';

const TYPE_TONE = { FLOTTE: 'blue', CLUB: 'navy', CLASSIQUE: 'gray', EMPLOYE: 'green' };
const TABS = [
  { key: 'ALL', label: 'Toutes les cartes' },
  { key: 'FLOTTE', label: 'Cartes flottes' },
  { key: 'A_ACTIVER', label: 'À activer' },
  { key: 'STOCK', label: 'Stock & suivi' },
];
const TECHNO = { PUCE: 'Puce', M1: 'M1' };
const expiry = (d) => (d ? `${String(d).slice(5, 7)}/${String(d).slice(2, 4)}` : '—');

/**
 * Attribution d'une carte activée du stock à un titulaire (particulier ou entreprise) : la carte est active aussitôt.
 * Carte Club : dépôt initial crédité sur la carte (200 000 F par défaut) et adhésion de 12 mois.
 */
export function NewCardModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const companies = useApi('/holders?kind=ENTREPRISE');
  const settings = useApi('/settings');
  const [card, setCard] = useState(null);
  const [f, setF] = useState({
    holderId: '', kind: fleet ? 'ENTREPRISE' : 'PARTICULIER', name: '',
    profile: 'GRAND_PUBLIC', idDocType: 'CNI', idDocNumber: '', phone: '', email: '', pin: '', vehiclePlate: '', vehicleLabel: '',
    driverName: '', deposit: '', paymentMethod: 'ESPECES', paymentRef: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const typeCode = card?.typeCode || (fleet ? 'FLOTTE' : 'CLASSIQUE');
  const isFleet = typeCode === 'FLOTTE';
  const isClub = typeCode === 'CLUB';
  const minDeposit = Number(settings.data?.['club.initial_deposit'] || 200000);
  const cardError = card && card.status !== 'STOCK'
    ? (card.status === 'A_ACTIVER' ? 'Cette carte n’est pas encore activée : une autre personne doit l’activer.' : `Carte ${card.statusLabel?.toLowerCase()} : pas disponible.`)
    : null;
  return (
    <FormModal
      wide
      title={isFleet ? 'Attribuer une carte flotte' : 'Attribuer une carte'}
      submitLabel={isClub ? `Attribuer et créditer ${fcfa(f.deposit || 0)} F` : 'Attribuer la carte'}
      onClose={onClose}
      onSubmit={async () => {
        if (!card) throw new Error('Choisissez une carte activée du stock');
        if (cardError) throw new Error(cardError);
        const holder = f.holderId ? null : {
          kind: isFleet ? 'ENTREPRISE' : f.kind, name: f.name, profile: f.profile, idDocType: f.idDocType || null,
          idDocNumber: f.idDocNumber || null, phone: f.phone || null, email: f.email || null,
        };
        const c = await post('/cards', {
          holderId: f.holderId ? Number(f.holderId) : null, holder, typeCode, number: card.number,
          pin: f.pin || null, vehiclePlate: f.vehiclePlate || null, vehicleLabel: f.vehicleLabel || null, driverName: f.driverName || null,
          deposit: isClub ? Number(f.deposit) : null, paymentMethod: isClub ? f.paymentMethod : null, paymentRef: f.paymentRef || null,
        });
        notify(`Carte ${masked(c.number)} attribuée et active${isClub ? ' · adhésion Club ouverte' : ''}`);
        onSaved?.(c);
      }}
    >
      <Field label="Carte activée du stock" hint={cardError || (card ? `${card.typeLabel} · ${card.techno === 'M1' ? 'M1' : 'puce'}` : 'Numéro complet ou 5 derniers chiffres de la carte remise au client')}>
        <CardPicker value={card} onChange={setCard} autoFocus />
      </Field>
      <div className="form-grid" style={{ marginTop: 12 }}>
        {(isFleet || f.kind === 'ENTREPRISE') && (
          <Field label="Entreprise existante" full>
            <SelectInput value={f.holderId} onChange={set('holderId')} placeholder="Nouvelle entreprise…"
              options={(companies.data || []).map((h) => ({ value: h.id, label: h.name }))} />
          </Field>
        )}
        {!f.holderId && (
          <>
            {!isFleet && (
              <Field label="Titulaire">
                <SelectInput value={f.kind} onChange={set('kind')} options={[{ value: 'PARTICULIER', label: 'Particulier' }, { value: 'ENTREPRISE', label: 'Entreprise' }]} />
              </Field>
            )}
            <Field label={isFleet || f.kind === 'ENTREPRISE' ? 'Raison sociale' : 'Nom et prénom'} full={isFleet}>
              <TextInput value={f.name} onChange={set('name')} required />
            </Field>
            <Field label="Pièce d'identité">
              <SelectInput value={f.idDocType} onChange={set('idDocType')} options={['CNI', 'Passeport', 'Permis', 'RCCM']} />
            </Field>
            <Field label="Numéro de la pièce"><TextInput value={f.idDocNumber} onChange={set('idDocNumber')} required={!isFleet && f.kind === 'PARTICULIER'} /></Field>
            <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
            <Field label="Email" hint={isClub ? 'Pour les rappels d’échéance Club' : undefined}><TextInput value={f.email} onChange={set('email')} type="email" /></Field>
            <Field label="Profil de prix">
              <SelectInput value={f.profile} onChange={set('profile')} options={[{ value: 'GRAND_PUBLIC', label: 'Grand public' }, { value: 'PARTENAIRE', label: 'Partenaire' }]} />
            </Field>
          </>
        )}
        <Field label="Code PIN (4 à 6 chiffres)"><TextInput value={f.pin} onChange={set('pin')} inputMode="numeric" pattern="[0-9]{4,6}" /></Field>
        {isClub && (
          <>
            <Field label="Dépôt initial crédité (F)" hint={`Minimum ${fcfa(minDeposit)} F, en plus du prix de la carte`}>
              <TextInput type="number" min={minDeposit} step="10000" value={f.deposit} onChange={set('deposit')} required />
            </Field>
            <Field label="Paiement">
              <SelectInput value={f.paymentMethod} onChange={set('paymentMethod')} options={[{ value: 'ESPECES', label: 'Espèces' },
                { value: 'MOBILE_MONEY', label: 'Mobile money' }, { value: 'VIREMENT', label: 'Virement' }, { value: 'CARTE_BANCAIRE', label: 'Carte bancaire' }]} />
            </Field>
            <Field label="Référence du paiement"><TextInput value={f.paymentRef} onChange={set('paymentRef')} /></Field>
          </>
        )}
        {isFleet && (
          <>
            <Field label="Immatriculation"><TextInput value={f.vehiclePlate} onChange={set('vehiclePlate')} placeholder="TG 4521 BK" /></Field>
            <Field label="Véhicule"><TextInput value={f.vehicleLabel} onChange={set('vehicleLabel')} /></Field>
            <Field label="Chauffeur"><TextInput value={f.driverName} onChange={set('driverName')} /></Field>
          </>
        )}
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>
        Seules les cartes activées (par une autre personne que celle qui a créé le lot) s'attribuent.
      </div>
    </FormModal>
  );
}

/** Lot de cartes numérotées ; « déjà actives » si l'activation physique est faite (une autre personne confirme). */
function BatchModal({ onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ typeCode: 'CLASSIQUE', techno: 'PUCE', quantity: '100', alreadyActive: false });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Lot de cartes" submitLabel="Créer le lot" onClose={onClose} onSubmit={async () => {
      const r = await post('/cards/batches', { ...f, quantity: Number(f.quantity) });
      notify(`${r.quantity} cartes créées · à ${f.alreadyActive ? 'confirmer' : 'activer'} par une autre personne`);
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Type"><SelectInput value={f.typeCode} onChange={set('typeCode')} options={[{ value: 'CLASSIQUE', label: 'Classique' },
          { value: 'CLUB', label: 'Club (carte noire)' }, { value: 'FLOTTE', label: 'Flotte' }, { value: 'EMPLOYE', label: 'Employé' }]} /></Field>
        <Field label="Technologie"><SelectInput value={f.techno} onChange={set('techno')} options={[{ value: 'PUCE', label: 'Carte à puce' }, { value: 'M1', label: 'M1' }]} /></Field>
        <Field label="Quantité"><TextInput type="number" min="1" max="5000" value={f.quantity} onChange={set('quantity')} required /></Field>
        <Field label="Activation physique">
          <SelectInput value={f.alreadyActive ? 'oui' : 'non'} onChange={(v) => set('alreadyActive')(v === 'oui')}
            options={[{ value: 'non', label: 'Pas encore faite' }, { value: 'oui', label: 'Déjà faite (cartes déjà actives)' }]} />
        </Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>
        Anti-fraude : le lot n'est utilisable qu'après l'activation (ou la confirmation) par une autre personne que vous.
      </div>
    </FormModal>
  );
}

/** Lots en attente d'activation, activables d'un coup par une autre personne que leur créateur. */
function PendingBatches({ onActivated }) {
  const notify = useToast();
  const { user, can } = useSession();
  const batches = useApi('/cards/batches');
  const pending = (batches.data || []).filter((b) => b.toActivate > 0);
  if (!pending.length) return null;
  const activate = async (b) => {
    try {
      const r = await post(`/cards/batches/${b.id}/activate`);
      notify(`${r.activated} carte(s) activée(s) · disponibles pour l'attribution`);
      batches.reload();
      onActivated();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <div style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      <div className="section-label">Lots à activer</div>
      {pending.map((b) => (
        <div key={b.id} className="row between" style={{ padding: '6px 0' }}>
          <span>
            <span className="strong">{b.typeCode === 'CLUB' ? 'Club' : b.typeCode.toLowerCase()} · {b.toActivate} carte(s)</span>
            <span className="muted"> · dès {b.firstNumber} · créé par {b.createdByName}{b.physicallyActive ? ' · déjà actives (à confirmer)' : ''}</span>
          </span>
          {can('CARTES_ACTIVER') && (
            b.createdBy === user.id
              ? <span className="muted" style={{ fontSize: 12.5 }}>Une autre personne doit l'activer</span>
              : <Button size="sm" variant="success" icon={ShieldCheck} onClick={() => activate(b)}>{b.physicallyActive ? 'Confirmer' : 'Activer'} le lot</Button>
          )}
        </div>
      ))}
    </div>
  );
}

function PricesModal({ types, onClose, onSaved }) {
  const notify = useToast();
  const sold = types.filter((t) => t.soldToPublic);
  const [prices, setPrices] = useState(Object.fromEntries(sold.map((t) => [t.code, t.price])));
  return (
    <FormModal title="Prix des cartes" onClose={onClose} onSubmit={async () => {
      for (const t of sold) {
        if (Number(prices[t.code]) !== t.price) await put(`/cards/types/${t.code}`, { price: Number(prices[t.code]) });
      }
      notify('Prix des cartes enregistrés');
      onSaved();
    }}>
      <div className="form-grid">
        {sold.map((t) => (
          <Field key={t.code} label={`Carte ${t.label} (F)`}>
            <TextInput type="number" min="0" value={prices[t.code]} onChange={(v) => setPrices({ ...prices, [t.code]: v })} />
          </Field>
        ))}
      </div>
    </FormModal>
  );
}

export default function Cartes() {
  const navigate = useNavigate();
  const notify = useToast();
  const { can } = useSession();
  const [tab, setTab] = useState('ALL');
  const [found, setFound] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState({ type: '', status: '', techno: '' });
  const [page, setPage] = useState(0);
  const [modal, setModal] = useState(null);

  const summary = useApi('/cards/summary');
  const list = useApi(`/cards${qs({ tab, q: search, ...filters, page, size: 25 })}`);
  const recent = useApi('/cards?tab=FLOTTE&size=3');
  useLiveReload(() => {
    summary.reload();
    list.reload();
  }, ['operation'], 5000);

  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(q.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const lookup = async (n = '601128470093') => {
    setLookupError(null);
    try {
      setFound(await api(`/cards/lookup/${encodeURIComponent(n.replace(/\D/g, ''))}`));
    } catch (e) {
      setFound(null);
      setLookupError(e.message);
    }
  };
  useEffect(() => {
    lookup();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const activate = async (card, e) => {
    e?.stopPropagation();
    try {
      await post(`/cards/${card.id}/activate`);
      notify(`Carte ${masked(card.number)} activée`);
      list.reload();
      summary.reload();
    } catch (err) {
      notify(err.message, 'error');
    }
  };

  const s = summary.data;
  const lowType = s?.stockByType?.filter((t) => t.inStock + t.atPdv > 0).sort((a, b) => a.inStock - b.inStock)[0];
  const setFilter = (k) => (v) => {
    setFilters((x) => ({ ...x, [k]: v }));
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Cartes"
        subtitle="Cartes classiques, Club et flottes · cartes à puce et M1 · activées avant d'être attribuées"
        actions={
          <>
            {can('RECHARGER') && <Button icon={Wallet} onClick={() => navigate('/rechargements')}>Rechargement</Button>}
            {can('CARTES_GERER') && <Button icon={Package} onClick={() => setModal({ type: 'batch' })}>Lot de cartes</Button>}
            {can('CARTES_GERER') && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'new' })}>Attribuer une carte</Button>}
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={CreditCard} label="Cartes actives" value={s ? num(s.active) : '…'} foot={s ? <><Trend value={num(s.activatedThisMonth)} /> ce mois</> : ''} />
        <Kpi icon={Building2} tone="navy" label="Cartes flottes" value={s ? num(s.fleet) : '…'} foot={s ? `${s.fleetCompanies} entreprises · ${s.groups} groupes` : ''} />
        <Kpi icon={Wallet} tone="green" label="Solde total" value={s ? compact(s.totalBalance) : '…'} unit="FCFA" foot={s ? `${num(s.toActivate)} cartes à activer` : ''} />
        <Kpi icon={Package} tone="orange" label="Stock de cartes vierges" value={s ? num(s.inStock) : '…'}
          foot={lowType ? <><Badge tone="orange">{lowType.label} {num(lowType.inStock)}</Badge> {num(s.atPdv)} en PDV</> : ''} />
      </div>

      {/* Consulter une carte */}
      <Card className="mb20 lookup-band" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 1px 280px 270px', alignItems: 'center', padding: '24px 24px' }}>
        <div style={{ paddingRight: 24 }}>
          <div className="card-title">Consulter une carte</div>
          <div className="card-sub">Saisissez ou scannez le numéro pour voir ses dernières informations et la modifier.</div>
          <div style={{ marginTop: 18 }}>
            <CardPicker value={found} onChange={(c) => (c ? lookup(c.number) : setFound(null))} height={48}
              placeholder="Numéro complet ou 5 derniers chiffres, puis Entrée"
              suffix={<Badge tone="gray"><Radio size={13} /> Lecteur COM9</Badge>} />
          </div>
          <div className="row" style={{ gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
            <span className="muted" style={{ whiteSpace: 'nowrap' }}>Récentes :</span>
            {(recent.data?.items || []).map((c) => (
              <button key={c.id} className="btn btn-sm" style={{ fontWeight: 500 }} onClick={() => lookup(c.number)}>
                {masked(c.number)} {c.holderName?.split(' ')[0]}
              </button>
            ))}
          </div>
          {lookupError && <div className="form-error">{lookupError}</div>}
        </div>
        <div style={{ background: 'var(--border)', height: '100%' }} />
        <div style={{ padding: '0 18px 0 22px' }}>
          <BankCard number={found?.numberFormatted || '•••• •••• ••••'} holder={(found?.holderName || '—').toUpperCase()}
            exp={expiry(found?.expiresOn)} kind={found?.typeLabel?.toUpperCase() || ''} />
        </div>
        <div>
          <KV k="Titulaire" v={found?.holderName || '—'} />
          <KV k="Solde" v={found ? money(found.balance) : '—'} />
          <KV k="Groupe" v={found?.groupName || '—'} />
          <KV k="Statut" v={found ? <StatusBadge status={found.statusLabel} /> : '—'} />
          <div className="row" style={{ gap: 8, marginTop: 10 }}>
            <Button variant="primary" size="sm" iconRight={ArrowRight} disabled={!found} onClick={() => navigate(`/cartes/${found.id}`)}>Ouvrir la fiche</Button>
            {can('RECHARGER') && (
              <Button size="sm" icon={Wallet} disabled={!found} onClick={() => navigate(`/rechargements?card=${found.number}`)}>Recharger</Button>
            )}
          </div>
        </div>
      </Card>

      {/* Suivi du stock */}
      <Card className="card-pad mb20">
        <div className="row between">
          <div className="card-title">Suivi du stock de cartes numérotées</div>
          <div className="row" style={{ gap: 8 }}>
            {(s?.types || []).filter((t) => t.soldToPublic).map((t) => (
              <Badge key={t.code} tone={TYPE_TONE[t.code]}>{t.label} {fcfa(t.price)} F</Badge>
            ))}
            {can('PRIX') && <Button size="sm" icon={Pencil} disabled={!s} onClick={() => setModal({ type: 'prices' })}>Modifier les prix</Button>}
          </div>
        </div>
        <div className="row" style={{ marginTop: 20, gap: 0, rowGap: 14, flexWrap: 'wrap' }}>
          {[
            [ShieldCheck, 'orange', 'À activer', s?.stockToActivate],
            [Package, 'gray', 'En stock (activées)', s?.inStock],
            [Store, 'orange', 'Attribuées à un PDV', s?.atPdv],
            [Banknote, 'blue', 'Vendues', s?.sold],
            [CircleCheck, 'green', 'Actives', s?.active],
          ].map(([Icon, tone, l, v], i, arr) => (
            <div key={l} className="row" style={{ flex: '1 1 190px', gap: 14 }}>
              <IcoBox icon={Icon} tone={tone} size={40} />
              <div>
                <div className="muted" style={{ fontSize: 13 }}>{l}</div>
                <div className="num" style={{ fontSize: 21, fontWeight: 700 }}>{v === undefined ? '…' : num(v)}</div>
              </div>
              {i < arr.length - 1 && <ChevronRight size={18} color="var(--muted-2)" style={{ marginLeft: 'auto', marginRight: 24 }} />}
            </div>
          ))}
        </div>
        <PendingBatches onActivated={() => { summary.reload(); list.reload(); }} />
      </Card>

      {/* Liste */}
      <Card>
        <div className="tabs">
          {TABS.map((t) => (
            <button key={t.key} className={tab === t.key ? 'active' : ''} onClick={() => { setTab(t.key); setPage(0); }}>
              {t.label}
              {tab === t.key && list.data && <span className="count">{num(list.data.total)}</span>}
              {t.key === 'A_ACTIVER' && tab !== t.key && s?.toActivate > 0 && <span className="count">{s.toActivate}</span>}
            </button>
          ))}
        </div>
        <div className="filters">
          <SearchInput icon={Search} placeholder="N° de carte, titulaire, immatriculation…" style={{ width: 260 }} value={q} onChange={(e) => setQ(e.target.value)} />
          <Select icon={Layers} value={filters.type} onChange={setFilter('type')} placeholder="Type"
            options={[{ value: 'CLASSIQUE', label: 'Classique' }, { value: 'CLUB', label: 'Club' }, { value: 'FLOTTE', label: 'Flotte' }, { value: 'EMPLOYE', label: 'Employé' }]} />
          <Select icon={CircleDot} value={filters.status} onChange={setFilter('status')} placeholder="Statut"
            options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'A_ACTIVER', label: 'À activer' }, { value: 'BLOQUEE', label: 'Bloquée' },
              { value: 'INACTIVE', label: 'Inactive' }, { value: 'STOCK', label: 'En stock' }, { value: 'PDV', label: 'En point de vente' }]} />
          <Select icon={Cpu} value={filters.techno} onChange={setFilter('techno')} placeholder="Technologie"
            options={[{ value: 'PUCE', label: 'Puce' }, { value: 'M1', label: 'M1' }]} />
          <div className="row" style={{ marginLeft: 'auto', gap: 8 }}>
            {can('CARTES_GERER') && <Button icon={Building2} onClick={() => setModal({ type: 'new', fleet: true })}>Attribuer une carte flotte</Button>}
            <Button icon={Download} disabled={!list.data?.items.length} onClick={() => exportCsv('cartes.csv', [
              { label: 'Numéro', value: 'numberFormatted' }, { label: 'Titulaire', value: 'holderName' }, { label: 'Groupe', value: 'groupName' },
              { label: 'Type', value: 'typeLabel' }, { label: 'Techno', value: 'techno' }, { label: 'Solde (F)', value: 'balance' },
              { label: 'Statut', value: 'statusLabel' }, { label: 'Dernière opération', value: 'lastOperation' },
            ], list.data.items)}>Exporter</Button>
          </div>
        </div>
        <Async state={list}>
          {(data) => (data.items.length === 0 ? <Empty>Aucune carte</Empty> : (
            <>
              <div className="table-wrap"><table className="table">
                <thead>
                  <tr>
                    <th>N° de carte</th><th>Titulaire · groupe</th><th>Type</th><th>Techno</th><th className="r">Solde (FCFA)</th><th>Statut</th>
                    <th>Dernière opération</th><th />
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((c) => (
                    <tr key={c.id} className="tr-click" onClick={() => navigate(`/cartes/${c.id}`)}>
                      <td className="num strong" style={{ fontSize: 14, letterSpacing: '.02em' }}>{c.numberFormatted}</td>
                      <td>
                        <div className="cell-title">{c.holderName || '—'}</div>
                        <div className="cell-sub">{c.groupName || c.vehicleLabel || '—'}</div>
                      </td>
                      <td><Badge tone={TYPE_TONE[c.typeCode]}>{c.typeLabel}</Badge></td>
                      <td>{TECHNO[c.techno] || c.techno}</td>
                      <td className="r num strong">{fcfa(c.balance)}</td>
                      <td><StatusBadge status={c.statusLabel} /></td>
                      <td className="muted">{c.lastOperation ? `${c.lastOperation} · ${ago(c.lastOperationAt)}` : '—'}</td>
                      <td className="r">
                        {c.status === 'A_ACTIVER' && can('CARTES_ACTIVER') && (
                          <Button size="sm" variant="success" icon={ShieldCheck} onClick={(e) => activate(c, e)}>Activer</Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
              <Pager data={data} page={page} onPage={setPage} label="cartes" />
            </>
          ))}
        </Async>
      </Card>

      {modal?.type === 'new' && (
        <NewCardModal fleet={modal.fleet} onClose={() => setModal(null)} onSaved={(card) => { list.reload(); summary.reload(); navigate(`/cartes/${card.id}`); }} />
      )}
      {modal?.type === 'batch' && (
        <BatchModal onClose={() => setModal(null)} onSaved={() => { list.reload(); summary.reload(); }} />
      )}
      {modal?.type === 'prices' && <PricesModal types={s.types} onClose={() => setModal(null)} onSaved={summary.reload} />}
    </>
  );
}
