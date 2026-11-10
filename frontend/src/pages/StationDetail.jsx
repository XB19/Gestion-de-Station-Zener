import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ChevronRight, ArrowLeftRight, Pencil, FileText, Plus, Users, CreditCard, Check, MapPin, Fuel, Cpu, Cable,
  TriangleAlert, Sparkles, Trash2,
} from 'lucide-react';
import { PageHeader, Card, Badge, StatusBadge, Button, Toggle, KV, Avatar, Async, Empty } from '../components/ui.jsx';
import { GroupedBars, hourlyBars } from '../components/charts.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useInterval, useLiveReload } from '../api/hooks.js';
import { useLiveEvent } from '../api/live.jsx';
import { useSession } from '../api/session.jsx';
import { del, fileUrl, post, put } from '../api/client.js';
import { ago, avatarColor, date, fcfa, hhmm, isoDay, liters, longDate, num, time } from '../lib/format.js';
import { StationFormModal } from './Stations.jsx';

const PUMP_STATE = { LIBRE: 'Libre', DISTRIBUE: 'Distribue', HORS_SERVICE: 'Hors service' };
const TANK_COLORS = { SUPER: '#2a75c4', GASOIL: '#5aa935', PETROLE: '#f2a32a' };
const DAY_LETTERS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];

function ModeOption({ active, icon: Icon, title, sub, onClick, disabled }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
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

const minutes = (t) => {
  const [h, m] = String(t).split(':').map(Number);
  return h * 60 + m;
};

/** Frise de la journée : plages conventionnelles du jour, autonome en dehors, curseur sur l'heure. */
function Timeline({ windows }) {
  const now = new Date();
  const dow = ((now.getDay() + 6) % 7) + 1; // 1 = lundi
  const today = windows.filter((w) => (w.days || []).includes(dow));
  const segs = [];
  today.forEach((w) => {
    const a = minutes(w.conventionalFrom);
    const b = minutes(w.conventionalTo);
    if (a < b) segs.push([a, b]);
    else if (a > b) segs.push([0, b], [a, 1440]);
    else segs.push([0, 1440]);
  });
  segs.sort((x, y) => x[0] - y[0]);
  const parts = [];
  let cur = 0;
  segs.forEach(([a, b]) => {
    if (a > cur) parts.push(['AUTO', cur, a]);
    parts.push(['CONV', Math.max(a, cur), b]);
    cur = Math.max(cur, b);
  });
  if (cur < 1440) parts.push(['AUTO', cur, 1440]);
  const nowPct = ((now.getHours() * 60 + now.getMinutes()) / 1440) * 100;
  return (
    <div style={{ marginTop: 22 }}>
      <div className="row between muted num" style={{ fontSize: 12 }}>
        {['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '24:00'].map((h) => <span key={h}>{h}</span>)}
      </div>
      <div style={{ position: 'relative', marginTop: 8, height: 30, borderRadius: 7, display: 'flex', overflow: 'visible' }}>
        {parts.map(([kind, a, b], i) => {
          const w = ((b - a) / 1440) * 100;
          return (
            <div
              key={i}
              style={{
                width: `${w}%`, background: kind === 'CONV' ? 'var(--primary)' : 'var(--navy-700)', color: '#fff',
                display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600, overflow: 'hidden', whiteSpace: 'nowrap',
                borderRadius: `${i === 0 ? 7 : 0}px ${i === parts.length - 1 ? 7 : 0}px ${i === parts.length - 1 ? 7 : 0}px ${i === 0 ? 7 : 0}px`,
              }}
            >
              {w > 14 ? (kind === 'CONV' ? 'Conventionnel' : 'Autonome') : ''}
            </div>
          );
        })}
        <div style={{ position: 'absolute', left: `${nowPct}%`, top: -8, bottom: -8, width: 2, background: 'var(--red-500)' }} />
        <div className="num" style={{ position: 'absolute', left: `${nowPct}%`, top: 40, transform: 'translateX(-50%)', color: 'var(--red)', fontSize: 12, fontWeight: 600 }}>
          {time(now)}
        </div>
      </div>
    </div>
  );
}

function WindowModal({ stationId, window: w, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    label: w?.label || '',
    days: w?.days || [1, 2, 3, 4, 5],
    holidays: !!w?.holidays,
    conventionalFrom: hhmm(w?.conventionalFrom || '06:00'),
    conventionalTo: hhmm(w?.conventionalTo || '22:00'),
  });
  const toggleDay = (d) => setF((x) => ({ ...x, days: x.days.includes(d) ? x.days.filter((y) => y !== d) : [...x.days, d].sort() }));
  return (
    <FormModal
      title={w ? 'Modifier la plage horaire' : 'Nouvelle plage horaire'}
      onClose={onClose}
      onSubmit={async () => {
        const body = { ...f, conventionalFrom: `${f.conventionalFrom}:00`, conventionalTo: `${f.conventionalTo}:00` };
        if (w) await put(`/stations/${stationId}/windows/${w.id}`, body);
        else await post(`/stations/${stationId}/windows`, body);
        notify('Plage horaire enregistrée');
        onSaved();
      }}
    >
      <div className="form-grid">
        <Field label="Libellé" full><TextInput value={f.label} onChange={(v) => setF({ ...f, label: v })} required placeholder="Lundi – Vendredi" /></Field>
        <Field label="Jours" full>
          <div className="row" style={{ gap: 8 }}>
            {DAY_LETTERS.map((l, i) => (
              <button type="button" key={i} onClick={() => toggleDay(i + 1)}
                style={{ width: 38, height: 34, borderRadius: 7, border: 0, fontWeight: 600,
                  background: f.days.includes(i + 1) ? 'var(--primary)' : 'var(--chip)', color: f.days.includes(i + 1) ? '#fff' : 'var(--muted-2)' }}>
                {l}
              </button>
            ))}
            <label className="row" style={{ gap: 8, marginLeft: 12 }}>
              <input type="checkbox" checked={f.holidays} onChange={(e) => setF({ ...f, holidays: e.target.checked })} /> Jours fériés
            </label>
          </div>
        </Field>
        <Field label="Conventionnel de"><TextInput type="time" value={f.conventionalFrom} onChange={(v) => setF({ ...f, conventionalFrom: v })} required /></Field>
        <Field label="à"><TextInput type="time" value={f.conventionalTo} onChange={(v) => setF({ ...f, conventionalTo: v })} required /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>En dehors de cette plage, la station passe en mode autonome (paiement par carte uniquement).</div>
    </FormModal>
  );
}

/** Pistolet : ajout ou modification complète (Admin), simple mise hors service (exploitation). */
function PumpModal({ stationId, pump, full, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    code: pump?.code || '', port: pump?.port || '', productCode: pump?.productCode || 'SUPER',
    state: pump ? (pump.state === 'DISTRIBUE' ? 'LIBRE' : pump.state) : 'LIBRE',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const remove = async () => {
    if (!window.confirm(`Supprimer le pistolet ${pump.code} ?`)) return;
    try {
      await del(`/stations/${stationId}/pumps/${pump.id}`);
      notify('Pistolet supprimé');
      onSaved();
      onClose();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <FormModal
      title={pump ? `Pistolet ${pump.code} · ${pump.port}` : 'Nouveau pistolet'}
      onClose={onClose}
      submitLabel={pump ? 'Enregistrer' : 'Ajouter'}
      onSubmit={async () => {
        if (pump) await put(`/stations/${stationId}/pumps/${pump.id}`, f);
        else await post(`/stations/${stationId}/pumps`, f);
        notify(pump ? `Pistolet ${f.code} : ${PUMP_STATE[f.state].toLowerCase()}` : `Pistolet ${f.code} ajouté sur ${f.port}`);
        onSaved();
      }}
    >
      <div className="form-grid">
        {full && (
          <>
            <Field label="Code"><TextInput value={f.code} onChange={set('code')} required placeholder="P9" /></Field>
            <Field label="Port du hub"><TextInput value={f.port} onChange={set('port')} required placeholder="COM10" /></Field>
            <Field label="Carburant">
              <SelectInput value={f.productCode} onChange={set('productCode')} options={[{ value: 'SUPER', label: 'Super' }, { value: 'GASOIL', label: 'Gasoil' }, { value: 'PETROLE', label: 'Pétrole' }]} />
            </Field>
          </>
        )}
        <Field label="État">
          <SelectInput value={f.state} onChange={set('state')} options={[{ value: 'LIBRE', label: 'En service' }, { value: 'HORS_SERVICE', label: 'Hors service' }]} />
        </Field>
      </div>
      {pump && <div className="muted" style={{ marginTop: 12, fontSize: 13 }}>{pump.productName} · index {num(pump.indexTotal, 1)} L</div>}
      {pump && full && <button type="button" className="btn btn-sm btn-danger" style={{ marginTop: 14 }} onClick={remove}><Trash2 size={14} /> Supprimer le pistolet</button>}
    </FormModal>
  );
}

/** Cuve : capacité, seuil manuel (vide = seuil automatique), délai de livraison. */
function TankModal({ stationId, tank, onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({
    name: tank?.name || 'Cuve ', productCode: tank?.productCode || 'SUPER', capacity: tank?.capacity ?? 30000, volume: tank ? undefined : 0,
    thresholdManual: tank?.thresholdManual ?? '', leadDays: tank?.leadDays ?? 3,
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={tank ? tank.name : 'Nouvelle cuve'} submitLabel={tank ? 'Enregistrer' : 'Ajouter'} onClose={onClose} onSubmit={async () => {
      const body = { ...f, capacity: Number(f.capacity), volume: f.volume === undefined ? null : Number(f.volume || 0),
        thresholdManual: f.thresholdManual === '' ? null : Number(f.thresholdManual), leadDays: Number(f.leadDays) };
      if (tank) await put(`/stations/${stationId}/tanks/${tank.id}`, body);
      else await post(`/stations/${stationId}/tanks`, body);
      notify(tank ? 'Cuve modifiée' : 'Cuve ajoutée');
      onSaved();
    }}>
      <div className="form-grid">
        <Field label="Nom"><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Carburant">
          {tank ? <TextInput value={tank.productName} onChange={() => {}} disabled />
            : <SelectInput value={f.productCode} onChange={set('productCode')} options={[{ value: 'SUPER', label: 'Super' }, { value: 'GASOIL', label: 'Gasoil' }, { value: 'PETROLE', label: 'Pétrole' }]} />}
        </Field>
        <Field label="Capacité (L)"><TextInput type="number" min="1" value={f.capacity} onChange={set('capacity')} required /></Field>
        {!tank && <Field label="Volume actuel (L)"><TextInput type="number" min="0" value={f.volume} onChange={set('volume')} /></Field>}
        <Field label="Seuil manuel (L)" hint={tank?.thresholdAuto ? `Vide = seuil automatique (${num(tank.thresholdAuto)} L)` : 'Vide = seuil automatique'}>
          <TextInput type="number" min="0" value={f.thresholdManual} onChange={set('thresholdManual')} />
        </Field>
        <Field label="Délai de livraison (jours)"><TextInput type="number" min="0" value={f.leadDays} onChange={set('leadDays')} /></Field>
      </div>
    </FormModal>
  );
}

/** Postes types de la station (Matin, Soir, Nuit…). */
function ShiftConfigs({ station, editable, onChanged }) {
  const notify = useToast();
  const [f, setF] = useState({ name: '', startTime: '06:00', endTime: '14:00' });
  const add = async (e) => {
    e.preventDefault();
    try {
      await post(`/stations/${station.id}/shift-configs`, { name: f.name, startTime: `${f.startTime}:00`, endTime: `${f.endTime}:00` });
      notify('Poste type ajouté');
      setF({ ...f, name: '' });
      onChanged();
    } catch (err) {
      notify(err.message, 'error');
    }
  };
  const remove = async (c) => {
    try {
      await del(`/stations/${station.id}/shift-configs/${c.id}`);
      notify('Poste type supprimé');
      onChanged();
    } catch (err) {
      notify(err.message, 'error');
    }
  };
  return (
    <div style={{ marginTop: 14, borderTop: '1px solid var(--border-2)', paddingTop: 12 }}>
      <div className="section-label">Postes types</div>
      {(station.shiftConfigs || []).map((c) => (
        <div key={c.id} className="kv">
          <span className="k">{c.name}</span>
          <span className="v row num" style={{ gap: 8 }}>
            {hhmm(c.startTime)} → {hhmm(c.endTime)}
            {editable && <button className="link-more" title="Supprimer" onClick={() => remove(c)}><Trash2 size={14} /></button>}
          </span>
        </div>
      ))}
      {editable && (
        <form className="row" style={{ gap: 6, marginTop: 8 }} onSubmit={add}>
          <label className="input" style={{ flex: 1 }}><input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="Nom" required /></label>
          <label className="input" style={{ width: 92 }}><input type="time" value={f.startTime} onChange={(e) => setF({ ...f, startTime: e.target.value })} /></label>
          <label className="input" style={{ width: 92 }}><input type="time" value={f.endTime} onChange={(e) => setF({ ...f, endTime: e.target.value })} /></label>
          <button className="icon-btn" type="submit" title="Ajouter"><Plus size={16} /></button>
        </form>
      )}
    </div>
  );
}

export default function StationDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const notify = useToast();
  const { can } = useSession();
  const st = useApi(`/stations/${id}`);
  const [modal, setModal] = useState(null);
  const [, tick] = useState(0);
  useLiveReload(st.reload, ['sale', 'station', 'shift', 'alert'], 3000);
  useInterval(() => tick((n) => n + 1), 5000);
  // état des pistolets mis à jour immédiatement par le flux temps réel
  useLiveEvent(['pump'], (_, e) => {
    if (!st.data || e?.stationId !== st.data.id) return;
    st.setData({
      ...st.data,
      lastSeenAt: new Date().toISOString(),
      pumps: st.data.pumps.map((p) => (p.id === e.pumpId ? { ...p, state: e.state, currentVolume: e.currentVolume } : p)),
    });
  });

  const exploit = can('STATION_EXPLOITER');
  const switchMode = async (mode) => {
    if (!exploit || st.data.mode === mode) return;
    try {
      await post(`/stations/${id}/mode`, { mode });
      notify(`Station en mode ${mode === 'AUTONOME' ? 'autonome' : 'conventionnel'} (bascule manuelle)`);
      st.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const setAuto = async (enabled) => {
    try {
      await put(`/stations/${id}/auto-switch`, { enabled });
      notify(enabled ? 'Bascule automatique activée' : 'Bascule automatique désactivée');
      st.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const removeWindow = async (w) => {
    try {
      await del(`/stations/${id}/windows/${w.id}`);
      notify('Plage supprimée');
      st.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  const openReport = async () => {
    try {
      await post(`/reports/daily/generate?stationId=${id}`);
      navigate(`/rapports?station=${id}&day=${isoDay()}`);
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <Async state={st} minHeight={400}>
      {(s) => {
        const autonomous = s.mode === 'AUTONOME';
        const bars = hourlyBars(s.litersByHour);
        const low = (s.tanks || []).filter((t) => t.belowThreshold);
        const tankAlert = (s.alerts || []).find((a) => a.tankId);
        const shift = s.team?.find((m) => m.shiftName);
        return (
          <>
            <PageHeader
              breadcrumb={
                <div className="breadcrumb">
                  <Link to="/stations">Stations</Link> <ChevronRight size={14} /> <span className="cur">{s.shortName}</span>
                </div>
              }
              title={s.name}
              tags={
                <>
                  <StatusBadge status={s.statusLabel} />
                  <Badge tone="blue">{s.typeLabel}</Badge>
                  <Badge tone="gray">Mode {s.modeLabel.toLowerCase()}</Badge>
                  {s.kind === 'MOBILE' && <Badge tone="purple">Station mobile</Badge>}
                  <span className="row muted" style={{ gap: 6, marginLeft: 6 }}><MapPin size={15} /> {s.address || s.zone} · {s.country}</span>
                </>
              }
              actions={
                <>
                  {exploit && (
                    <Button icon={ArrowLeftRight} onClick={() => switchMode(autonomous ? 'CONVENTIONNEL' : 'AUTONOME')}>
                      Basculer en {autonomous ? 'conventionnel' : 'autonome'}
                    </Button>
                  )}
                  {can('STATIONS_GERER') && <Button icon={Pencil} onClick={() => setModal({ type: 'edit' })}>Modifier</Button>}
                  {can('RAPPORTS') && <Button variant="primary" icon={FileText} onClick={openReport}>Rapport du jour</Button>}
                </>
              }
            />

            <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
              <div className="stack">
                <Card className="card-pad">
                  <div className="row between">
                    <div className="card-title">Mode de fonctionnement</div>
                    {exploit && <Button size="sm" icon={Plus} onClick={() => setModal({ type: 'window' })}>Ajouter une plage</Button>}
                  </div>
                  <div className="row" style={{ gap: 14, marginTop: 18 }}>
                    <ModeOption active={!autonomous} icon={Users} title="Conventionnel" sub="Pompiste présent, ventes à la pompe"
                      onClick={() => switchMode('CONVENTIONNEL')} disabled={!exploit} />
                    <ModeOption active={autonomous} icon={CreditCard} title="Autonome" sub="Libre-service, permissions des cartes"
                      onClick={() => switchMode('AUTONOME')} disabled={!exploit} />
                  </div>
                  <Timeline windows={s.windows || []} />
                  <div className="row" style={{ gap: 16, background: 'var(--surface-2)', borderRadius: 10, padding: '14px 18px', marginTop: 34 }}>
                    <Toggle on={s.autoSwitch} onChange={setAuto} disabled={!exploit} />
                    <div>
                      <div className="strong">Bascule automatique selon les plages horaires</div>
                      <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
                        {s.autoSwitch && s.nextSwitchAt
                          ? `Prochaine bascule : ${s.nextSwitchMode === 'AUTONOME' ? 'autonome' : 'conventionnel'} à ${time(s.nextSwitchAt)}`
                          : 'Bascule automatique désactivée'}
                        {s.modeOverrideUntil ? ` · bascule manuelle jusqu’à ${time(s.modeOverrideUntil)}` : ' · bascule manuelle possible à tout moment'}
                      </div>
                    </div>
                  </div>
                  <div style={{ marginTop: 6 }}>
                    {(s.windows || []).length === 0 && <Empty>Aucune plage : le mode ne change que manuellement</Empty>}
                    {(s.windows || []).map((w) => (
                      <div key={w.id} className="row" style={{ padding: '13px 0', borderBottom: '1px solid var(--border-2)' }}>
                        <span className="strong" style={{ flex: '0 1 220px' }}>{w.label}{w.holidays && !/férié/i.test(w.label) ? ' · fériés' : ''}</span>
                        <Badge tone="blue" className="num"><Users size={13} /> {hhmm(w.conventionalFrom)} → {hhmm(w.conventionalTo)}</Badge>
                        <Badge tone="blue" className="num" style={{ marginLeft: 8 }}><CreditCard size={13} /> {hhmm(w.conventionalTo)} → {hhmm(w.conventionalFrom)}</Badge>
                        {exploit && (
                          <span className="row" style={{ marginLeft: 'auto', gap: 4 }}>
                            <button className="link-more" title="Modifier" onClick={() => setModal({ type: 'window', window: w })}><Pencil size={16} /></button>
                            <button className="link-more" title="Supprimer" onClick={() => removeWindow(w)}><Trash2 size={16} /></button>
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </Card>

                <Card className="card-pad">
                  <div className="row between" style={{ alignItems: 'flex-start' }}>
                    <div>
                      <div className="card-title">Hub & pistolets</div>
                      <div className="card-sub">Un appareil par port{exploit ? ' · cliquez sur un pistolet pour le modifier' : ''}</div>
                    </div>
                    <span className="row" style={{ gap: 10 }}>
                      <span className="muted" style={{ fontSize: 13 }}>Flux binaire lu par le PC station · {s.lastSeenAt ? ago(s.lastSeenAt) : 'jamais'}</span>
                      {can('STATIONS_GERER') && <Button size="sm" icon={Plus} onClick={() => setModal({ type: 'pump' })}>Pistolet</Button>}
                    </span>
                  </div>
                  <div className="grid g4" style={{ gap: 12, marginTop: 18 }}>
                    {(s.pumps || []).map((p) => {
                      const active = p.state === 'DISTRIBUE';
                      const down = p.state === 'HORS_SERVICE';
                      return (
                        <div
                          key={p.id}
                          className={exploit ? 'tr-click' : ''}
                          onClick={() => exploit && setModal({ type: 'pump', pump: p })}
                          style={{
                            borderRadius: 10, padding: '14px 14px 12px', transition: 'all .2s',
                            border: active ? '1.5px solid var(--primary)' : down ? '1px solid #f3b4b4' : '1px solid var(--border)',
                            background: active ? '#f7faff' : '#fff',
                          }}
                        >
                          <div className="row between">
                            <span><span className="strong">{p.code}</span> <span className="muted">{p.port}</span></span>
                            <Fuel size={17} color={active ? 'var(--primary)' : down ? 'var(--red)' : 'var(--muted-2)'} />
                          </div>
                          <div style={{ marginTop: 10, color: 'var(--text-2)' }}>{p.productName}</div>
                          <div className="row between" style={{ marginTop: 10 }}>
                            <Badge tone={active ? 'blue' : down ? 'red' : 'gray'}>{PUMP_STATE[p.state]}</Badge>
                            {active && p.currentVolume != null && <span className="strong num">{num(p.currentVolume, 1)} L</span>}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                  <div className="grid g3" style={{ gap: 12, marginTop: 14 }}>
                    {[
                      [Cpu, 'Hub', `${s.hubPorts} ports · ${s.hubPortsUsed} utilisés`],
                      [CreditCard, 'Lecteur de cartes', `${s.readerPort || '—'} · ${s.readerConnected ? 'connecté' : 'déconnecté'}`],
                      [Cable, 'Port série', `${s.serialPort || '—'}${s.serialBaud ? ` · ${s.serialBaud} bauds` : ''}`],
                    ].map(([Icon, l, v]) => (
                      <div key={l} className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '12px 16px' }}>
                        <Icon size={20} color={l.startsWith('Lecteur') && !s.readerConnected ? 'var(--red)' : 'var(--primary)'} strokeWidth={1.7} />
                        <div>
                          <div className="muted" style={{ fontSize: 12.5 }}>{l}</div>
                          <div className="strong">{v}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                </Card>

                <Card className="card-pad">
                  <div className="card-title">Activité du jour</div>
                  <div className="card-sub">Litres distribués par tranche de deux heures</div>
                  <div className="row" style={{ gap: 40, marginTop: 20, alignItems: 'flex-end' }}>
                    {[['Volume du jour', liters(s.today.volume)], ['Recette', `${fcfa(s.today.revenue)} F`], ['Transactions', num(s.today.transactions)]].map(([l, v]) => (
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
                    <GroupedBars data={bars.data} labels={bars.labels} height={190} />
                  </div>
                </Card>
              </div>

              <div className="stack">
                <Card className="card-pad">
                  <div className="row between">
                    <div>
                      <div className="card-title">Cuves</div>
                      <div className="card-sub">- - - seuil ajusté automatiquement</div>
                    </div>
                    <span className="row" style={{ gap: 8 }}>
                      {can('STATIONS_GERER') && <Button size="sm" icon={Plus} onClick={() => setModal({ type: 'tank' })}>Cuve</Button>}
                      <Sparkles size={17} color="var(--muted)" />
                    </span>
                  </div>
                  <div className="grid g3" style={{ gap: 10, marginTop: 18 }}>
                    {(s.tanks || []).map((t) => (
                      <div key={t.id} style={{ textAlign: 'center' }} className={can('STOCKS') ? 'tr-click' : ''}
                        title={can('STOCKS') ? 'Modifier la cuve (capacité, seuil, délai)' : undefined}
                        onClick={() => can('STOCKS') && setModal({ type: 'tank', tank: t })}>
                        <div style={{ position: 'relative', height: 150, borderRadius: 10, background: '#eef2f6', overflow: 'hidden', border: '1px solid var(--border)' }}>
                          <div className="num" style={{ position: 'absolute', top: 12, left: 0, right: 0, fontSize: 18, fontWeight: 700, zIndex: 2 }}>{num(t.pct)} %</div>
                          <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: `${t.pct}%`, background: t.belowThreshold ? '#e5575c' : TANK_COLORS[t.productCode] }} />
                          {t.threshold != null && (
                            <div style={{ position: 'absolute', left: 0, right: 0, bottom: `${Math.min(100, t.thresholdPct)}%`, borderTop: '1.5px dashed rgba(15,23,42,.45)' }} />
                          )}
                        </div>
                        <div className="strong" style={{ marginTop: 10 }}>{t.productName}</div>
                        <div className="muted num" style={{ fontSize: 12, marginTop: 2 }}>{num(t.volume)} / {num(t.capacity)} L</div>
                      </div>
                    ))}
                  </div>
                  {low.map((t) => (
                    <div key={t.id} className="alert-box red" style={{ marginTop: 16 }}>
                      <TriangleAlert size={18} style={{ flexShrink: 0, marginTop: 1 }} />
                      <span>
                        <b>{t.productName} sous le seuil {t.thresholdManual ? 'manuel' : 'auto'} ({num(t.threshold)} L).</b>
                        {t.trendPct != null ? ` Tendance ${t.trendPct > 0 ? '+' : ''}${num(t.trendPct, 1)} % sur 7 jours` : ''}
                        {tankAlert?.emailedAt ? `, email envoyé au gérant à ${time(tankAlert.emailedAt)}.` : '.'}
                      </span>
                    </div>
                  ))}
                </Card>

                <Card className="card-pad">
                  <div className="row between">
                    <div className="card-title">Informations</div>
                    {can('STATIONS_GERER') && <button className="link-more" onClick={() => setModal({ type: 'edit' })}><Pencil size={16} /></button>}
                  </div>
                  <div style={{ marginTop: 8 }}>
                    <KV k="Code" v={s.code} />
                    <KV k="Client" v={s.clientName || '—'} />
                    <KV k="Type" v={s.typeLabel} />
                    <KV k="Ouverture" v={longDate(s.openedOn)} />
                    <KV k="Coordonnées" v={s.latitude != null ? `${Number(s.latitude).toFixed(4)}° N · ${Number(s.longitude).toFixed(4)}° E` : '—'} />
                    <KV k="Pays" v={s.country} />
                    <KV k="Gérant" v={s.managerName || '—'} />
                    <KV k="Licence" v={`${s.licenceStatus}${s.licenceEnd ? ` · ${date(s.licenceEnd)}` : ''}`} />
                  </div>
                </Card>

                <Card className="card-pad">
                  <div className="row between">
                    <div className="card-title">Équipe en poste</div>
                    <span className="muted" style={{ fontSize: 13 }}>
                      {shift ? `Poste ${shift.shiftName.toLowerCase()} ${hhmm(shift.startTime)}–${hhmm(shift.endTime)}` : ''}
                    </span>
                  </div>
                  <div style={{ marginTop: 8 }}>
                    {(s.team || []).length === 0 && <Empty>Personne en poste</Empty>}
                    {(s.team || []).map((m) => (
                      <div key={m.id} className="list-item" style={{ borderBottom: 0, padding: '10px 0' }}>
                        <Avatar initials={m.initials} color={avatarColor(m.id)} size={38} photo={fileUrl(m.photoUrl)} />
                        <div style={{ flex: 1 }}>
                          <div className="strong">{m.name}</div>
                          <div className="muted" style={{ fontSize: 13 }}>{m.role}{m.pumps ? ` · ${m.pumps.split(', ').length} pistolets` : ''} · depuis {time(m.startedAt)}</div>
                        </div>
                        <StatusBadge status={m.status === 'PAUSE' ? 'Pause' : 'En poste'} />
                      </div>
                    ))}
                  </div>
                  <ShiftConfigs station={s} editable={exploit} onChanged={st.reload} />
                </Card>
              </div>
            </div>

            {modal?.type === 'edit' && <StationFormModal station={s} onClose={() => setModal(null)} onSaved={st.reload} />}
            {modal?.type === 'window' && <WindowModal stationId={s.id} window={modal.window} onClose={() => setModal(null)} onSaved={st.reload} />}
            {modal?.type === 'pump' && <PumpModal stationId={s.id} pump={modal.pump} full={can('STATIONS_GERER')} onClose={() => setModal(null)} onSaved={st.reload} />}
            {modal?.type === 'tank' && <TankModal stationId={s.id} tank={modal.tank} onClose={() => setModal(null)} onSaved={st.reload} />}
          </>
        );
      }}
    </Async>
  );
}
