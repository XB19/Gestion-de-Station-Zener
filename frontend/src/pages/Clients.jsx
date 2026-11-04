import { useState } from 'react';
import { Download, Plus, Building2, KeyRound, CalendarDays, CircleX, Search, RefreshCw, Power, MoreHorizontal } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, SearchInput, Avatar, KV, Progress } from '../components/ui.jsx';
import { Ring } from '../components/charts.jsx';

const CLIENTS = [
  { i: 'GZ', c: 'blue', n: 'Groupe Zener', s: 'Succursales · 3 pays', st: 24, lic: 'Active', end: '31/12/2026', days: 59 },
  { i: 'EA', c: 'green', n: 'Ets Amegah & Fils', s: 'Gérance libre · Togo', st: 3, lic: 'Active', end: '14/03/2027', days: 132 },
  { i: 'SK', c: 'navy', n: 'Sté Kodjo Services', s: 'Gérance libre · Togo', st: 2, lic: 'Expire bientôt', end: '25/11/2026', days: 23 },
  { i: 'AP', c: 'teal', n: 'Agbeko Pétrole', s: 'Gérance libre · Togo', st: 2, lic: 'Expire bientôt', end: '30/11/2026', days: 28 },
  { i: 'EM', c: 'orange', n: 'Ets Mensah', s: 'Gérance libre · Togo', st: 1, lic: 'Active', end: '02/06/2027', days: 212 },
  { i: 'SS', c: 'purple', n: 'Sté Savanes Énergie', s: 'Gérance libre · Togo', st: 1, lic: 'Résiliée', end: '15/10/2026', days: 0 },
  { i: 'BC', c: 'pink', n: 'Bénin Carburants SA', s: 'Gérance libre · Bénin', st: 2, lic: 'Active', end: '01/02/2027', days: 91 },
];

export default function Clients() {
  const [q, setQ] = useState('');
  const rows = CLIENTS.filter((c) => c.n.toLowerCase().includes(q.toLowerCase()));

  return (
    <>
      <PageHeader
        title="Clients & licences"
        subtitle="Attribution des stations aux clients et gestion de la licence annuelle"
        actions={
          <>
            <Button icon={Download}>Exporter</Button>
            <Button variant="primary" icon={Plus}>Nouveau client</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Building2} label="Clients" value="19" foot="1 groupe · 18 gérants" />
        <Kpi icon={KeyRound} tone="green" label="Licences actives" value="40" foot="sur 42 stations" />
        <Kpi icon={CalendarDays} tone="orange" label="À renouveler sous 30 j" value="5" foot={<Badge tone="orange">Échéance 31/12</Badge>} />
        <Kpi icon={CircleX} tone="red" label="Résiliées" value="2" foot="Dapaong · Kpalimé Est" />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
        <Card>
          <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
            <div className="card-title">Clients</div>
            <SearchInput icon={Search} placeholder="Rechercher un client…" style={{ width: 300 }} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <table className="table">
            <thead>
              <tr><th>Client</th><th className="r">Stations</th><th>Licence</th><th>Échéance</th><th>Restant</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((c) => {
                const color = c.days === 0 ? '#e2e8f0' : c.days < 30 ? '#f29e1f' : '#5aa935';
                return (
                  <tr key={c.n}>
                    <td>
                      <div className="row" style={{ gap: 12 }}>
                        <Avatar initials={c.i} color={c.c} size={36} />
                        <div>
                          <div className="cell-title">{c.n}</div>
                          <div className="cell-sub">{c.s}</div>
                        </div>
                      </div>
                    </td>
                    <td className="r num strong">{c.st}</td>
                    <td><StatusBadge status={c.lic} /></td>
                    <td className="num">{c.end}</td>
                    <td>
                      <div className="row" style={{ gap: 10, width: 130 }}>
                        <Progress value={Math.max(Math.min((c.days / 240) * 100, 100), c.days ? 6 : 0)} color={color} />
                        <span className="muted num" style={{ fontSize: 13, width: 38, textAlign: 'right' }}>{c.days} j</span>
                      </div>
                    </td>
                    <td className="r">
                      <button className="icon-btn" style={{ width: 32, height: 32 }} title={c.lic === 'Résiliée' ? 'Réactiver' : 'Renouveler'}>
                        {c.lic === 'Résiliée' ? <Power size={15} /> : <RefreshCw size={15} />}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>

        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Licence</div>
            <button className="link-more"><MoreHorizontal size={18} /></button>
          </div>
          <div className="row" style={{ gap: 18, marginTop: 16 }}>
            <Ring value={23} max={365} size={108}>
              <div>
                <div className="num" style={{ fontSize: 24, fontWeight: 700 }}>23</div>
                <div className="muted" style={{ fontSize: 12 }}>jours</div>
              </div>
            </Ring>
            <div>
              <div className="strong" style={{ fontSize: 16 }}>Sté Kodjo Services</div>
              <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>Licence annuelle · 2 stations</div>
              <Badge tone="orange" dot style={{ marginTop: 8 }}>Expire le 25/11/2026</Badge>
            </div>
          </div>
          <div style={{ marginTop: 8 }}>
            <KV k="Formule" v="Annuelle · par station" />
            <KV k="Montant" v="1 200 000 F / an" />
            <KV k="Début" v="25/11/2025" />
            <KV k="Stations couvertes" v="Tsévié Nord, Tsévié Gare" />
            <KV k="Rappel automatique" v="J-30, J-7, J-1" />
          </div>
          <Button variant="primary" icon={RefreshCw} block style={{ height: 42, marginTop: 14 }}>Renouveler pour 1 an</Button>
          <Button variant="danger" icon={Power} block style={{ height: 42, marginTop: 10 }}>Résilier la licence</Button>
        </Card>
      </div>
    </>
  );
}
