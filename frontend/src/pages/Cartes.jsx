import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  CreditCard, Building2, Wallet, Package, Plus, Search, Radio, ArrowRight, Store, Banknote, CircleCheck,
  ChevronRight, Pencil, Layers, CircleDot, Cpu, Download, ShieldCheck,
} from 'lucide-react';
import {
  PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Select, SearchInput, KV, BankCard, IcoBox, Async, Empty, Pager,
} from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { api, post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { ago, compact, exportCsv, fcfa, masked, money, num } from '../lib/format.js';

const TYPE_TONE = { FLOTTE: 'blue', VIP: 'purple', CLASSIQUE: 'gray', EMPLOYE: 'green' };
const TABS = [
  { key: 'ALL', label: 'Toutes les cartes' },
  { key: 'FLOTTE', label: 'Cartes flottes' },
  { key: 'A_ACTIVER', label: 'À activer' },
  { key: 'STOCK', label: 'Stock & suivi' },
];
const TECHNO = { PUCE: 'Puce', M1: 'M1' };
const expiry = (d) => (d ? `${String(d).slice(5, 7)}/${String(d).slice(2, 4)}` : '—');

/** Création d'une carte pour un titulaire (particulier ou entreprise) ; elle reste « à activer ». */
export function NewCardModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const companies = useApi('/holders?kind=ENTREPRISE');
  const [f, setF] = useState({
    typeCode: fleet ? 'FLOTTE' : 'CLASSIQUE', techno: 'PUCE', holderId: '', kind: fleet ? 'ENTREPRISE' : 'PARTICULIER', name: '',
    profile: 'GRAND_PUBLIC', idDocType: 'CNI', idDocNumber: '', phone: '', email: '', pin: '', vehiclePlate: '', vehicleLabel: '',
    driverName: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const isFleet = f.typeCode === 'FLOTTE';
  return (
    <FormModal
      wide
      title={isFleet ? 'Nouvelle carte flotte' : 'Nouvelle carte'}
      submitLabel="Créer la carte"
      onClose={onClose}
      onSubmit={async () => {
        const holder = f.holderId ? null : {
          kind: isFleet ? 'ENTREPRISE' : f.kind, name: f.name, profile: f.profile, idDocType: f.idDocType || null,
          idDocNumber: f.idDocNumber || null, phone: f.phone || null, email: f.email || null,
        };
        const card = await post('/cards', {
          holderId: f.holderId ? Number(f.holderId) : null, holder, typeCode: f.typeCode, techno: f.techno,
          pin: f.pin || null, vehiclePlate: f.vehiclePlate || null, vehicleLabel: f.vehicleLabel || null, driverName: f.driverName || null,
        });
        notify(`Carte ${masked(card.number)} créée · à activer par une autre personne`);
        onSaved?.(card);
      }}
    >
      <div className="form-grid">
        <Field label="Type de carte">
          <SelectInput value={f.typeCode} onChange={set('typeCode')} options={[
            { value: 'CLASSIQUE', label: 'Classique' }, { value: 'VIP', label: 'VIP' }, { value: 'FLOTTE', label: 'Flotte (entreprise)' }]} />
        </Field>
        <Field label="Technologie">
          <SelectInput value={f.techno} onChange={set('techno')} options={[{ value: 'PUCE', label: 'Carte à puce' }, { value: 'M1', label: 'M1' }]} />
        </Field>
        {isFleet && (
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
            <Field label="Email"><TextInput value={f.email} onChange={set('email')} type="email" /></Field>
            <Field label="Profil de prix">
              <SelectInput value={f.profile} onChange={set('profile')} options={[{ value: 'GRAND_PUBLIC', label: 'Grand public' }, { value: 'PARTENAIRE', label: 'Partenaire' }]} />
            </Field>
          </>
        )}
        <Field label="Code PIN (4 à 6 chiffres)"><TextInput value={f.pin} onChange={set('pin')} inputMode="numeric" pattern="[0-9]{4,6}" /></Field>
        {isFleet && (
          <>
            <Field label="Immatriculation"><TextInput value={f.vehiclePlate} onChange={set('vehiclePlate')} placeholder="TG 4521 BK" /></Field>
            <Field label="Véhicule"><TextInput value={f.vehicleLabel} onChange={set('vehicleLabel')} /></Field>
            <Field label="Chauffeur"><TextInput value={f.driverName} onChange={set('driverName')} /></Field>
          </>
        )}
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>
        Anti-fraude : la carte devra être activée par une autre personne que vous.
      </div>
    </FormModal>
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
  const [number, setNumber] = useState('6011 2847 0093');
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

  const lookup = async (n = number) => {
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
        subtitle="Cartes classiques, VIP et flottes · cartes à puce et M1 prises en charge"
        actions={
          <>
            {can('RECHARGER') && <Button icon={Wallet} onClick={() => navigate('/rechargements')}>Rechargement</Button>}
            {can('CARTES_GERER') && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'new' })}>Nouvelle carte</Button>}
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
          <form className="row" style={{ gap: 10, marginTop: 18 }} onSubmit={(e) => { e.preventDefault(); lookup(); }}>
            <label className="input" style={{ flex: 1, minWidth: 0, height: 48, borderWidth: 2, borderColor: 'var(--primary)', boxShadow: '0 0 0 3px var(--primary-50)' }}>
              <CreditCard size={18} color="var(--primary)" />
              <input className="num" style={{ fontSize: 15, fontWeight: 600, letterSpacing: '.05em' }} value={number} onChange={(e) => setNumber(e.target.value)} />
              <Badge tone="gray"><Radio size={13} /> Lecteur COM9</Badge>
            </label>
            <Button variant="primary" icon={Search} style={{ height: 48 }} type="submit">Rechercher</Button>
          </form>
          <div className="row" style={{ gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
            <span className="muted" style={{ whiteSpace: 'nowrap' }}>Récentes :</span>
            {(recent.data?.items || []).map((c) => (
              <button key={c.id} className="btn btn-sm" style={{ fontWeight: 500 }} onClick={() => { setNumber(c.numberFormatted); lookup(c.number); }}>
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
            [Package, 'gray', 'En stock', s?.inStock],
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
            options={[{ value: 'CLASSIQUE', label: 'Classique' }, { value: 'VIP', label: 'VIP' }, { value: 'FLOTTE', label: 'Flotte' }, { value: 'EMPLOYE', label: 'Employé' }]} />
          <Select icon={CircleDot} value={filters.status} onChange={setFilter('status')} placeholder="Statut"
            options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'A_ACTIVER', label: 'À activer' }, { value: 'BLOQUEE', label: 'Bloquée' },
              { value: 'INACTIVE', label: 'Inactive' }, { value: 'STOCK', label: 'En stock' }, { value: 'PDV', label: 'En point de vente' }]} />
          <Select icon={Cpu} value={filters.techno} onChange={setFilter('techno')} placeholder="Technologie"
            options={[{ value: 'PUCE', label: 'Puce' }, { value: 'M1', label: 'M1' }]} />
          <div className="row" style={{ marginLeft: 'auto', gap: 8 }}>
            {can('CARTES_GERER') && <Button icon={Building2} onClick={() => setModal({ type: 'new', fleet: true })}>Créer une carte flotte</Button>}
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
      {modal?.type === 'prices' && <PricesModal types={s.types} onClose={() => setModal(null)} onSaved={summary.reload} />}
    </>
  );
}
