import { useEffect, useState } from 'react';
import { Search, CalendarDays, Activity, Fuel, Download, Cpu, Eye, User } from 'lucide-react';
import { PageHeader, Card, Button, Select, SearchInput, Avatar, IcoBox, KV, Async, Empty, Pager } from '../components/ui.jsx';
import { Donut } from '../components/charts.jsx';
import { Modal } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { useLiveEvent } from '../api/live.jsx';
import { useSession } from '../api/session.jsx';
import { fileUrl, qs } from '../api/client.js';
import { CATEGORY_COLORS, CATEGORY_LABELS, operationIcon } from '../lib/operations.js';
import { avatarColor, date, exportCsv, isoDay, money, num, time } from '../lib/format.js';

const PERIODS = [
  { value: 'today', label: 'Aujourd’hui' },
  { value: 'yesterday', label: 'Hier' },
  { value: 'week', label: '7 derniers jours' },
  { value: 'month', label: '30 derniers jours' },
];
function range(period) {
  const d = (n) => isoDay(new Date(Date.now() - n * 86400000));
  return { today: [d(0), d(0)], yesterday: [d(1), d(1)], week: [d(6), d(0)], month: [d(29), d(0)] }[period];
}

export default function Journal() {
  const { can } = useSession();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [period, setPeriod] = useState('today');
  const [category, setCategory] = useState('');
  const [stationId, setStationId] = useState('');
  const [userId, setUserId] = useState('');
  const [page, setPage] = useState(0);
  const [fresh, setFresh] = useState([]);
  const [preview, setPreview] = useState(false);
  const [from, to] = range(period);

  const list = useApi(`/journal${qs({ q: search, from, to, category, stationId, userId, page, size: 25 })}`);
  const breakdown = useApi(`/journal/breakdown?day=${to}`);
  const stations = useApi('/stations');
  const users = useApi(can('EMPLOYES') ? '/employees?size=200' : null);
  const settings = useApi('/settings');
  const email = useApi(preview ? `/reports/email-summary/preview` : null);
  useLiveReload(breakdown.reload, ['operation'], 5000);

  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(q.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);
  useEffect(() => setFresh([]), [list.data]);

  // nouvelles opérations en tête de liste (première page, sans filtre restrictif)
  const live = page === 0 && !search && period === 'today' && !userId;
  useLiveEvent(['operation'], (_, op) => {
    if (!live || !op) return;
    if (category && op.category !== category) return;
    if (stationId && String(op.stationId) !== String(stationId)) return;
    setFresh((cur) => [op, ...cur.filter((x) => x.id !== op.id)].slice(0, 25));
  });

  const rows = [...fresh, ...(list.data?.items || []).filter((o) => !fresh.some((f) => f.id === o.id))].slice(0, 25);
  const b = breakdown.data;
  const segments = (b?.categories || []).map((c) => ({ value: c.count, color: CATEGORY_COLORS[c.category] || '#94a3b8' }));
  const set = (fn) => (v) => {
    fn(v);
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Journal d’opérations"
        subtitle="Qui a fait quoi, et quand · toutes les opérations centralisées avec le point de vente et l’agent"
      />

      <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1fr) 320px', alignItems: 'start' }}>
        <Card>
          <div className="filters" style={{ flexWrap: 'wrap' }}>
            <SearchInput icon={Search} placeholder="Rechercher…" style={{ flex: 1, minWidth: 160 }} value={q} onChange={(e) => setQ(e.target.value)} />
            <Select icon={CalendarDays} value={period} onChange={set(setPeriod)} options={PERIODS} />
            {users.data && (
              <Select icon={User} value={userId} onChange={set(setUserId)} placeholder="Utilisateur"
                options={users.data.items.map((u) => ({ value: u.id, label: u.name }))} />
            )}
            <Select icon={Activity} value={category} onChange={set(setCategory)} placeholder="Type"
              options={Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }))} />
            <Select icon={Fuel} value={stationId} onChange={set(setStationId)} placeholder="Lieu"
              options={(stations.data || []).map((x) => ({ value: x.id, label: x.shortName }))} />
            <button className="icon-btn" style={{ marginLeft: 'auto' }} title="Exporter la page" disabled={!rows.length}
              onClick={() => exportCsv('journal.csv', [
                { label: 'Date', value: (o) => `${date(o.at)} ${time(o.at, true)}` }, { label: 'Qui', value: 'actorName' }, { label: 'Rôle', value: 'actorRole' },
                { label: 'Lieu', value: 'place' }, { label: 'Opération', value: 'title' }, { label: 'Détail', value: 'detail' }, { label: 'Montant (F)', value: 'amount' },
              ], rows)}>
              <Download size={17} />
            </button>
          </div>
          <Async state={list}>
            {(data) => (rows.length === 0 ? <Empty>Aucune opération pour ces filtres</Empty> : (
              <>
                <div className="table-wrap"><table className="table">
                  <thead>
                    <tr><th>Heure</th><th>Qui · où</th><th>Quoi</th><th className="r">Montant</th></tr>
                  </thead>
                  <tbody>
                    {rows.map((o) => {
                      const [icon, tone] = operationIcon(o);
                      const system = !o.actorId;
                      const isFresh = fresh.some((f) => f.id === o.id);
                      return (
                        <tr key={o.id} className={isFresh ? 'flash' : ''}>
                          <td className="muted num" style={{ fontSize: 14, whiteSpace: 'nowrap' }}>
                            {period === 'today' ? time(o.at, true) : <>{date(o.at)}<br />{time(o.at, true)}</>}
                          </td>
                          <td>
                            <div className="row" style={{ gap: 12 }}>
                              {system ? (
                                <span className="avatar" style={{ width: 32, height: 32, background: 'var(--chip)', color: 'var(--muted)' }}><Cpu size={16} /></span>
                              ) : (
                                <Avatar initials={o.actorInitials} color={avatarColor(o.actorId)} size={32} photo={fileUrl(o.actorPhotoUrl)} />
                              )}
                              <div>
                                <div className="cell-title">{o.actorName}</div>
                                <div className="cell-sub">{[o.actorRole, o.place].filter(Boolean).join(' · ') || '—'}</div>
                              </div>
                            </div>
                          </td>
                          <td>
                            <div className="row" style={{ gap: 12 }}>
                              <IcoBox icon={icon} tone={tone} size={32} />
                              <div>
                                <div className="cell-title">{o.title}</div>
                                <div className="cell-sub">{o.detail}</div>
                              </div>
                            </div>
                          </td>
                          <td className="r num strong">{o.amount ? money(o.amount) : '—'}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table></div>
                <Pager data={data} page={page} onPage={setPage} label="opérations" />
              </>
            ))}
          </Async>
        </Card>

        <div className="stack">
          <Card className="card-pad">
            <div className="card-title">Opérations du jour</div>
            <div className="card-sub">{b ? `${num(b.total)} opérations tracées` : '…'}</div>
            <div style={{ display: 'flex', justifyContent: 'center', margin: '20px 0 16px' }}>
              <Donut size={150} thickness={18} segments={segments.length ? segments : [{ value: 1, color: '#eef2f6' }]}>
                <div>
                  <div className="num" style={{ fontSize: 24, fontWeight: 700 }}>{b ? num(b.total) : '…'}</div>
                  <div className="muted" style={{ fontSize: 12 }}>opérations</div>
                </div>
              </Donut>
            </div>
            {(b?.categories || []).map((c) => (
              <button key={c.category} className="row between" style={{ width: '100%', padding: '8px 0', border: 0, background: 'transparent' }}
                onClick={() => set(setCategory)(category === c.category ? '' : c.category)}>
                <span className="legend" style={{ fontWeight: category === c.category ? 700 : undefined }}>
                  <i style={{ background: CATEGORY_COLORS[c.category] }} /> {CATEGORY_LABELS[c.category] || c.category}
                </span>
                <span className="strong num">{num(c.count)}</span>
              </button>
            ))}
          </Card>

          <Card className="card-pad">
            <div className="card-title">Résumé quotidien par email</div>
            <div style={{ marginTop: 8 }}>
              <KV k="Envoi" v={settings.data ? `Chaque jour à ${settings.data['email.summary_time']}` : '…'} />
              <KV k="Destinataires" v={settings.data ? `${settings.data['email.summary_recipients'].split(',').length} adresses${settings.data['email.summary_to_managers'] === 'true' ? ' + gérants' : ''}` : '…'} />
              <KV k="Contenu" v="Recettes, alertes, validations" />
              <KV k="Dernier envoi" v={settings.data?.['email.summary_last_day'] ? date(settings.data['email.summary_last_day']) : '—'} />
            </div>
            {can('RAPPORTS') && <Button icon={Eye} block style={{ marginTop: 14, height: 40 }} onClick={() => setPreview(true)}>Aperçu de l’email</Button>}
          </Card>
        </div>
      </div>

      {preview && (
        <Modal title="Aperçu du résumé quotidien" onClose={() => setPreview(false)} wide>
          <Async state={email}>
            {(p) => (
              <>
                <div className="muted" style={{ fontSize: 13 }}>À : {p.recipients.join(', ')}</div>
                <div className="strong" style={{ marginTop: 10 }}>{p.subject}</div>
                <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', fontSize: 14, lineHeight: 1.6, marginTop: 12 }}>{p.body}</pre>
              </>
            )}
          </Async>
        </Modal>
      )}
    </>
  );
}
