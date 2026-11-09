import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Fuel, Wifi, ArrowLeftRight, Building2, Plus, Search, Globe, CircleDot, Download } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, Select, SearchInput, IcoBox, Async, Empty } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, Modal, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put, qs } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { exportCsv, fcfa } from '../lib/format.js';
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

export const PIN_COLOR = { EN_LIGNE: '#3f9a2c', INSTABLE: '#f29e1f', HORS_LIGNE: '#e5484d' };

// Dans le Grand Lomé les stations sont trop proches pour toutes porter un nom
const LOME = (p) => p.latitude < 6.3 && p.longitude > 1.1 && p.longitude < 1.3;

export function TogoMap({ pins, selected, onSelect, onOpen, height = 560, extra }) {
  const sel = pins.find((p) => p.id === selected);
  return (
    <div style={{ position: 'relative', background: '#eef2f6', borderRadius: 10, height, overflow: 'hidden' }}>
      <svg viewBox={`0 0 ${VB_W} ${VB_H}`} width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        <path d={SEA_PATH} fill="#dce9f6" />
        <path d={toPath(COAST, false)} fill="none" stroke="#b9cfe6" strokeWidth="1" />
        {NEIGHBOURS.map((line, i) => (
          <path key={i} d={toPath(line, false)} fill="none" stroke="#c4d3e4" strokeWidth="1" strokeDasharray="3 3" />
        ))}
        <path d={TOGO_PATH} fill="#f8fafc" stroke="#8fb0d6" strokeWidth="1.3" strokeLinejoin="round" />
        {extra}
        {pins.map((p) => {
          const [x, y] = px([Number(p.latitude), Number(p.longitude)]);
          const active = p.id === selected;
          const showName = !LOME(p) || p.shortName?.startsWith('Agoè');
          return (
            <g key={p.id} style={{ cursor: 'pointer' }} onClick={() => onSelect?.(p.id)} onDoubleClick={() => onOpen?.(p.id)}>
              <circle cx={x} cy={y} r={active ? 8.5 : 6.5} fill="#fff" stroke={active ? '#1b66b8' : '#dfe6ee'} strokeWidth={active ? 2 : 1} />
              <circle cx={x} cy={y} r="3.4" fill={PIN_COLOR[p.status] || '#94a3b8'} />
              {showName && (
                <text x={x + 9} y={y + 3.8} fontSize="10.5" fontWeight="500" fill="#0f172a">
                  {LOME(p) ? 'Lomé' : p.shortName}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {sel && (
        <div
          className="card tr-click"
          onClick={() => onOpen?.(sel.id)}
          style={{ position: 'absolute', left: 16, top: '45%', width: 210, padding: '14px 16px', boxShadow: 'var(--shadow-lg)' }}
        >
          <div className="strong">{sel.name}</div>
          <div className="muted num" style={{ fontSize: 12, marginTop: 4 }}>
            {Number(sel.latitude).toFixed(4)}° N · {Number(sel.longitude).toFixed(4)}° E
          </div>
          <Badge tone={sel.status === 'EN_LIGNE' ? 'green' : sel.status === 'INSTABLE' ? 'orange' : 'red'} dot style={{ marginTop: 10 }}>
            {sel.statusLabel}
            {sel.status !== 'EN_LIGNE' && sel.minutesSinceSeen != null ? ` · ${sel.minutesSinceSeen} min` : ''}
          </Badge>
          <div className="muted" style={{ fontSize: 12.5, marginTop: 10, lineHeight: 1.45 }}>
            {sel.readerConnected ? `Lecteur de cartes connecté (${sel.readerPort || '—'})` : `Lecteur de cartes déconnecté (${sel.readerPort || '—'})`}
          </div>
        </div>
      )}
    </div>
  );
}

/** Création ou modification d'une station (Admin). */
export function StationFormModal({ station, onClose, onSaved }) {
  const notify = useToast();
  const { can } = useSession();
  const clients = useApi(can('CLIENTS_LICENCES') ? '/clients' : null);
  const [f, setF] = useState(() => ({
    name: station?.name || 'Zener ',
    shortName: station?.shortName || '',
    type: station?.type || 'SUCCURSALE',
    kind: station?.kind || 'FIXE',
    clientId: station?.clientId || '',
    country: station?.country || 'Togo',
    zone: station?.zone || '',
    address: station?.address || '',
    latitude: station?.latitude ?? '',
    longitude: station?.longitude ?? '',
    hubPorts: station?.hubPorts ?? 12,
    readerPort: station?.readerPort || 'COM9',
    serialPort: station?.serialPort || 'COM3',
    serialBaud: station?.serialBaud ?? 9600,
  }));
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const [key, setKey] = useState(null);

  const submit = async () => {
    const body = {
      ...f,
      clientId: f.clientId ? Number(f.clientId) : null,
      latitude: f.latitude === '' ? null : Number(f.latitude),
      longitude: f.longitude === '' ? null : Number(f.longitude),
      hubPorts: Number(f.hubPorts) || 12,
      serialBaud: f.serialBaud ? Number(f.serialBaud) : null,
      managerId: station?.managerId ?? null,
      openedOn: station?.openedOn ?? null,
    };
    const res = station ? await put(`/stations/${station.id}`, body) : await post('/stations', body);
    notify(station ? 'Station modifiée' : 'Station créée');
    if (!station && res.apiKey) setKey({ code: res.code, key: res.apiKey, id: res.id });
    onSaved?.(res);
  };

  if (key) {
    return (
      <Modal title="Clé du PC station" onClose={onClose} footer={<button className="btn btn-primary" onClick={onClose}>J’ai noté la clé</button>}>
        <p className="muted">La clé ne sera plus affichée. Saisissez-la dans le programme du PC station.</p>
        <div className="kv"><span className="k">Code station</span><span className="v num">{key.code}</span></div>
        <div className="kv"><span className="k">Clé</span><span className="v num" style={{ wordBreak: 'break-all' }}>{key.key}</span></div>
      </Modal>
    );
  }

  return (
    <FormModal title={station ? 'Modifier la station' : 'Nouvelle station'} onClose={onClose} onSubmit={submit} wide
      submitLabel={station ? 'Enregistrer' : 'Créer la station'}>
      <div className="form-grid">
        <Field label="Nom"><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Nom court"><TextInput value={f.shortName} onChange={set('shortName')} required /></Field>
        <Field label="Type">
          <SelectInput value={f.type} onChange={set('type')} options={[{ value: 'SUCCURSALE', label: 'Succursale' }, { value: 'GERANCE_LIBRE', label: 'Gérance libre' }]} />
        </Field>
        <Field label="Client attribué">
          <SelectInput value={f.clientId} onChange={set('clientId')} placeholder="Aucun"
            options={(clients.data || []).map((c) => ({ value: c.id, label: c.name }))} />
        </Field>
        <Field label="Station">
          <SelectInput value={f.kind} onChange={set('kind')} options={[{ value: 'FIXE', label: 'Fixe' }, { value: 'MOBILE', label: 'Mobile (position GPS)' }]} />
        </Field>
        <Field label="Pays">
          <SelectInput value={f.country} onChange={set('country')} options={['Togo', 'Bénin', 'Burkina Faso']} />
        </Field>
        <Field label="Zone / préfecture"><TextInput value={f.zone} onChange={set('zone')} /></Field>
        <Field label="Adresse"><TextInput value={f.address} onChange={set('address')} /></Field>
        <Field label="Latitude"><TextInput value={f.latitude} onChange={set('latitude')} type="number" step="0.0001" /></Field>
        <Field label="Longitude"><TextInput value={f.longitude} onChange={set('longitude')} type="number" step="0.0001" /></Field>
        <Field label="Ports du hub"><TextInput value={f.hubPorts} onChange={set('hubPorts')} type="number" min="1" /></Field>
        <Field label="Port du lecteur de cartes"><TextInput value={f.readerPort} onChange={set('readerPort')} /></Field>
        <Field label="Port série du hub"><TextInput value={f.serialPort} onChange={set('serialPort')} /></Field>
        <Field label="Débit (bauds)"><TextInput value={f.serialBaud} onChange={set('serialBaud')} type="number" /></Field>
      </div>
    </FormModal>
  );
}

const TYPES = [{ value: 'SUCCURSALE', label: 'Succursale' }, { value: 'GERANCE_LIBRE', label: 'Gérance libre' }];
const MODES = [{ value: 'CONVENTIONNEL', label: 'Conventionnel' }, { value: 'AUTONOME', label: 'Autonome' }];
const STATUSES = [{ value: 'EN_LIGNE', label: 'En ligne' }, { value: 'INSTABLE', label: 'Instable' }, { value: 'HORS_LIGNE', label: 'Hors ligne' }];

export default function Stations() {
  const navigate = useNavigate();
  const { can } = useSession();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState('');
  const [filters, setFilters] = useState({ type: '', mode: '', status: '', country: '' });
  const [creating, setCreating] = useState(params.get('new') === '1');
  const [selected, setSelected] = useState(null);

  const summary = useApi('/stations/summary');
  const list = useApi(`/stations${qs({ ...filters })}`);
  const map = useApi('/stations/map');
  const reloadAll = () => {
    summary.reload();
    list.reload();
    map.reload();
  };
  useLiveReload(reloadAll, ['station', 'sale'], 5000);

  useEffect(() => {
    if (selected === null && map.data?.length) {
      const first = map.data.find((p) => p.status !== 'EN_LIGNE') || map.data[0];
      setSelected(first.id);
    }
  }, [map.data, selected]);

  const rows = useMemo(
    () => (list.data || []).filter((s) => `${s.name} ${s.clientName || ''} ${s.zone || ''}`.toLowerCase().includes(q.toLowerCase())),
    [list.data, q],
  );
  const s = summary.data;
  const countries = s?.byCountry?.map((c) => `${c.country} ${c.count}`).join(' · ');
  const setFilter = (k) => (v) => setFilters((x) => ({ ...x, [k]: v }));

  return (
    <>
      <PageHeader
        title="Stations"
        subtitle="Création, attribution aux clients et suivi de chaque station du réseau"
        actions={
          <>
            <Button icon={Download} disabled={!rows.length} onClick={() => exportCsv('stations.csv', [
              { label: 'Code', value: 'code' }, { label: 'Station', value: 'name' }, { label: 'Type', value: 'typeLabel' },
              { label: 'Client', value: 'clientName' }, { label: 'Zone', value: 'zone' }, { label: 'Statut', value: 'statusLabel' },
              { label: 'Mode', value: 'modeLabel' }, { label: 'Recette du jour (F)', value: 'revenueToday' },
            ], rows)}>Exporter</Button>
            {can('STATIONS_GERER') && <Button variant="primary" icon={Plus} onClick={() => setCreating(true)}>Nouvelle station</Button>}
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Fuel} label="Stations" value={s?.total ?? '…'} foot={countries || '—'} />
        <Kpi icon={Wifi} tone="green" label="En ligne" value={s?.online ?? '…'}
          foot={s ? <><Badge tone="red" dot>{s.offline} hors ligne</Badge> {s.unstable ? `· ${s.unstable} instable` : ''}</> : ''} />
        <Kpi icon={ArrowLeftRight} tone="navy" label="Mode autonome" value={s?.autonomous ?? '…'} foot={s ? `dont ${s.autonomousBySchedule} par plage horaire` : ''} />
        <Kpi icon={Building2} tone="orange" label="Succursales / Gérance" value={s ? `${s.succursales} / ${s.gerances}` : '…'}
          foot={s ? `${s.managerClients} client${s.managerClients > 1 ? 's' : ''} gérant${s.managerClients > 1 ? 's' : ''}` : ''} />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 400px', alignItems: 'start' }}>
        <Card>
          <div className="filters" style={{ flexWrap: 'nowrap' }}>
            <SearchInput icon={Search} placeholder="Rechercher une station…" style={{ flex: 1, minWidth: 150 }} value={q} onChange={(e) => setQ(e.target.value)} />
            <Select icon={Globe} value={filters.country} onChange={setFilter('country')} placeholder="Pays"
              options={(s?.byCountry || []).map((c) => c.country)} />
            <Select icon={Building2} value={filters.type} onChange={setFilter('type')} placeholder="Type" options={TYPES} />
            <Select icon={ArrowLeftRight} value={filters.mode} onChange={setFilter('mode')} placeholder="Mode" options={MODES} />
            <Select icon={CircleDot} value={filters.status} onChange={setFilter('status')} placeholder="Statut" options={STATUSES} />
          </div>
          <Async state={list}>
            {() => (rows.length === 0 ? <Empty>Aucune station ne correspond</Empty> : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Station</th><th>Client attribué</th><th>Statut</th><th>Mode</th><th className="r">Recette (F)</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((st) => (
                    <tr key={st.id} className="tr-click" onClick={() => navigate(`/stations/${st.id}`)} onMouseEnter={() => setSelected(st.id)}>
                      <td>
                        <div className="row" style={{ gap: 12 }}>
                          <IcoBox icon={Fuel} tone={st.type === 'SUCCURSALE' ? 'blue' : 'purple'} size={34} />
                          <div>
                            <div className="cell-title">{st.name}</div>
                            <div className="cell-sub">{st.typeLabel} · {st.zone}{st.kind === 'MOBILE' ? ' · mobile' : ''}</div>
                          </div>
                        </div>
                      </td>
                      <td>{st.clientName || <span className="muted">Non attribuée</span>}</td>
                      <td><StatusBadge status={st.statusLabel} /></td>
                      <td><Badge tone={st.mode === 'AUTONOME' ? 'navy' : 'gray'}>{st.modeLabel}</Badge></td>
                      <td className="r num strong">{st.revenueToday ? fcfa(st.revenueToday) : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ))}
          </Async>
        </Card>

        <Card>
          <div className="card-head">
            <div>
              <div className="card-title">Géolocalisation</div>
              <div className="card-sub">{map.data ? `${map.data.length} stations géolocalisées` : '…'}</div>
            </div>
            {can('STATIONS_GERER') && (
              <button className="icon-btn" style={{ width: 34, height: 34 }} title="Nouvelle station" onClick={() => setCreating(true)}>
                <Plus size={17} />
              </button>
            )}
          </div>
          <div style={{ padding: '18px 20px 0' }}>
            <TogoMap pins={map.data || []} selected={selected} onSelect={setSelected} onOpen={(id) => navigate(`/stations/${id}`)} />
          </div>
          <div className="row" style={{ gap: 20, padding: '16px 20px', borderTop: '1px solid var(--border-2)', marginTop: 4 }}>
            {[['En ligne', '#3f9a2c'], ['Instable', '#f29e1f'], ['Hors ligne', '#e5484d']].map(([l, c]) => (
              <span key={l} className="legend"><i style={{ background: c, borderRadius: '50%' }} /> {l}</span>
            ))}
            <span className="muted" style={{ marginLeft: 'auto', fontSize: 12.5 }}>Double-clic : ouvrir</span>
          </div>
        </Card>
      </div>

      {creating && (
        <StationFormModal
          onClose={() => {
            setCreating(false);
            if (params.get('new')) setParams({});
          }}
          onSaved={reloadAll}
        />
      )}
    </>
  );
}

