import { Download, UserPlus, Trophy, Crown, Users, Search, CircleCheck, Fuel, IdCard, MoreHorizontal, Pencil, Check } from 'lucide-react';
import { PageHeader, Card, Badge, Button, Select, SearchInput, Avatar } from '../components/ui.jsx';

const TEAM = [
  { i: 'KA', c: 'blue', n: 'Kossi Amegah', s: 'Agoè Assiyéyé', r: 'Gérant', tone: 'blue' },
  { i: 'YA', c: 'green', n: 'Yao Agbeko', s: 'Agoè Assiyéyé', r: 'Chef de poste', tone: 'navy' },
  { i: 'KT', c: 'navy', n: 'Komlan Tetteh', s: 'Agoè Assiyéyé', r: 'Pompiste', tone: 'gray' },
  { i: 'EL', c: 'teal', n: 'Essi Lawson', s: 'Siège Lomé', r: 'Agent PDV', tone: 'purple' },
  { i: 'AG', c: 'orange', n: 'Abla Gbeasor', s: 'Siège Lomé', r: 'Comptable', tone: 'green' },
  { i: 'MK', c: 'purple', n: 'Mawuli Kpodar', s: 'Siège Lomé', r: 'DAF', tone: 'orange' },
];

const ROLES = ['Admin', 'Gérant', 'Chef p.', 'Pomp.', 'Compta.', 'DAF'];
const RIGHTS = [
  ['Créer et attribuer stations', [1, 0, 0, 0, 0, 0]],
  ['Gérer la station', [1, 1, 0, 0, 0, 0]],
  ['Ventes à la pompe', [0, 0, 1, 1, 0, 0]],
  ['Rapports journaliers', [1, 1, 1, 0, 0, 0]],
  ['Historique transactions', [1, 1, 0, 0, 1, 1]],
  ['Valider montants élevés', [0, 0, 0, 0, 1, 1]],
  ['Valider cartes entreprises', [0, 0, 0, 0, 0, 1]],
];

export default function Employes() {
  return (
    <>
      <PageHeader
        title="Employés & rôles"
        subtitle="Fiches employés avec photo, rôles et droits d’accès, employé du mois"
        actions={
          <>
            <Button icon={Download}>Exporter</Button>
            <Button variant="primary" icon={UserPlus}>Nouvel employé</Button>
          </>
        }
      />

      <div className="grid mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 300px' }}>
        {/* Employé du mois */}
        <div
          className="row"
          style={{
            position: 'relative', overflow: 'hidden', borderRadius: 'var(--radius)', color: '#fff', padding: '0 34px', gap: 26, minHeight: 150,
            background: 'linear-gradient(110deg, #13306b 0%, #174a92 55%, #1b66b8 100%)',
          }}
        >
          <div style={{ position: 'absolute', right: -40, top: -10, width: 230, height: 230, borderRadius: '50%', border: '24px solid rgba(90,169,53,.45)' }} />
          <div style={{ position: 'absolute', right: -80, top: -50, width: 330, height: 330, borderRadius: '50%', border: '1px solid rgba(255,255,255,.12)' }} />
          <div style={{ position: 'relative' }}>
            <div style={{ width: 76, height: 76, borderRadius: '50%', background: '#fff', color: 'var(--primary)', display: 'grid', placeItems: 'center', fontSize: 26, fontWeight: 700 }}>KT</div>
            <span style={{ position: 'absolute', right: -4, bottom: -2, width: 26, height: 26, borderRadius: '50%', background: '#f6c445', border: '2px solid #fff', display: 'grid', placeItems: 'center', color: '#7a4b00' }}>
              <Crown size={13} strokeWidth={2.4} />
            </span>
          </div>
          <div style={{ position: 'relative', whiteSpace: 'nowrap' }}>
            <div className="row" style={{ gap: 7, fontSize: 13, opacity: 0.85 }}><Trophy size={15} /> Meilleur employé · octobre 2026</div>
            <div style={{ fontSize: 23, fontWeight: 700, marginTop: 6 }}>Komlan Tetteh</div>
            <div style={{ fontSize: 14, opacity: 0.8, marginTop: 4 }}>Pompiste · Zener Agoè Assiyéyé</div>
          </div>
          <div className="row" style={{ marginLeft: 'auto', gap: 12, position: 'relative' }}>
            {[['Produits vendus', '1 284'], ['Litres servis', '38 420'], ['Lubrifiants', '96']].map(([l, v]) => (
              <div key={l} style={{ width: 112, whiteSpace: 'nowrap', border: '1px solid rgba(255,255,255,.22)', background: 'rgba(255,255,255,.1)', borderRadius: 10, padding: '10px 14px' }}>
                <div style={{ fontSize: 12, opacity: 0.8 }}>{l}</div>
                <div className="num" style={{ fontSize: 19, fontWeight: 700, marginTop: 2 }}>{v}</div>
              </div>
            ))}
          </div>
        </div>

        <Card className="kpi">
          <div className="kpi-top"><span className="kpi-icon tone-blue"><Users size={18} /></span> Employés</div>
          <div className="kpi-value num">386</div>
          <div className="kpi-foot">sur 42 stations</div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between" style={{ alignItems: 'flex-start' }}>
            <div>
              <div className="card-title">Équipe</div>
              <div className="card-sub">386 employés · photo affichée sur la carte de l’employé</div>
            </div>
            <Button variant="primary" size="sm" icon={UserPlus}>Nouvel employé</Button>
          </div>
          <div className="row" style={{ gap: 10, marginTop: 18 }}>
            <SearchInput icon={Search} placeholder="Rechercher un employé…" style={{ flex: 1 }} />
            <Select icon={CircleCheck}>Rôle</Select>
            <Select icon={Fuel}>Station</Select>
          </div>
          <div className="grid g3" style={{ gap: 14, marginTop: 18 }}>
            {TEAM.map((m) => (
              <div key={m.n} style={{ position: 'relative', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 14px 14px', textAlign: 'center' }}>
                <button className="link-more" style={{ position: 'absolute', right: 10, top: 10 }}><MoreHorizontal size={17} /></button>
                <div style={{ display: 'flex', justifyContent: 'center' }}><Avatar initials={m.i} color={m.c} size={56} /></div>
                <div className="strong" style={{ fontSize: 14, marginTop: 12 }}>{m.n}</div>
                <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{m.s}</div>
                <Badge tone={m.tone} style={{ marginTop: 10 }}>{m.r}</Badge>
                <div style={{ borderTop: '1px solid var(--border-2)', marginTop: 14, paddingTop: 12 }}>
                  <Button size="sm" icon={IdCard} style={{ fontWeight: 500 }}>Carte employé</Button>
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <div className="card-head" style={{ paddingBottom: 14 }}>
            <div>
              <div className="card-title">Rôles & droits</div>
              <div className="card-sub">6 rôles</div>
            </div>
            <button className="link-more"><Pencil size={16} /></button>
          </div>
          <table className="table">
            <thead>
              <tr><th>Droit</th>{ROLES.map((r) => <th key={r} className="c" style={{ padding: '11px 6px' }}>{r}</th>)}</tr>
            </thead>
            <tbody>
              {RIGHTS.map(([d, vals]) => (
                <tr key={d}>
                  <td style={{ fontWeight: 500, padding: '15px 8px 15px 20px', whiteSpace: 'nowrap' }}>{d}</td>
                  {vals.map((v, i) => (
                    <td key={i} className="c" style={{ padding: '12px 6px' }}>
                      {v ? (
                        <span style={{ display: 'inline-grid', placeItems: 'center', width: 22, height: 22, borderRadius: 5, background: 'var(--green-50)', color: 'var(--green)' }}>
                          <Check size={14} strokeWidth={2.6} />
                        </span>
                      ) : (
                        <span style={{ display: 'inline-block', width: 16, height: 1.5, background: '#d5dce5' }} />
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
    </>
  );
}
