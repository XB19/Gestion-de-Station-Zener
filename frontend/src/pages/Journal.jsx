import {
  SlidersHorizontal, Search, CalendarDays, User, Activity, Fuel, Download, Wallet, ShieldCheck, CreditCard,
  ArrowLeftRight, Mail, CircleCheck, Pencil, Building2, CloudOff, Cpu, Eye,
} from 'lucide-react';
import { PageHeader, Card, Button, Select, SearchInput, Avatar, IcoBox, KV } from '../components/ui.jsx';
import { Donut } from '../components/charts.jsx';

const OPS = [
  { t: '18:42:07', a: { i: 'KA', c: 'blue' }, n: 'Kossi Amegah', w: 'Gérant · Agoè Assiyéyé', icon: Fuel, tone: 'blue', q: 'Vente carburant', d: 'Pompe 3 · 42,5 L Super · carte ···4410', m: '28 900 F' },
  { t: '18:41:52', a: { i: 'EL', c: 'green' }, n: 'Essi Lawson', w: 'Agent PDV · Siège Lomé', icon: Wallet, tone: 'green', q: 'Recharge carte', d: 'Carte ···2847 · espèces', m: '50 000 F' },
  { t: '18:39:30', sys: true, n: 'Système', w: 'Siège Lomé', icon: ShieldCheck, tone: 'orange', q: 'Validation demandée', d: 'VAL-2026-1184 · Translom SARL', m: '1 950 000 F' },
  { t: '18:38:11', a: { i: 'AD', c: 'teal' }, n: 'Afi Dzifa Adjo', w: 'Agent PDV · Foire de Lomé', icon: CreditCard, tone: 'navy', q: 'Vente carte VIP', d: '···9931 · pièce d’identité vérifiée · Flooz', m: '5 000 F' },
  { t: '18:36:02', sys: true, n: 'Système', w: 'Kara Centre', icon: ArrowLeftRight, tone: 'green', q: 'Bascule en mode autonome', d: 'Conventionnel → Autonome', m: '—' },
  { t: '18:31:44', sys: true, n: 'Système', w: 'Bè Kpota', icon: Mail, tone: 'red', q: 'Email d’alerte envoyé', d: 'Gasoil 2 150 L < seuil auto 3 400 L', m: '—' },
  { t: '18:29:15', a: { i: 'AG', c: 'pink' }, n: 'Abla Gbeasor', w: 'Comptable · Siège Lomé', icon: CircleCheck, tone: 'green', q: 'Montant approuvé', d: 'VAL-2026-1183 · 1re validation', m: '2 800 000 F' },
  { t: '18:22:40', a: { i: 'YA', c: 'blue' }, n: 'Yao Agbeko', w: 'Chef de poste · Agoè Assiyéyé', icon: Pencil, tone: 'gray', q: 'Permissions modifiées', d: 'Carte ···0093 · plafond 150 000 → 200 000 F', m: '—' },
  { t: '18:20:03', a: { i: 'AK', c: 'indigo' }, n: 'Ama Kpodar', w: 'Admin · Siège', icon: Building2, tone: 'purple', q: 'Station attribuée', d: 'Zener Tsévié Nord → Sté Kodjo Services', m: '—' },
  { t: '18:12:57', a: { i: 'SA', c: 'forest' }, n: 'Sélom Ahadji', w: 'Agent PDV · Stand Agro-Plus', icon: CloudOff, tone: 'orange', q: 'Synchronisation hors ligne', d: '12 opérations envoyées après retour réseau', m: '—' },
];

const BREAKDOWN = [
  ['Ventes carburant', 4812, '#1b66b8'],
  ['Recharges', 640, '#5aa935'],
  ['Ventes cartes', 286, '#283c8f'],
  ['Validations', 12, '#f29e1f'],
  ['Modifications', 38, '#94a3b8'],
];

export default function Journal() {
  return (
    <>
      <PageHeader
        title="Journal d’opérations"
        subtitle="Qui a fait quoi, et quand · toutes les opérations centralisées avec le point de vente et l’agent"
        actions={<Button icon={SlidersHorizontal}>Filtres enregistrés</Button>}
      />

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 320px', alignItems: 'start' }}>
        <Card>
          <div className="filters" style={{ flexWrap: 'nowrap' }}>
            <SearchInput icon={Search} placeholder="Rechercher…" style={{ flex: 1, minWidth: 140 }} />
            <Select icon={CalendarDays}>Aujourd’hui</Select>
            <Select icon={User}>Utilisateur</Select>
            <Select icon={Activity}>Type</Select>
            <Select icon={Fuel}>Lieu</Select>
            <button className="icon-btn" style={{ marginLeft: 'auto' }}><Download size={17} /></button>
          </div>
          <table className="table">
            <thead>
              <tr><th>Heure</th><th>Qui · où</th><th>Quoi</th><th className="r">Montant</th></tr>
            </thead>
            <tbody>
              {OPS.map((o) => (
                <tr key={o.t}>
                  <td className="muted num" style={{ fontSize: 14 }}>{o.t}</td>
                  <td>
                    <div className="row" style={{ gap: 12 }}>
                      {o.sys ? (
                        <span className="avatar" style={{ width: 32, height: 32, background: 'var(--chip)', color: 'var(--muted)' }}><Cpu size={16} /></span>
                      ) : (
                        <Avatar initials={o.a.i} color={o.a.c} size={32} />
                      )}
                      <div>
                        <div className="cell-title">{o.n}</div>
                        <div className="cell-sub">{o.w}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <div className="row" style={{ gap: 12 }}>
                      <IcoBox icon={o.icon} tone={o.tone} size={32} />
                      <div>
                        <div className="cell-title">{o.q}</div>
                        <div className="cell-sub">{o.d}</div>
                      </div>
                    </div>
                  </td>
                  <td className="r num strong">{o.m}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <div className="stack">
          <Card className="card-pad">
            <div className="card-title">Opérations du jour</div>
            <div className="card-sub">5 788 opérations tracées</div>
            <div style={{ display: 'flex', justifyContent: 'center', margin: '20px 0 16px' }}>
              <Donut size={150} thickness={18} segments={BREAKDOWN.map(([, v, c]) => ({ value: v, color: c }))}>
                <div>
                  <div className="num" style={{ fontSize: 24, fontWeight: 700 }}>5 788</div>
                  <div className="muted" style={{ fontSize: 12 }}>opérations</div>
                </div>
              </Donut>
            </div>
            {BREAKDOWN.map(([l, v, c]) => (
              <div key={l} className="row between" style={{ padding: '8px 0' }}>
                <span className="legend"><i style={{ background: c }} /> {l}</span>
                <span className="strong num">{v.toLocaleString('fr-FR').replace(/ /g, ' ')}</span>
              </div>
            ))}
          </Card>

          <Card className="card-pad">
            <div className="card-title">Résumé quotidien par email</div>
            <div style={{ marginTop: 8 }}>
              <KV k="Envoi" v="Chaque jour à 23:59" />
              <KV k="Destinataires" v="Direction, DAF, gérants" />
              <KV k="Contenu" v="Recettes, alertes, validations" />
              <KV k="Dernier envoi" v="01/11 · 23:59 ✓" />
            </div>
            <Button icon={Eye} block style={{ marginTop: 14, height: 40 }}>Aperçu de l’email</Button>
          </Card>
        </div>
      </div>
    </>
  );
}
