import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Fuel, Wifi, ArrowLeftRight, Building2, Upload, Plus, Search, Globe, CircleDot, Map as MapIcon,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, Select, SearchInput, IcoBox } from '../components/ui.jsx';
import { STATIONS } from '../data/stations.js';
import { TOGO_BORDER } from '../data/togo.js';

/* ---------- Carte du Togo ----------
   Projection équirectangulaire corrigée de la latitude moyenne du pays (~8,6° N).
   Cadre : lon −0,95 → 2,45 ; lat 5,62 → 11,42 */
const LON0 = -0.95;
const LAT0 = 11.42;
const SCALE = 100;
const KX = Math.cos((8.6 * Math.PI) / 180);
const VB_W = Math.round((2.45 - LON0) * SCALE * KX);
const VB_H = Math.round((LAT0 - 5.62) * SCALE);
const px = ([lat, lon]) => [+((lon - LON0) * SCALE * KX).toFixed(1), +((LAT0 - lat) * SCALE).toFixed(1)];
const toPath = (pts, close) => 'M ' + pts.map((p) => px(p).join(' ')).join(' L ') + (close ? ' Z' : '');

const TOGO_PATH = toPath(TOGO_BORDER, true);

// Littoral du golfe de Guinée de part et d'autre du Togo (Ghana à l'ouest, Bénin à l'est)
const COAST = [
  [5.55, -0.95], [5.62, -0.4], [5.77, 0.3], [5.79, 0.63], [5.9, 0.9], [5.98, 1.06], [6.1, 1.2],
  [6.17, 1.45], [6.23, 1.63], [6.28, 1.8], [6.33, 2.1], [6.36, 2.45],
];
const SEA_PATH = toPath(COAST, false) + ` L ${VB_W} ${VB_H} L 0 ${VB_H} Z`;

// Frontières voisines (Burkina Faso au nord)
const NEIGHBOURS = [
  [[11.0, -0.95], [11.0, -0.5], [10.98, -0.14]], // Ghana / Burkina
  [[10.99, 0.92], [11.1, 1.1], [11.3, 1.45], [11.42, 1.7]], // Bénin / Burkina
];

// Stations géolocalisées (coordonnées réelles des villes)
const PINS = [
  { name: 'Dapaong', lat: 10.862, lon: 0.205, s: 'red' },
  { name: 'Kara', lat: 9.551, lon: 1.186, s: 'green' },
  { name: 'Sokodé', lat: 8.983, lon: 1.133, s: 'green' },
  { name: 'Atakpamé', lat: 7.527, lon: 1.126, s: 'red' },
  { name: 'Kpalimé', lat: 6.9, lon: 0.631, s: 'orange' },
  { name: '', lat: 6.95, lon: 1.168, s: 'green' }, // Notsè
  { name: '', lat: 6.426, lon: 1.213, s: 'green' }, // Tsévié
  { name: '', lat: 6.2, lon: 1.17, s: 'green' }, // Adidogomé
  { name: '', lat: 6.228, lon: 1.592, s: 'green' }, // Aného
  { name: 'Lomé', lat: 6.137, lon: 1.222, s: 'green' },
];
const PIN_COLOR = { green: '#3f9a2c', orange: '#f29e1f', red: '#e5484d' };

function TogoMap() {
  return (
    <div style={{ position: 'relative', background: '#eef2f6', borderRadius: 10, height: 560, overflow: 'hidden' }}>
      <svg viewBox={`0 0 ${VB_W} ${VB_H}`} width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        <path d={SEA_PATH} fill="#dce9f6" />
        <path d={toPath(COAST, false)} fill="none" stroke="#b9cfe6" strokeWidth="1" />
        {NEIGHBOURS.map((line, i) => (
          <path key={i} d={toPath(line, false)} fill="none" stroke="#c4d3e4" strokeWidth="1" strokeDasharray="3 3" />
        ))}
        <path d={TOGO_PATH} fill="#f8fafc" stroke="#8fb0d6" strokeWidth="1.3" strokeLinejoin="round" />
        {PINS.map((p, i) => {
          const [x, y] = px([p.lat, p.lon]);
          return (
            <g key={i}>
              <circle cx={x} cy={y} r="6.5" fill="#fff" stroke="#dfe6ee" />
              <circle cx={x} cy={y} r="3.4" fill={PIN_COLOR[p.s]} />
              {p.name && (
                <text x={x + 9} y={y + 3.8} fontSize="10.5" fontWeight="500" fill="#0f172a">
                  {p.name}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      <div
        className="card"
        style={{ position: 'absolute', left: 16, top: '45%', width: 200, padding: '14px 16px', boxShadow: 'var(--shadow-lg)' }}
      >
        <div className="strong">Zener Atakpamé</div>
        <div className="muted num" style={{ fontSize: 12, marginTop: 4 }}>7.5270° N · 1.1260° E</div>
        <Badge tone="red" dot style={{ marginTop: 10 }}>Hors ligne · 12 min</Badge>
        <div className="muted" style={{ fontSize: 12.5, marginTop: 10, lineHeight: 1.45 }}>
          Lecteur de cartes déconnecté (COM4)
        </div>
      </div>
    </div>
  );
}

export default function Stations() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const rows = useMemo(
    () => STATIONS.filter((s) => (s.name + s.client + s.zone).toLowerCase().includes(q.toLowerCase())),
    [q],
  );

  return (
    <>
      <PageHeader
        title="Stations"
        subtitle="Création, attribution aux clients et suivi de chaque station du réseau"
        actions={
          <>
            <Button icon={Upload}>Importer</Button>
            <Button variant="primary" icon={Plus}>Nouvelle station</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Fuel} label="Stations" value="42" foot="Togo 34 · Bénin 5 · Burkina 3" />
        <Kpi icon={Wifi} tone="green" label="En ligne" value="38" foot={<Badge tone="red" dot>4 hors ligne</Badge>} />
        <Kpi icon={ArrowLeftRight} tone="navy" label="Mode autonome" value="11" foot="dont 7 par plage horaire" />
        <Kpi icon={Building2} tone="orange" label="Succursales / Gérance" value="24 / 18" foot="18 clients gérants" />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 400px', alignItems: 'start' }}>
        <Card>
          <div className="filters" style={{ flexWrap: 'nowrap' }}>
            <SearchInput icon={Search} placeholder="Rechercher une station…" style={{ flex: 1, minWidth: 150 }} value={q} onChange={(e) => setQ(e.target.value)} />
            <Select icon={Globe}>Togo</Select>
            <Select icon={Building2}>Type</Select>
            <Select icon={ArrowLeftRight}>Mode</Select>
            <Select icon={CircleDot}>Statut</Select>
          </div>
          <table className="table">
            <thead>
              <tr>
                <th>Station</th><th>Client attribué</th><th>Statut</th><th>Mode</th><th className="r">Recette (F)</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="tr-click" onClick={() => navigate(`/stations/${s.id}`)}>
                  <td>
                    <div className="row" style={{ gap: 12 }}>
                      <IcoBox icon={Fuel} tone={s.type === 'Succursale' ? 'blue' : 'purple'} size={34} />
                      <div>
                        <div className="cell-title">{s.name}</div>
                        <div className="cell-sub">{s.type} · {s.zone}</div>
                      </div>
                    </div>
                  </td>
                  <td>{s.client}</td>
                  <td><StatusBadge status={s.status} /></td>
                  <td><Badge tone="gray">{s.mode}</Badge></td>
                  <td className="r num strong">{s.recette || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <Card>
          <div className="card-head">
            <div>
              <div className="card-title">Géolocalisation</div>
              <div className="card-sub">Togo · 34 stations</div>
            </div>
            <div className="row" style={{ gap: 8 }}>
              <button className="icon-btn" style={{ width: 34, height: 34 }}><Plus size={17} /></button>
              <button className="icon-btn" style={{ width: 34, height: 34 }}><MapIcon size={16} /></button>
            </div>
          </div>
          <div style={{ padding: '18px 20px 0' }}>
            <TogoMap />
          </div>
          <div className="row" style={{ gap: 20, padding: '16px 20px', borderTop: '1px solid var(--border-2)', marginTop: 4 }}>
            {[['En ligne', '#3f9a2c'], ['Instable', '#f29e1f'], ['Hors ligne', '#e5484d']].map(([l, c]) => (
              <span key={l} className="legend"><i style={{ background: c, borderRadius: '50%' }} /> {l}</span>
            ))}
          </div>
        </Card>
      </div>
    </>
  );
}
