import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Banknote, Droplet, Wifi, ShieldCheck, Download, Plus, Fuel, Wallet, CreditCard, ArrowLeftRight, Flame,
  Clock, ArrowRight, TriangleAlert, Mail,
} from 'lucide-react';
import {
  PageHeader, Card, CardHead, Kpi, Trend, Badge, StatusBadge, Button, Segmented, IcoBox, Progress,
} from '../components/ui.jsx';
import { AreaChart } from '../components/charts.jsx';
import { STATIONS } from '../data/stations.js';

// Courbe cumulée (en millions) de 06h à 18h42
const TODAY = [0.2, 0.8, 1.1, 2.4, 2.8, 3.6, 4.2, 4.4, 5.3, 5.6, 6.6, 7.0, 8.0, 8.4, 9.4, 9.9, 10.6, 11.6, 12.0, 13.0, 13.5, 14.6, 15.8, 16.8, 18.45];
const YESTERDAY = [0.1, 0.6, 1.1, 2.2, 2.5, 3.2, 3.8, 4.1, 4.9, 5.2, 6.0, 6.4, 7.3, 7.6, 8.6, 9.0, 9.7, 10.6, 11.0, 11.8, 12.2, 13.2, 14.3, 15.2, 16.6];

const FEED = [
  { icon: Fuel, tone: 'blue', t: 'Vente carburant · 42,5 L', s: 'Kossi A. · Pompe 3 · Agoè', v: '28 900 F', when: 'à l’instant' },
  { icon: Wallet, tone: 'green', t: 'Recharge carte ···2847', s: 'Essi L. · Siège Lomé', v: '50 000 F', when: 'il y a 1 min' },
  { icon: ShieldCheck, tone: 'orange', t: 'Validation demandée', s: 'Translom SARL · flotte', v: '2 500 000 F', when: 'il y a 3 min' },
  { icon: CreditCard, tone: 'navy', t: 'Vente carte VIP', s: 'Afi D. · Foire de Lomé', v: '5 000 F', when: 'il y a 4 min' },
  { icon: ArrowLeftRight, tone: 'green', t: 'Bascule mode autonome', s: 'Kara Centre · auto', v: '', when: 'il y a 6 min' },
  { icon: Flame, tone: 'red', t: 'Échange gaz 12 kg', s: 'Yao A. · Bè Kpota', v: '6 500 F', when: 'il y a 7 min' },
  { icon: Fuel, tone: 'blue', t: 'Vente carburant · 60 L', s: 'Komlan T. · Pompe 1 · Adidogomé', v: '40 500 F', when: 'il y a 9 min' },
];

const PDV = [
  { name: 'Siège Lomé', kind: 'Permanent', amount: '1 240 000 F', pct: 72 },
  { name: 'Foire Internationale de Lomé', kind: 'Temporaire · J-3', amount: '386 000 F', pct: 48 },
  { name: 'Stand Agro-Plus Togo', kind: 'Temporaire · J-1', amount: '94 000 F', pct: 20, offline: 'Hors ligne · 12 op.' },
];

const ETAT_TONE = { Normal: 'gray', 'Cuve Gasoil basse': 'orange', 'Lecteur déconnecté': 'red' };

export function CuveBars({ levels }) {
  const colors = ['#1b66b8', '#5aa935', '#f29e1f'];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 4, height: 24 }}>
      {levels.map((l, i) => (
        <span key={i} style={{ width: 7, height: 24, background: '#edf1f5', borderRadius: 2, display: 'flex', alignItems: 'flex-end' }}>
          <span style={{ width: '100%', height: `${l}%`, borderRadius: 2, background: l < 20 ? '#e5484d' : colors[i] }} />
        </span>
      ))}
    </span>
  );
}

export default function Dashboard() {
  const navigate = useNavigate();
  const [period, setPeriod] = useState('Jour');
  const rows = STATIONS.filter((s) => ['agoe-assiyeye', 'be-kpota', 'adidogome', 'kara-centre', 'atakpame'].includes(s.id));

  return (
    <>
      <PageHeader
        title="Bonjour Ama"
        subtitle="Lundi 2 novembre 2026 · voici l’activité de votre réseau en direct"
        actions={
          <>
            <Button icon={Download}>Exporter</Button>
            <Button variant="primary" icon={Plus} onClick={() => navigate('/stations')}>Nouvelle station</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Banknote} label="Recette du jour" value="18 452 500" unit="FCFA" foot={<><Trend value="12,4 %" /> vs hier à la même heure</>} />
        <Kpi icon={Droplet} tone="green" label="Volume distribué" value="27 140" unit="L" foot={<><Trend value="6,1 %" /> Super 61 % · Gasoil 39 %</>} />
        <Kpi icon={Wifi} tone="navy" label="Stations en ligne" value="38" unit="/ 42" foot={<><Badge tone="red" dot>4 hors ligne</Badge> depuis 10 min</>} />
        <Kpi icon={ShieldCheck} tone="orange" label="Validations en attente" value="4" foot={<><span className="row" style={{ gap: 5, color: 'var(--orange)', fontWeight: 600 }}><Clock size={14} /> 6,8 M FCFA</span> à approuver</>} />
      </div>

      <div className="grid mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px' }}>
        <Card>
          <CardHead
            title="Recette en direct"
            sub="Toutes stations, mise à jour en continu"
            right={<Segmented items={['Jour', 'Semaine', 'Mois']} value={period} onChange={setPeriod} />}
          />
          <div className="row" style={{ gap: 34, padding: '20px 20px 0', alignItems: 'flex-end' }}>
            <div>
              <div className="muted" style={{ fontSize: 12.5 }}>Cumul à 18:42</div>
              <div className="num" style={{ fontSize: 21, fontWeight: 700, marginTop: 4 }}>
                18 452 500 <small className="muted" style={{ fontSize: 12.5 }}>FCFA</small>
              </div>
            </div>
            {[['Carburant', '15 980 200'], ['Cartes & recharges', '1 640 000'], ['Gaz & lubrifiants', '832 300']].map(([l, v]) => (
              <div key={l}>
                <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                <div className="num strong" style={{ fontSize: 16, marginTop: 6 }}>{v}</div>
              </div>
            ))}
            <div className="row" style={{ marginLeft: 'auto', gap: 16, alignSelf: 'flex-start' }}>
              <span className="legend"><i style={{ background: '#1b66b8' }} /> Aujourd’hui</span>
              <span className="legend"><i style={{ background: '#8db4e2' }} /> Hier</span>
            </div>
          </div>
          <div style={{ padding: '14px 12px 20px 4px' }}>
            <AreaChart
              today={TODAY}
              yesterday={YESTERDAY}
              max={20}
              height={300}
              yLabels={['0 M', '5 M', '10 M', '15 M', '20 M']}
              xLabels={['06h', '08h', '10h', '12h', '14h', '16h', '18h']}
            />
          </div>
        </Card>

        <Card>
          <CardHead title="Flux en direct" sub="Qui fait quoi, en ce moment" right={<span className="live-pill" style={{ height: 26 }}><span className="pulse" /> Live</span>} />
          <div style={{ padding: '8px 20px 6px' }}>
            {FEED.map((f) => (
              <div key={f.t + f.when} className="list-item" style={{ alignItems: 'flex-start' }}>
                <IcoBox icon={f.icon} tone={f.tone} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="strong" style={{ fontSize: 14 }}>{f.t}</div>
                  <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>{f.s}</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div className="strong num" style={{ fontSize: 14 }}>{f.v}</div>
                  <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>{f.when}</div>
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px', alignItems: 'start' }}>
        <Card>
          <CardHead
            title="État des stations"
            sub="38 en ligne · 4 hors ligne"
            right={<Button size="sm" iconRight={ArrowRight} onClick={() => navigate('/stations')}>Voir les 42 stations</Button>}
          />
          <table className="table" style={{ marginTop: 14 }}>
            <thead>
              <tr>
                <th>Station</th><th>Statut</th><th>Mode</th><th className="r">Recette du jour</th><th>Cuves</th><th>État</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="tr-click" onClick={() => navigate(`/stations/${s.id}`)}>
                  <td>
                    <div className="row" style={{ gap: 12 }}>
                      <IcoBox icon={Fuel} tone="gray" size={32} />
                      <div>
                        <div className="cell-title">{s.short}</div>
                        <div className="cell-sub">{s.type}</div>
                      </div>
                    </div>
                  </td>
                  <td><StatusBadge status={s.status} /></td>
                  <td><Badge tone={s.mode === 'Autonome' ? 'navy' : 'blue'}>{s.mode}</Badge></td>
                  <td className="r num strong">{s.recette || '—'}</td>
                  <td><CuveBars levels={s.cuves} /></td>
                  <td>{s.etat && <Badge tone={ETAT_TONE[s.etat]}>{s.etat}</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <div className="stack">
          <Card>
            <CardHead title="Points de vente ouverts" right={<Badge tone="green">3 ouverts</Badge>} />
            <div style={{ padding: '6px 20px 14px' }}>
              {PDV.map((p) => (
                <div key={p.name} style={{ padding: '14px 0', borderBottom: '1px solid var(--border-2)' }}>
                  <div className="row between">
                    <span className="row" style={{ gap: 8 }}>
                      <span className="strong">{p.name}</span>
                      {p.offline && <Badge tone="orange">{p.offline}</Badge>}
                    </span>
                    <span className="strong num">{p.amount}</span>
                  </div>
                  <div className="row" style={{ marginTop: 10, gap: 14 }}>
                    <span className="muted" style={{ width: 120, fontSize: 13 }}>{p.kind}</span>
                    <Progress value={p.pct} />
                    <span className="muted" style={{ fontSize: 13, whiteSpace: 'nowrap' }}>{p.pct} % cartes</span>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          <Card>
            <CardHead title="Alertes de stock" right={<span className="muted" style={{ fontSize: 13 }}>3 actives</span>} />
            <div style={{ padding: '6px 20px 10px' }}>
              {[
                { icon: TriangleAlert, tone: 'red', t: 'Gasoil · Bè Kpota', s: '2 150 L restants · seuil auto 3 400 L' },
                { icon: TriangleAlert, tone: 'orange', t: 'Bouteilles 6 kg · Kara', s: '14 pleines · seuil 20' },
                { icon: Mail, tone: 'blue', t: 'Email envoyé au gérant', s: 'Alerte cuve · 18:31' },
              ].map((a) => (
                <div key={a.t} className="list-item" style={{ borderBottom: 0, padding: '10px 0' }}>
                  <IcoBox icon={a.icon} tone={a.tone} />
                  <div>
                    <div className="strong">{a.t}</div>
                    <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>{a.s}</div>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
