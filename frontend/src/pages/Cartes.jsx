import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  CreditCard, Building2, Wallet, Package, Plus, Search, Radio, ArrowRight, Pencil, Store, Banknote, CircleCheck,
  ChevronRight, Layers, Users, CircleDot, Cpu, Download, MoreHorizontal,
} from 'lucide-react';
import {
  PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Select, SearchInput, KV, BankCard, IcoBox,
} from '../components/ui.jsx';

export const CARDS = [
  { n: '6011 2847 0093', holder: 'Translom SARL', group: 'Camions Nord', type: 'Flotte', techno: 'Puce', solde: '1 284 500', status: 'Active', last: 'Recharge · il y a 2 h' },
  { n: '6011 2847 4410', holder: 'Komi Mensah', group: null, type: 'VIP', techno: 'M1', solde: '96 000', status: 'Active', last: 'Vente 42 L · hier' },
  { n: '6011 3302 7782', holder: 'Agro-Plus Togo', group: 'Livraisons', type: 'Flotte', techno: 'Puce', solde: '642 000', status: 'Active', last: 'Vente 120 L · 14:02' },
  { n: '6011 3302 1209', holder: 'Akossiwa Lawson', group: null, type: 'Classique', techno: 'M1', solde: '12 500', status: 'Active', last: 'Vente 18 L · 09:40' },
  { n: '6011 2290 5531', holder: 'BTP Kara Construction', group: 'Engins', type: 'Flotte', techno: 'Puce', solde: '0', status: 'Bloquée', last: 'Plafond mensuel atteint' },
  { n: '6011 4417 0028', holder: 'Lomé Logistique', group: 'Navettes', type: 'Flotte', techno: 'Puce', solde: '2 015 000', status: 'Active', last: 'Recharge masse · 1 nov.' },
  { n: '6011 4417 9930', holder: 'Yawo Agbodjan', group: null, type: 'Classique', techno: 'M1', solde: '4 000', status: 'Inactive', last: 'Vendue · Foire de Lomé' },
];

const TYPE_TONE = { Flotte: 'blue', VIP: 'purple', Classique: 'gray' };

const TABS = [
  ['Toutes les cartes', '12 480'],
  ['Cartes flottes', '312'],
  ['Groupes', '26'],
  ['Stock & suivi', '3 200'],
  ['Types & prix', '3'],
];

export default function Cartes() {
  const navigate = useNavigate();
  const [tab, setTab] = useState(TABS[0][0]);
  const [number, setNumber] = useState('ZN 6011 2847 0093');
  const [q, setQ] = useState('');
  const rows = CARDS.filter((c) => (c.n + c.holder).toLowerCase().includes(q.toLowerCase()));
  const open = () => navigate('/cartes/6011-2847-0093');

  return (
    <>
      <PageHeader
        title="Cartes"
        subtitle="Cartes classiques, VIP et flottes · cartes à puce et M1 prises en charge"
        actions={
          <>
            <Button icon={Wallet} onClick={() => navigate('/rechargements')}>Rechargement</Button>
            <Button variant="primary" icon={Plus}>Nouvelle carte</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={CreditCard} label="Cartes actives" value="12 480" foot={<><Trend value="284" /> ce mois</>} />
        <Kpi icon={Building2} tone="navy" label="Cartes flottes" value="312" foot="48 entreprises · 26 groupes" />
        <Kpi icon={Wallet} tone="green" label="Solde total" value="86,4 M" unit="FCFA" foot={<><Trend value="4,2 %" /> vs mois dernier</>} />
        <Kpi icon={Package} tone="orange" label="Stock de cartes vierges" value="3 200" foot={<><Badge tone="orange">Classique bas</Badge> VIP 640</>} />
      </div>

      {/* Consulter une carte */}
      <Card className="mb20" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 1px 280px 270px', alignItems: 'center', padding: '24px 24px' }}>
        <div style={{ paddingRight: 24 }}>
          <div className="card-title">Consulter une carte</div>
          <div className="card-sub">Saisissez ou scannez le numéro pour voir ses dernières informations et la modifier.</div>
          <div className="row" style={{ gap: 10, marginTop: 18 }}>
            <label className="input" style={{ flex: 1, minWidth: 0, height: 48, borderWidth: 2, borderColor: 'var(--primary)', boxShadow: '0 0 0 3px var(--primary-50)' }}>
              <CreditCard size={18} color="var(--primary)" />
              <input className="num" style={{ fontSize: 15, fontWeight: 600, letterSpacing: '.05em' }} value={number} onChange={(e) => setNumber(e.target.value)} />
              <Badge tone="gray"><Radio size={13} /> Lecteur COM9</Badge>
            </label>
            <Button variant="primary" icon={Search} style={{ height: 48 }} onClick={open}>Rechercher</Button>
          </div>
          <div className="row" style={{ gap: 8, marginTop: 14 }}>
            <span className="muted" style={{ whiteSpace: 'nowrap' }}>Récentes :</span>
            {['···0093 Translom', '···4410 K. Mensah', '···7782 Agro-Plus'].map((r) => (
              <button key={r} className="btn btn-sm" style={{ fontWeight: 500 }}>{r}</button>
            ))}
          </div>
        </div>
        <div style={{ background: 'var(--border)', height: '100%' }} />
        <div style={{ padding: '0 18px 0 22px' }}>
          <BankCard number="6011 2847 0093" holder="TRANSLOM SARL" exp="12/28" />
        </div>
        <div>
          <KV k="Titulaire" v="Translom SARL" />
          <KV k="Solde" v="1 284 500 F" />
          <KV k="Groupe" v="Camions Nord" />
          <KV k="Statut" v={<StatusBadge status="Active" />} />
          <div className="row" style={{ gap: 8, marginTop: 10 }}>
            <Button variant="primary" size="sm" iconRight={ArrowRight} onClick={open}>Ouvrir la fiche</Button>
            <Button size="sm" icon={Wallet} onClick={() => navigate('/rechargements')}>Recharger</Button>
          </div>
        </div>
      </Card>

      {/* Suivi du stock */}
      <Card className="card-pad mb20">
        <div className="row between">
          <div className="card-title">Suivi du stock de cartes numérotées</div>
          <div className="row" style={{ gap: 8 }}>
            <Badge tone="gray">Classique 1 000 F</Badge>
            <Badge tone="purple">VIP 5 000 F</Badge>
            <Button size="sm" icon={Pencil}>Modifier les prix</Button>
          </div>
        </div>
        <div className="row" style={{ marginTop: 20, gap: 0 }}>
          {[
            [Package, 'gray', 'En stock', '3 200'],
            [Store, 'orange', 'Attribuées à un PDV', '640'],
            [Banknote, 'blue', 'Vendues', '15 820'],
            [CircleCheck, 'green', 'Actives', '12 480'],
          ].map(([Icon, tone, l, v], i, arr) => (
            <div key={l} className="row" style={{ flex: 1, gap: 14 }}>
              <IcoBox icon={Icon} tone={tone} size={40} />
              <div>
                <div className="muted" style={{ fontSize: 13 }}>{l}</div>
                <div className="num" style={{ fontSize: 21, fontWeight: 700 }}>{v}</div>
              </div>
              {i < arr.length - 1 && <ChevronRight size={18} color="var(--muted-2)" style={{ marginLeft: 'auto', marginRight: 24 }} />}
            </div>
          ))}
        </div>
      </Card>

      {/* Liste */}
      <Card>
        <div className="tabs">
          {TABS.map(([l, c]) => (
            <button key={l} className={tab === l ? 'active' : ''} onClick={() => setTab(l)}>
              {l} <span className="count">{c}</span>
            </button>
          ))}
        </div>
        <div className="filters">
          <SearchInput icon={Search} placeholder="N° de carte, titulaire…" style={{ width: 220 }} value={q} onChange={(e) => setQ(e.target.value)} />
          <Select icon={Layers}>Type</Select>
          <Select icon={Users}>Groupe</Select>
          <Select icon={CircleDot}>Statut</Select>
          <Select icon={Cpu}>Technologie</Select>
          <div className="row" style={{ marginLeft: 'auto', gap: 8 }}>
            <Button icon={Building2}>Créer une carte flotte</Button>
            <Button icon={Download}>Exporter</Button>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>N° de carte</th><th>Titulaire · groupe</th><th>Type</th><th>Techno</th><th className="r">Solde (FCFA)</th><th>Statut</th><th>Dernière opération</th><th />
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.n} className="tr-click" onClick={open}>
                <td className="num strong" style={{ fontSize: 14, letterSpacing: '.02em' }}>{c.n}</td>
                <td>
                  <div className="cell-title">{c.holder}</div>
                  <div className="cell-sub">{c.group || '—'}</div>
                </td>
                <td><Badge tone={TYPE_TONE[c.type]}>{c.type}</Badge></td>
                <td>{c.techno}</td>
                <td className="r num strong">{c.solde}</td>
                <td><StatusBadge status={c.status} /></td>
                <td className="muted">{c.last}</td>
                <td className="r"><button className="link-more" onClick={(e) => e.stopPropagation()}><MoreHorizontal size={18} /></button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
