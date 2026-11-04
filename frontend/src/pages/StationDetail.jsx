import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ChevronRight, ArrowLeftRight, Pencil, FileText, Plus, Users, CreditCard, Check, MapPin, Fuel, Cpu, Cable,
  TriangleAlert, Sparkles,
} from 'lucide-react';
import { PageHeader, Card, CardHead, Badge, StatusBadge, Button, Toggle, KV, Avatar } from '../components/ui.jsx';
import { GroupedBars } from '../components/charts.jsx';
import { stationById } from '../data/stations.js';

const PUMPS = [
  { p: 'P1', com: 'COM1', fuel: 'Super', state: 'Distribue', l: '34,2 L' },
  { p: 'P2', com: 'COM2', fuel: 'Super', state: 'Libre' },
  { p: 'P3', com: 'COM3', fuel: 'Gasoil', state: 'Distribue', l: '58,0 L' },
  { p: 'P4', com: 'COM4', fuel: 'Gasoil', state: 'Libre' },
  { p: 'P5', com: 'COM5', fuel: 'Super', state: 'Libre' },
  { p: 'P6', com: 'COM6', fuel: 'Gasoil', state: 'Hors service' },
  { p: 'P7', com: 'COM7', fuel: 'Pétrole', state: 'Libre' },
  { p: 'P8', com: 'COM8', fuel: 'Super', state: 'Distribue', l: '12,8 L' },
];

const SCHEDULE = [
  ['Lundi – Vendredi', '06:00 → 22:00', '22:00 → 06:00'],
  ['Samedi', '06:00 → 23:00', '23:00 → 06:00'],
  ['Dimanche & fériés', '07:00 → 20:00', '20:00 → 07:00'],
];

const TANKS = [
  { name: 'Super', pct: 62, vol: '18 600 / 30 000 L', color: '#2a75c4', threshold: 25 },
  { name: 'Gasoil', pct: 21, vol: '6 300 / 30 000 L', color: '#e5575c', threshold: 28 },
  { name: 'Pétrole', pct: 48, vol: '4 800 / 10 000 L', color: '#f2a32a', threshold: 20 },
];

const TEAM = [
  { i: 'KA', c: 'blue', n: 'Kossi Amegah', r: 'Gérant', s: 'En poste' },
  { i: 'YA', c: 'green', n: 'Yao Agbeko', r: 'Chef de poste', s: 'En poste' },
  { i: 'KT', c: 'navy', n: 'Komlan Tetteh', r: 'Pompiste · P1–P4', s: 'En poste' },
  { i: 'EA', c: 'teal', n: 'Edem Akakpo', r: 'Pompiste · P5–P8', s: 'Pause' },
];

const ACTIVITY = [[42, 20], [92, 45], [84, 58], [116, 54], [104, 76], [88, 62], [138, 94], [68, 42], [26, 12]];

function ModeOption({ active, icon: Icon, title, sub, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        flex: 1, display: 'flex', alignItems: 'center', gap: 14, padding: '16px 18px', textAlign: 'left',
        borderRadius: 12, background: '#fff',
        border: active ? '2px solid var(--primary)' : '1px solid var(--border)',
      }}
    >
      <span className={`ico-box ${active ? '' : 'tone-gray'}`} style={active ? { background: 'var(--primary)', color: '#fff', width: 40, height: 40 } : { width: 40, height: 40 }}>
        <Icon size={19} strokeWidth={1.8} />
      </span>
      <span style={{ flex: 1 }}>
        <div className="strong" style={{ fontSize: 15 }}>{title}</div>
        <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{sub}</div>
      </span>
      {active ? (
        <span style={{ width: 24, height: 24, borderRadius: '50%', background: 'var(--primary)', color: '#fff', display: 'grid', placeItems: 'center' }}>
          <Check size={14} strokeWidth={3} />
        </span>
      ) : (
        <span style={{ width: 24, height: 24, borderRadius: '50%', border: '1.5px solid #cbd3dd' }} />
      )}
    </button>
  );
}

function Timeline() {
  // Autonome 00h–06h, Conventionnel 06h–22h, Autonome 22h–24h, curseur à 18:42
  const now = ((18 + 42 / 60) / 24) * 100;
  return (
    <div style={{ marginTop: 22 }}>
      <div className="row between muted num" style={{ fontSize: 12 }}>
        {['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '24:00'].map((h) => <span key={h}>{h}</span>)}
      </div>
      <div style={{ position: 'relative', marginTop: 8, height: 30, borderRadius: 7, overflow: 'visible', display: 'flex' }}>
        <div style={{ width: '25%', background: 'var(--navy-700)', borderRadius: '7px 0 0 7px', color: '#fff', display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600 }}>Autonome</div>
        <div style={{ width: '66.67%', background: 'var(--primary)', color: '#fff', display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600 }}>Conventionnel</div>
        <div style={{ flex: 1, background: 'var(--navy-700)', borderRadius: '0 7px 7px 0' }} />
        <div style={{ position: 'absolute', left: `${now}%`, top: -8, bottom: -8, width: 2, background: 'var(--red-500)' }} />
        <div className="num" style={{ position: 'absolute', left: `${now}%`, top: 40, transform: 'translateX(-50%)', color: 'var(--red)', fontSize: 12, fontWeight: 600 }}>18:42</div>
      </div>
    </div>
  );
}

export default function StationDetail() {
  const { id } = useParams();
  const station = stationById(id);
  const [mode, setMode] = useState('Conventionnel');

  return (
    <>
      <PageHeader
        breadcrumb={
          <div className="breadcrumb">
            <Link to="/stations">Stations</Link> <ChevronRight size={14} /> <span className="cur">{station.short}</span>
          </div>
        }
        title={station.name}
        tags={
          <>
            <StatusBadge status={station.status} />
            <Badge tone="blue">{station.type}</Badge>
            <Badge tone="gray">Mode {mode.toLowerCase()}</Badge>
            <span className="row muted" style={{ gap: 6, marginLeft: 6 }}><MapPin size={15} /> Agoè, Grand Lomé · Togo</span>
          </>
        }
        actions={
          <>
            <Button icon={ArrowLeftRight} onClick={() => setMode(mode === 'Autonome' ? 'Conventionnel' : 'Autonome')}>
              Basculer en {mode === 'Autonome' ? 'conventionnel' : 'autonome'}
            </Button>
            <Button icon={Pencil}>Modifier</Button>
            <Button variant="primary" icon={FileText}>Rapport du jour</Button>
          </>
        }
      />

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
        <div className="stack">
          {/* Mode de fonctionnement */}
          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Mode de fonctionnement</div>
              <Button size="sm" icon={Plus}>Ajouter une plage</Button>
            </div>
            <div className="row" style={{ gap: 14, marginTop: 18 }}>
              <ModeOption active={mode === 'Conventionnel'} icon={Users} title="Conventionnel" sub="Pompiste présent, ventes à la pompe" onClick={() => setMode('Conventionnel')} />
              <ModeOption active={mode === 'Autonome'} icon={CreditCard} title="Autonome" sub="Libre-service, permissions des cartes" onClick={() => setMode('Autonome')} />
            </div>
            <Timeline />
            <div className="row" style={{ gap: 16, background: 'var(--surface-2)', borderRadius: 10, padding: '14px 18px', marginTop: 34 }}>
              <Toggle />
              <div>
                <div className="strong">Bascule automatique selon les plages horaires</div>
                <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>Prochaine bascule : autonome à 22:00 · bascule manuelle possible à tout moment</div>
              </div>
            </div>
            <div style={{ marginTop: 6 }}>
              {SCHEDULE.map(([d, conv, auto]) => (
                <div key={d} className="row" style={{ padding: '13px 0', borderBottom: '1px solid var(--border-2)' }}>
                  <span className="strong" style={{ width: 220 }}>{d}</span>
                  <Badge tone="blue" className="num"><Users size={13} /> {conv}</Badge>
                  <Badge tone="blue" className="num"><CreditCard size={13} /> {auto}</Badge>
                  <button className="link-more" style={{ marginLeft: 'auto' }}><Pencil size={16} /></button>
                </div>
              ))}
            </div>
          </Card>

          {/* Hub & pistolets */}
          <Card className="card-pad">
            <div className="row between" style={{ alignItems: 'flex-start' }}>
              <div>
                <div className="card-title">Hub & pistolets</div>
                <div className="card-sub">Un appareil par port</div>
              </div>
              <span className="muted" style={{ fontSize: 13 }}>Flux binaire lu par le PC station · il y a 2 s</span>
            </div>
            <div className="grid g4" style={{ gap: 12, marginTop: 18 }}>
              {PUMPS.map((p) => {
                const active = p.state === 'Distribue';
                const down = p.state === 'Hors service';
                return (
                  <div
                    key={p.p}
                    style={{
                      borderRadius: 10, padding: '14px 14px 12px',
                      border: active ? '1.5px solid var(--primary)' : down ? '1px solid #f3b4b4' : '1px solid var(--border)',
                      background: active ? '#f7faff' : '#fff',
                    }}
                  >
                    <div className="row between">
                      <span><span className="strong">{p.p}</span> <span className="muted">{p.com}</span></span>
                      <Fuel size={17} color={active ? 'var(--primary)' : down ? 'var(--red)' : 'var(--muted-2)'} />
                    </div>
                    <div style={{ marginTop: 10, color: 'var(--text-2)' }}>{p.fuel}</div>
                    <div className="row between" style={{ marginTop: 10 }}>
                      <Badge tone={active ? 'blue' : down ? 'red' : 'gray'}>{p.state}</Badge>
                      {p.l && <span className="strong num">{p.l}</span>}
                    </div>
                  </div>
                );
              })}
            </div>
            <div className="grid g3" style={{ gap: 12, marginTop: 14 }}>
              {[
                [Cpu, 'Hub', '12 ports · 10 utilisés'],
                [CreditCard, 'Lecteur de cartes', 'COM9 · connecté'],
                [Cable, 'Port série', 'COM12 · 9600 bauds'],
              ].map(([Icon, l, v]) => (
                <div key={l} className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '12px 16px' }}>
                  <Icon size={20} color="var(--primary)" strokeWidth={1.7} />
                  <div>
                    <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                    <div className="strong">{v}</div>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          {/* Activité */}
          <Card className="card-pad">
            <div className="card-title">Activité du jour</div>
            <div className="card-sub">Litres distribués par heure</div>
            <div className="row" style={{ gap: 40, marginTop: 20, alignItems: 'flex-end' }}>
              {[['Volume du jour', '4 182 L'], ['Recette', '2 845 600 F'], ['Transactions', '214']].map(([l, v]) => (
                <div key={l}>
                  <div className="muted" style={{ fontSize: 13 }}>{l}</div>
                  <div className="num" style={{ fontSize: 21, fontWeight: 700, marginTop: 4 }}>{v}</div>
                </div>
              ))}
              <div className="row" style={{ marginLeft: 'auto', gap: 16 }}>
                <span className="legend"><i style={{ background: '#1b66b8' }} /> Super</span>
                <span className="legend"><i style={{ background: '#8cc63f' }} /> Gasoil</span>
              </div>
            </div>
            <div style={{ marginTop: 24 }}>
              <GroupedBars data={ACTIVITY} labels={['6h', '8h', '10h', '12h', '14h', '16h', '18h', '20h', '22h']} height={190} />
            </div>
          </Card>
        </div>

        {/* Colonne droite */}
        <div className="stack">
          <Card className="card-pad">
            <div className="row between">
              <div>
                <div className="card-title">Cuves</div>
                <div className="card-sub">- - - seuil ajusté automatiquement</div>
              </div>
              <Sparkles size={17} color="var(--muted)" />
            </div>
            <div className="grid g3" style={{ gap: 10, marginTop: 18 }}>
              {TANKS.map((t) => (
                <div key={t.name} style={{ textAlign: 'center' }}>
                  <div style={{ position: 'relative', height: 150, borderRadius: 10, background: '#eef2f6', overflow: 'hidden', border: '1px solid var(--border)' }}>
                    <div className="num" style={{ position: 'absolute', top: 12, left: 0, right: 0, fontSize: 18, fontWeight: 700, zIndex: 2 }}>{t.pct} %</div>
                    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: `${t.pct}%`, background: t.color }} />
                    <div style={{ position: 'absolute', left: 0, right: 0, bottom: `${t.threshold}%`, borderTop: '1.5px dashed rgba(15,23,42,.45)' }} />
                  </div>
                  <div className="strong" style={{ marginTop: 10 }}>{t.name}</div>
                  <div className="muted num" style={{ fontSize: 12, marginTop: 2 }}>{t.vol}</div>
                </div>
              ))}
            </div>
            <div className="alert-box red" style={{ marginTop: 16 }}>
              <TriangleAlert size={18} style={{ flexShrink: 0, marginTop: 1 }} />
              <span><b>Gasoil sous le seuil auto (8 400 L).</b> Tendance +9 % sur 7 jours, email envoyé au gérant à 18:31.</span>
            </div>
          </Card>

          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Informations</div>
              <button className="link-more"><Pencil size={16} /></button>
            </div>
            <div style={{ marginTop: 8 }}>
              <KV k="Client" v={station.client} />
              <KV k="Type" v={station.type} />
              <KV k="Création" v="14 mars 2019" />
              <KV k="Coordonnées" v="6.2350° N · 1.2050° E" />
              <KV k="Pays" v="Togo" />
              <KV k="Gérant" v="Kossi Amegah" />
              <KV k="Licence" v="Active · 31/12/2026" />
            </div>
          </Card>

          <Card className="card-pad">
            <div className="row between">
              <div className="card-title">Équipe en poste</div>
              <span className="muted" style={{ fontSize: 13 }}>Poste 14h–22h</span>
            </div>
            <div style={{ marginTop: 8 }}>
              {TEAM.map((m) => (
                <div key={m.n} className="list-item" style={{ borderBottom: 0, padding: '10px 0' }}>
                  <Avatar initials={m.i} color={m.c} size={38} />
                  <div style={{ flex: 1 }}>
                    <div className="strong">{m.n}</div>
                    <div className="muted" style={{ fontSize: 13 }}>{m.r}</div>
                  </div>
                  <StatusBadge status={m.s} />
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
