import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Banknote, Droplet, Wifi, ShieldCheck, Download, Plus, Fuel, Clock, ArrowRight, TriangleAlert, Mail } from 'lucide-react';
import {
  PageHeader, Card, CardHead, Kpi, Trend, Badge, StatusBadge, Button, Segmented, IcoBox, Progress, Async, Empty,
} from '../components/ui.jsx';
import { AreaChart, niceScale } from '../components/charts.jsx';
import { useApi, useInterval, useLiveReload } from '../api/hooks.js';
import { useLiveEvent } from '../api/live.jsx';
import { useSession } from '../api/session.jsx';
import { operationIcon } from '../lib/operations.js';
import { ago, compact, exportCsv, fcfa, money, num, pct, shortName, time, todayLabel } from '../lib/format.js';

const PERIODS = { Jour: 'JOUR', Semaine: 'SEMAINE', Mois: 'MOIS' };
const ETAT_TONE = (etat) => (etat === 'Normal' ? 'gray' : /Lecteur/.test(etat) ? 'red' : 'orange');

export function CuveBars({ levels }) {
  const colors = ['#1b66b8', '#5aa935', '#f29e1f'];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 4, height: 24 }}>
      {levels.map((l, i) => (
        <span key={i} style={{ width: 7, height: 24, background: '#edf1f5', borderRadius: 2, display: 'flex', alignItems: 'flex-end' }}>
          <span style={{ width: '100%', height: `${l}%`, borderRadius: 2, background: l < 20 ? '#e5484d' : colors[i % 3] }} />
        </span>
      ))}
    </span>
  );
}

/** Ligne du flux en direct (entrée du journal). */
export function FeedItem({ op, flash }) {
  const [icon, tone] = operationIcon(op);
  const who = op.actorName && op.actorName !== 'Système' ? shortName(op.actorName) : null;
  return (
    <div className={`list-item ${flash ? 'flash' : ''}`} style={{ alignItems: 'flex-start' }}>
      <IcoBox icon={icon} tone={tone} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="strong" style={{ fontSize: 14 }}>{op.title}</div>
        <div className="muted" style={{ fontSize: 12.5, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {[who, op.detail, op.place].filter(Boolean).join(' · ')}
        </div>
      </div>
      <div style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
        <div className="strong num" style={{ fontSize: 14 }}>{op.amount ? money(op.amount) : ''}</div>
        <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>{ago(op.at)}</div>
      </div>
    </div>
  );
}

/** Flux en direct : opérations du journal, les nouvelles arrivent par le flux temps réel. */
export function LiveFeed({ initial, limit = 7, stationId }) {
  const [items, setItems] = useState([]);
  const [, tick] = useState(0);
  useEffect(() => {
    if (initial) setItems((cur) => (cur.length ? cur : initial.slice(0, limit).map((o) => ({ ...o, fresh: false }))));
  }, [initial, limit]);
  useLiveEvent(['operation'], (_, op) => {
    if (!op || (stationId && op.stationId !== stationId)) return;
    setItems((cur) => [{ ...op, fresh: true }, ...cur.filter((x) => x.id !== op.id)].slice(0, limit));
  });
  useInterval(() => tick((n) => n + 1), 30_000); // « il y a x min »
  if (!items.length) return <Empty>Aucune opération pour le moment</Empty>;
  return items.map((op) => <FeedItem key={op.id} op={op} flash={op.fresh} />);
}

export default function Dashboard() {
  const navigate = useNavigate();
  const { user, can } = useSession();
  const [period, setPeriod] = useState('Jour');
  const dash = useApi(`/dashboard?period=${PERIODS[period]}`);
  useLiveReload(dash.reload, ['sale', 'operation', 'station'], 4000);
  useInterval(dash.reload, 60_000);

  return (
    <>
      <PageHeader
        title={`Bonjour ${user?.firstName || ''}`}
        subtitle={`${todayLabel()} · voici l’activité de votre réseau en direct`}
        actions={
          <>
            <Button
              icon={Download}
              disabled={!dash.data}
              onClick={() =>
                exportCsv(`stations-${period.toLowerCase()}.csv`, [
                  { label: 'Station', value: 'name' }, { label: 'Statut', value: 'statusLabel' }, { label: 'Mode', value: 'modeLabel' },
                  { label: 'Recette du jour (F)', value: 'revenueToday' }, { label: 'Volume (L)', value: 'volumeToday' },
                  { label: 'État', value: 'state' },
                ], dash.data.stationStates)
              }
            >
              Exporter
            </Button>
            {can('STATIONS_GERER') && (
              <Button variant="primary" icon={Plus} onClick={() => navigate('/stations?new=1')}>Nouvelle station</Button>
            )}
          </>
        }
      />

      <Async state={dash} minHeight={400}>
        {(d) => {
          const fuels = d.volume.byFuel || [];
          const share = fuels.filter((f) => f.code !== 'PETROLE').map((f) => `${f.name} ${f.sharePct} %`).join(' · ');
          const offline = d.stations.offline + d.stations.unstable;
          const curve = d.curves;
          const current = curve.current.filter((v) => v !== null);
          const scale = niceScale(Math.max(...curve.previous, ...current, 1));
          const unitM = scale.max >= 1e6;
          const yLabels = scale.ticks.map((t) => (unitM ? `${num(t / 1e6, t % 1e6 ? 1 : 0)} M` : `${num(t / 1e3)} k`));
          const step = curve.labels.length > 12 ? Math.ceil(curve.labels.length / 8) : 1;
          const xLabels = curve.labels.filter((_, i) => i % step === 0)
            .map((l) => (curve.unit === 'JOUR' ? l.slice(8, 10) + '/' + l.slice(5, 7) : l));
          return (
            <>
              <div className="grid g4 mb20">
                <Kpi
                  icon={Banknote}
                  label={period === 'Jour' ? 'Recette du jour' : `Recette · ${period.toLowerCase()}`}
                  value={fcfa(d.revenue.value)}
                  unit="FCFA"
                  foot={<><Trend value={pct(d.revenue.changePct)} down={d.revenue.changePct < 0} /> vs {period === 'Jour' ? 'hier à la même heure' : 'période précédente'}</>}
                />
                <Kpi
                  icon={Droplet}
                  tone="green"
                  label="Volume distribué"
                  value={num(d.volume.value)}
                  unit="L"
                  foot={<><Trend value={pct(d.volume.changePct)} down={d.volume.changePct < 0} /> {share}</>}
                />
                <Kpi
                  icon={Wifi}
                  tone="navy"
                  label="Stations en ligne"
                  value={d.stations.online}
                  unit={`/ ${d.stations.total}`}
                  foot={offline ? <><Badge tone="red" dot>{d.stations.offline} hors ligne</Badge> {d.stations.unstable ? `${d.stations.unstable} instable` : ''}</> : 'Toutes en ligne'}
                />
                <Kpi
                  icon={ShieldCheck}
                  tone="orange"
                  label="Validations en attente"
                  value={d.validations.pending}
                  foot={<><span className="row" style={{ gap: 5, color: 'var(--orange)', fontWeight: 600 }}><Clock size={14} /> {compact(d.validations.amount)} FCFA</span> à approuver</>}
                />
              </div>

              <div className="grid mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px' }}>
                <Card>
                  <CardHead
                    title="Recette en direct"
                    sub="Toutes stations, mise à jour en continu"
                    right={<Segmented items={Object.keys(PERIODS)} value={period} onChange={setPeriod} />}
                  />
                  <div className="row" style={{ gap: 34, padding: '20px 20px 0', alignItems: 'flex-end' }}>
                    <div>
                      <div className="muted" style={{ fontSize: 12.5 }}>Cumul à {time(d.generatedAt)}</div>
                      <div className="num" style={{ fontSize: 21, fontWeight: 700, marginTop: 4 }}>
                        {fcfa(d.revenue.value + d.recharges)} <small className="muted" style={{ fontSize: 12.5 }}>FCFA</small>
                      </div>
                    </div>
                    {d.breakdown.map((b) => (
                      <div key={b.code}>
                        <div className="muted" style={{ fontSize: 12.5 }}>{b.label}</div>
                        <div className="num strong" style={{ fontSize: 16, marginTop: 6 }}>{fcfa(b.amount)}</div>
                      </div>
                    ))}
                    <div className="row" style={{ marginLeft: 'auto', gap: 16, alignSelf: 'flex-start' }}>
                      <span className="legend"><i style={{ background: '#1b66b8' }} /> {period === 'Jour' ? 'Aujourd’hui' : 'Période'}</span>
                      <span className="legend"><i style={{ background: '#8db4e2' }} /> {period === 'Jour' ? 'Hier' : 'Précédente'}</span>
                    </div>
                  </div>
                  <div style={{ padding: '14px 12px 20px 4px' }}>
                    <AreaChart
                      today={current}
                      yesterday={curve.previous}
                      points={curve.labels.length}
                      max={scale.max}
                      height={300}
                      yLabels={yLabels}
                      xLabels={xLabels}
                    />
                  </div>
                </Card>

                <Card>
                  <CardHead title="Flux en direct" sub="Qui fait quoi, en ce moment" right={<span className="live-pill" style={{ height: 26 }}><span className="pulse" /> Live</span>} />
                  <div style={{ padding: '8px 20px 6px' }}>
                    <LiveFeed initial={d.feed} />
                  </div>
                </Card>
              </div>

              <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 380px', alignItems: 'start' }}>
                <Card>
                  <CardHead
                    title="État des stations"
                    sub={`${d.stations.online} en ligne · ${offline} hors ligne ou instable`}
                    right={<Button size="sm" iconRight={ArrowRight} onClick={() => navigate('/stations')}>Voir les {d.stations.total} stations</Button>}
                  />
                  <table className="table" style={{ marginTop: 14 }}>
                    <thead>
                      <tr>
                        <th>Station</th><th>Statut</th><th>Mode</th><th className="r">Recette du jour</th><th>Cuves</th><th>État</th>
                      </tr>
                    </thead>
                    <tbody>
                      {d.stationStates.map((s) => (
                        <tr key={s.id} className="tr-click" onClick={() => navigate(`/stations/${s.id}`)}>
                          <td>
                            <div className="row" style={{ gap: 12 }}>
                              <IcoBox icon={Fuel} tone="gray" size={32} />
                              <div>
                                <div className="cell-title">{s.shortName}</div>
                                <div className="cell-sub">{s.typeLabel}</div>
                              </div>
                            </div>
                          </td>
                          <td><StatusBadge status={s.statusLabel} /></td>
                          <td><Badge tone={s.mode === 'AUTONOME' ? 'navy' : 'blue'}>{s.modeLabel}</Badge></td>
                          <td className="r num strong">{s.revenueToday ? fcfa(s.revenueToday) : '—'}</td>
                          <td><CuveBars levels={(s.tanks || []).map((t) => t.pct)} /></td>
                          <td>{s.state && <Badge tone={ETAT_TONE(s.state)}>{s.state}</Badge>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Card>

                <div className="stack">
                  <Card>
                    <CardHead title="Points de vente ouverts" right={<Badge tone="green">{d.pdv.length} ouverts</Badge>} />
                    <div style={{ padding: '6px 20px 14px' }}>
                      {d.pdv.length === 0 && <Empty>Aucun point de vente ouvert</Empty>}
                      {d.pdv.map((p) => (
                        <div key={p.id} className="tr-click" style={{ padding: '14px 0', borderBottom: '1px solid var(--border-2)' }} onClick={() => navigate(`/points-de-vente?id=${p.id}`)}>
                          <div className="row between">
                            <span className="row" style={{ gap: 8 }}>
                              <span className="strong">{p.name}</span>
                              {p.offline && <Badge tone="orange">Hors ligne · {ago(p.lastSyncAt)}</Badge>}
                            </span>
                            <span className="strong num">{money(p.amount)}</span>
                          </div>
                          <div className="row" style={{ marginTop: 10, gap: 14 }}>
                            <span className="muted" style={{ width: 130, fontSize: 13 }}>
                              {p.kind === 'PERMANENT' ? 'Permanent' : `Temporaire · J-${p.daysLeft ?? '?'}`}
                            </span>
                            <Progress value={Number(p.soldPct)} />
                            <span className="muted" style={{ fontSize: 13, whiteSpace: 'nowrap' }}>{num(p.soldPct)} % cartes</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </Card>

                  <Card>
                    <CardHead title="Alertes de stock" right={<span className="muted" style={{ fontSize: 13 }}>{d.stockAlerts.length} actives</span>} />
                    <div style={{ padding: '6px 20px 10px' }}>
                      {d.stockAlerts.length === 0 && <Empty>Aucune alerte</Empty>}
                      {d.stockAlerts.map((a) => (
                        <div key={a.id} className="list-item tr-click" style={{ borderBottom: 0, padding: '10px 0' }} onClick={() => navigate('/ventes-stocks')}>
                          <IcoBox icon={a.tankId ? TriangleAlert : Mail} tone={a.severity === 'CRITIQUE' ? 'red' : 'orange'} />
                          <div>
                            <div className="strong">{a.productName} · {a.place}</div>
                            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
                              {num(a.level)}{a.tankId ? ' L' : ''} restants · seuil {num(a.threshold)}{a.tankId ? ' L' : ''} · {ago(a.raisedAt)}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </Card>
                </div>
              </div>
            </>
          );
        }}
      </Async>
    </>
  );
}
