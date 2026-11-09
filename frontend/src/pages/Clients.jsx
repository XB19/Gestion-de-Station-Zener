import { useEffect, useState } from 'react';
import { Building2, KeyRound, CalendarDays, CircleX, Download, Plus, Search, RefreshCw, Power } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, StatusBadge, Button, SearchInput, Avatar, KV, Progress, Async, Empty } from '../components/ui.jsx';
import { Ring } from '../components/charts.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi } from '../api/hooks.js';
import { post } from '../api/client.js';
import { avatarColor, date, exportCsv, fcfa, initials, isoDay, money } from '../lib/format.js';

const KIND = { GROUPE: 'Succursales', GERANT: 'Gérance libre' };

function ClientModal({ onClose, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState({ name: '', kind: 'GERANT', country: 'Togo', contactName: '', phone: '', email: '', amountPerStation: 350000, licenceStart: isoDay() });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title="Nouveau client" submitLabel="Créer le client et sa licence" wide onClose={onClose} onSubmit={async () => {
      const c = await post('/clients', { ...f, amountPerStation: Number(f.amountPerStation) || 0 });
      notify(`Client créé · licence jusqu’au ${date(c.endDate)}`);
      onSaved(c);
    }}>
      <div className="form-grid">
        <Field label="Raison sociale" full><TextInput value={f.name} onChange={set('name')} required /></Field>
        <Field label="Type"><SelectInput value={f.kind} onChange={set('kind')} options={[{ value: 'GERANT', label: 'Gérant (gérance libre)' }, { value: 'GROUPE', label: 'Groupe (succursales)' }]} /></Field>
        <Field label="Pays"><SelectInput value={f.country} onChange={set('country')} options={['Togo', 'Bénin', 'Burkina Faso']} /></Field>
        <Field label="Contact"><TextInput value={f.contactName} onChange={set('contactName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Email"><TextInput type="email" value={f.email} onChange={set('email')} /></Field>
        <Field label="Licence : montant par station (F / an)"><TextInput type="number" min="0" value={f.amountPerStation} onChange={set('amountPerStation')} /></Field>
        <Field label="Début de la licence"><TextInput type="date" value={f.licenceStart} onChange={set('licenceStart')} /></Field>
      </div>
      <div className="muted" style={{ fontSize: 13, marginTop: 12 }}>Les stations sont attribuées au client depuis l’écran Stations.</div>
    </FormModal>
  );
}

export default function Clients() {
  const notify = useToast();
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState(null);
  const [modal, setModal] = useState(false);
  const summary = useApi('/clients/summary');
  const list = useApi('/clients');
  const detail = useApi(selected ? `/clients/${selected}` : null);
  const reload = () => {
    summary.reload();
    list.reload();
    detail.reload();
  };

  useEffect(() => {
    if (!selected && list.data?.length) {
      const soon = [...list.data].filter((c) => c.licenceStatus === 'Expire bientôt').sort((a, b) => a.daysLeft - b.daysLeft)[0];
      setSelected((soon || list.data[0]).id);
    }
  }, [list.data, selected]);

  const act = async (id, action, message) => {
    if (action === 'terminate' && !window.confirm('Résilier la licence de ce client ? Ses stations ne seront plus couvertes.')) return;
    try {
      await post(`/clients/${id}/licence/${action}`, action === 'renew' ? { years: 1 } : undefined);
      notify(message);
      reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  const rows = (list.data || []).filter((c) => c.name.toLowerCase().includes(q.toLowerCase()));
  const s = summary.data;

  return (
    <>
      <PageHeader
        title="Clients & licences"
        subtitle="Attribution des stations aux clients et gestion de la licence annuelle"
        actions={
          <>
            <Button icon={Download} disabled={!rows.length} onClick={() => exportCsv('clients.csv', [
              { label: 'Client', value: 'name' }, { label: 'Type', value: (c) => KIND[c.kind] }, { label: 'Pays', value: 'country' },
              { label: 'Stations', value: 'stations' }, { label: 'Licence', value: 'licenceStatus' }, { label: 'Échéance', value: 'endDate' },
              { label: 'Contact', value: 'contactName' }, { label: 'Téléphone', value: 'phone' }, { label: 'Email', value: 'email' },
            ], rows)}>Exporter</Button>
            <Button variant="primary" icon={Plus} onClick={() => setModal(true)}>Nouveau client</Button>
          </>
        }
      />

      <div className="grid g4 mb20">
        <Kpi icon={Building2} label="Clients" value={s ? s.clients : '…'} foot={s ? `${s.groups} groupe · ${s.managers} gérants` : ''} />
        <Kpi icon={KeyRound} tone="green" label="Stations sous licence" value={s ? s.stationsLicensed : '…'} foot={s ? `sur ${s.stationsTotal} stations` : ''} />
        <Kpi icon={CalendarDays} tone="orange" label="À renouveler sous 30 j" value={s ? s.toRenew : '…'} foot={<Badge tone="orange">Rappels J-30, J-7, J-1</Badge>} />
        <Kpi icon={CircleX} tone="red" label="Résiliées" value={s ? s.terminated : '…'}
          foot={(list.data || []).filter((c) => c.licenceStatus === 'Résiliée').map((c) => c.name).join(' · ') || '—'} />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
        <Card>
          <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
            <div className="card-title">Clients</div>
            <SearchInput icon={Search} placeholder="Rechercher un client…" style={{ width: 300 }} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <Async state={list}>
            {() => (rows.length === 0 ? <Empty>Aucun client</Empty> : (
              <table className="table">
                <thead>
                  <tr><th>Client</th><th className="r">Stations</th><th>Licence</th><th>Échéance</th><th>Restant</th><th /></tr>
                </thead>
                <tbody>
                  {rows.map((c) => {
                    const days = Math.max(0, c.daysLeft ?? 0);
                    const terminated = c.licenceStatus === 'Résiliée' || c.licenceStatus === 'Expirée';
                    const color = terminated ? '#e2e8f0' : days < 30 ? '#f29e1f' : '#5aa935';
                    return (
                      <tr key={c.id} className="tr-click" onClick={() => setSelected(c.id)} style={c.id === selected ? { background: '#f7faff' } : undefined}>
                        <td>
                          <div className="row" style={{ gap: 12 }}>
                            <Avatar initials={initials(c.name)} color={avatarColor(c.name)} size={36} />
                            <div>
                              <div className="cell-title">{c.name}</div>
                              <div className="cell-sub">{KIND[c.kind]} · {c.country}</div>
                            </div>
                          </div>
                        </td>
                        <td className="r num strong">{c.stations}</td>
                        <td><StatusBadge status={c.licenceStatus} /></td>
                        <td className="num">{date(c.endDate)}</td>
                        <td>
                          <div className="row" style={{ gap: 10, width: 130 }}>
                            <Progress value={terminated ? 0 : Math.max(Math.min((days / 365) * 100, 100), 6)} color={color} />
                            <span className="muted num" style={{ fontSize: 13, width: 38, textAlign: 'right' }}>{terminated ? '—' : `${days} j`}</span>
                          </div>
                        </td>
                        <td className="r">
                          <button
                            className="icon-btn"
                            style={{ width: 32, height: 32 }}
                            title={c.licenceStatus === 'Résiliée' ? 'Réactiver' : 'Renouveler pour 1 an'}
                            onClick={(e) => {
                              e.stopPropagation();
                              if (c.licenceStatus === 'Résiliée') act(c.id, 'reactivate', 'Licence réactivée');
                              else act(c.id, 'renew', 'Licence renouvelée pour un an');
                            }}
                          >
                            {c.licenceStatus === 'Résiliée' ? <Power size={15} /> : <RefreshCw size={15} />}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            ))}
          </Async>
        </Card>

        <Card className="card-pad">
          <div className="card-title">Licence</div>
          {!selected && <Empty>Sélectionnez un client</Empty>}
          {selected && (
            <Async state={detail} minHeight={300}>
              {(c) => {
                const days = Math.max(0, c.daysLeft ?? 0);
                const terminated = c.licenceStatus === 'Résiliée';
                return (
                  <>
                    <div className="row" style={{ gap: 18, marginTop: 16 }}>
                      <Ring value={terminated ? 0 : days} max={365} size={108} color={days < 30 ? '#f29e1f' : '#5aa935'}>
                        <div>
                          <div className="num" style={{ fontSize: 24, fontWeight: 700 }}>{terminated ? '—' : days}</div>
                          <div className="muted" style={{ fontSize: 12 }}>jours</div>
                        </div>
                      </Ring>
                      <div>
                        <div className="strong" style={{ fontSize: 16 }}>{c.name}</div>
                        <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>Licence annuelle · {c.stations.length} station{c.stations.length > 1 ? 's' : ''}</div>
                        <Badge tone={terminated ? 'red' : days < 30 ? 'orange' : 'green'} dot style={{ marginTop: 8 }}>
                          {terminated ? 'Résiliée' : `${c.licenceStatus === 'Expirée' ? 'Expirée' : 'Expire'} le ${date(c.endDate)}`}
                        </Badge>
                      </div>
                    </div>
                    <div style={{ marginTop: 8 }}>
                      <KV k="Formule" v="Annuelle · par station" />
                      <KV k="Montant" v={`${money(c.annualAmount)} / an`} />
                      <KV k="Par station" v={`${fcfa(c.amountPerStation)} F`} />
                      <KV k="Début" v={date(c.startDate)} />
                      <KV k="Stations couvertes" v={c.stations.map((x) => x.shortName).join(', ') || 'Aucune'} />
                      <KV k="Contact" v={[c.contactName, c.phone].filter(Boolean).join(' · ') || '—'} />
                      <KV k="Rappel automatique" v={c.reminderDays.split(',').map((d) => `J-${d.trim()}`).join(', ')} />
                    </div>
                    {terminated ? (
                      <Button variant="primary" icon={Power} block style={{ height: 42, marginTop: 14 }} onClick={() => act(c.id, 'reactivate', 'Licence réactivée')}>
                        Réactiver la licence
                      </Button>
                    ) : (
                      <>
                        <Button variant="primary" icon={RefreshCw} block style={{ height: 42, marginTop: 14 }} onClick={() => act(c.id, 'renew', 'Licence renouvelée pour un an')}>
                          Renouveler pour 1 an
                        </Button>
                        <Button variant="danger" icon={Power} block style={{ height: 42, marginTop: 10 }} onClick={() => act(c.id, 'terminate', 'Licence résiliée')}>
                          Résilier la licence
                        </Button>
                      </>
                    )}
                  </>
                );
              }}
            </Async>
          )}
        </Card>
      </div>

      {modal && <ClientModal onClose={() => setModal(false)} onSaved={(c) => { reload(); setSelected(c.id); }} />}
    </>
  );
}
