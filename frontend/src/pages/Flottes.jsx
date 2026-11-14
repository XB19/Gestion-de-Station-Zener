import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Building2, IdCard, Wallet, Fuel, Pencil, Plus, Search, X, Car } from 'lucide-react';
import { PageHeader, Card, Kpi, Badge, Button, Checkbox, Async, Empty } from '../components/ui.jsx';
import { FormModal, Field, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { post, put } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { dateTime, fcfa, money, num } from '../lib/format.js';

const STATUS_TONES = { ACTIVE: 'green', A_ACTIVER: 'orange', BLOQUEE: 'red', INACTIVE: 'gray' };
const vehicle = (c) => [c.vehiclePlate, c.vehicleLabel, c.driverName].filter(Boolean).join(' · ');

/** Création ou modification d'une flotte (groupe de cartes d'une entreprise). */
function FleetModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const companies = useApi(fleet ? null : '/holders?kind=ENTREPRISE');
  const [f, setF] = useState({
    holderId: fleet ? String(fleet.holderId) : '', name: fleet?.name || '', contactName: fleet?.contactName || '',
    phone: fleet?.phone || '', note: fleet?.note || '', active: fleet?.active ?? true,
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormModal title={fleet ? `Flotte · ${fleet.holderName}` : 'Nouvelle flotte'} onClose={onClose} onSubmit={async () => {
      const body = { ...f, holderId: Number(f.holderId) };
      const r = fleet ? await put(`/fleets/${fleet.id}`, body) : await post('/fleets', body);
      notify(fleet ? 'Flotte modifiée' : 'Flotte créée');
      onSaved(r);
    }}>
      <div className="form-grid">
        {fleet ? (
          <Field label="Entreprise"><TextInput value={fleet.holderName} onChange={() => {}} disabled /></Field>
        ) : (
          <Field label="Entreprise">
            <SelectInput value={f.holderId} onChange={set('holderId')} placeholder="Choisir…" required
              options={(companies.data || []).map((h) => ({ value: String(h.id), label: h.name }))} />
          </Field>
        )}
        <Field label="Nom de la flotte"><TextInput value={f.name} onChange={set('name')} required placeholder="Camions Nord, Direction…" /></Field>
        <Field label="Contact"><TextInput value={f.contactName} onChange={set('contactName')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        {fleet && (
          <Field label="État">
            <SelectInput value={f.active ? 'oui' : 'non'} onChange={(v) => set('active')(v === 'oui')}
              options={[{ value: 'oui', label: 'Active' }, { value: 'non', label: 'Désactivée' }]} />
          </Field>
        )}
        <Field label="Note" full><TextInput value={f.note} onChange={set('note')} /></Field>
      </div>
    </FormModal>
  );
}

/** Attribution de cartes de l'entreprise à la flotte. */
function AttachModal({ fleet, onClose, onSaved }) {
  const notify = useToast();
  const cards = useApi(`/fleets/${fleet.id}/available-cards`);
  const [picked, setPicked] = useState([]);
  const toggle = (id) => setPicked((x) => (x.includes(id) ? x.filter((y) => y !== id) : [...x, id]));
  return (
    <FormModal title={`Attribuer des cartes · ${fleet.name}`} wide submitLabel={`Attribuer${picked.length ? ` (${picked.length})` : ''}`}
      onClose={onClose} onSubmit={async () => {
        if (!picked.length) throw new Error('Choisissez au moins une carte');
        const r = await post(`/fleets/${fleet.id}/cards`, { cardIds: picked });
        notify(`${picked.length} carte(s) attribuée(s) à ${fleet.name}`);
        onSaved(r);
      }}>
      <div className="muted" style={{ fontSize: 13, marginBottom: 10 }}>
        Cartes de {fleet.holderName} qui ne sont pas dans cette flotte. Une carte n'appartient qu'à une flotte : si elle est déjà dans une autre, elle y est retirée.
      </div>
      <Async state={cards}>
        {(rows) => (!rows.length ? <Empty>Toutes les cartes de l'entreprise sont déjà dans cette flotte</Empty> : (
          <div className="table-wrap" style={{ maxHeight: 380, overflow: 'auto' }}><table className="table">
            <thead><tr><th style={{ width: 40 }} /><th>Carte</th><th>État</th><th>Véhicule · chauffeur</th><th>Flotte actuelle</th><th className="r">Solde</th></tr></thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} onClick={() => toggle(c.id)} style={{ cursor: 'pointer' }}>
                  <td><Checkbox checked={picked.includes(c.id)} /></td>
                  <td className="num strong">{c.numberFormatted}</td>
                  <td><Badge tone={STATUS_TONES[c.status] || 'gray'}>{c.statusLabel}</Badge></td>
                  <td>{vehicle(c) || '—'}</td>
                  <td>{c.currentGroup || <span className="muted">Aucune</span>}</td>
                  <td className="r num">{fcfa(c.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        ))}
      </Async>
    </FormModal>
  );
}

export default function Flottes() {
  const notify = useToast();
  const { can } = useSession();
  const edit = can('FLOTTES');
  const [q, setQ] = useState('');
  const list = useApi('/fleets');
  const [selected, setSelected] = useState(null);
  const [modal, setModal] = useState(null);
  const detail = useApi(selected ? `/fleets/${selected}` : null);
  useLiveReload(() => { list.reload(); detail.reload(); }, ['operation'], 8000);

  useEffect(() => {
    if (!selected && list.data?.length) setSelected(list.data.find((f) => f.cards > 0)?.id || list.data[0].id);
  }, [list.data, selected]);

  const shown = useMemo(() => (list.data || []).filter((f) => !q || `${f.holderName} ${f.name}`.toLowerCase().includes(q.toLowerCase())), [list.data, q]);
  const done = (r) => {
    setModal(null);
    list.reload();
    if (r?.id && r.id !== selected) setSelected(r.id);
    else detail.reload();
  };
  const remove = async (fleet, c) => {
    try {
      await post(`/fleets/${fleet.id}/cards/${c.id}/remove`);
      notify(`Carte ${c.numberFormatted} retirée de ${fleet.name}`);
      done();
    } catch (e) {
      notify(e.message, 'error');
    }
  };

  return (
    <>
      <PageHeader title="Flottes" subtitle="Groupes de cartes des entreprises : attribuez des cartes à une flotte pour les suivre ensemble (cartes, extractions)"
        actions={edit && <Button variant="primary" icon={Plus} onClick={() => setModal({ type: 'fleet' })}>Nouvelle flotte</Button>} />
      <div className="grid split" style={{ gridTemplateColumns: '320px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad" style={{ padding: 14 }}>
          <label className="input" style={{ height: 38, marginBottom: 10 }}>
            <Search size={16} strokeWidth={1.8} />
            <input placeholder="Entreprise ou flotte" value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
          <Async state={list}>
            {() => (!shown.length ? <Empty>Aucune flotte</Empty> : shown.map((f) => (
              <button key={f.id} type="button" onClick={() => setSelected(f.id)}
                style={{ width: '100%', textAlign: 'left', border: f.id === selected ? '1.5px solid #bcd3ee' : '1px solid transparent', borderRadius: 10,
                  background: f.id === selected ? 'var(--primary-50)' : 'transparent', padding: '10px 12px', marginBottom: 4, opacity: f.active ? 1 : 0.55 }}>
                <div className="row between">
                  <span className="strong" style={{ fontSize: 14 }}>{f.name}</span>
                  <span className="muted num" style={{ fontSize: 13 }}>{f.cards} carte(s)</span>
                </div>
                <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>{f.holderName} · {fcfa(f.balance)} F sur les cartes</div>
              </button>
            )))}
          </Async>
        </Card>

        <div className="stack">
          {!selected ? <Card><Empty>Choisissez une flotte</Empty></Card> : (
            <Async state={detail}>
              {(f) => {
                const cards = f.cards || [];
                const active = cards.filter((c) => c.status === 'ACTIVE').length;
                return (
                  <>
                    <Card className="card-pad">
                      <div className="row between" style={{ flexWrap: 'wrap', rowGap: 10 }}>
                        <div>
                          <div className="row" style={{ gap: 8 }}>
                            <Building2 size={18} color="var(--primary)" />
                            <span className="card-title">{f.holderName} · {f.name}</span>
                            {!f.active && <Badge tone="red">Désactivée</Badge>}
                          </div>
                          <div className="card-sub">{[f.contactName, f.phone || f.holderPhone, f.note].filter(Boolean).join(' · ') || 'Aucun contact renseigné'}</div>
                        </div>
                        {edit && (
                          <span className="row" style={{ gap: 8 }}>
                            <Button size="sm" icon={Pencil} onClick={() => setModal({ type: 'fleet', fleet: f })}>Modifier</Button>
                            <Button size="sm" variant="primary" icon={Plus} onClick={() => setModal({ type: 'attach', fleet: f })} disabled={!f.active}>
                              Attribuer des cartes
                            </Button>
                          </span>
                        )}
                      </div>
                    </Card>
                    <div className="grid g3">
                      <Kpi icon={IdCard} label="Cartes" value={num(cards.length)} foot={`${active} active(s)`} />
                      <Kpi icon={Wallet} tone="navy" label="Soldes des cartes" value={fcfa(cards.reduce((s, c) => s + c.balance, 0))} unit="F" foot="total disponible" />
                      <Kpi icon={Fuel} tone="green" label="Achats 30 jours" value={fcfa(cards.reduce((s, c) => s + Number(c.spent30d || 0), 0))} unit="F"
                        foot="toutes les cartes de la flotte" />
                    </div>
                    <Card>
                      <div className="card-head"><div className="card-title">Cartes de la flotte</div></div>
                      {!cards.length ? <Empty>Aucune carte · « Attribuer des cartes » pour en ajouter</Empty> : (
                        <div className="table-wrap"><table className="table">
                          <thead><tr><th>Carte</th><th>État</th><th>Véhicule · chauffeur</th><th className="r">Solde</th><th className="r">Achats 30 j</th>
                            <th>Dernier achat</th>{edit && <th />}</tr></thead>
                          <tbody>
                            {cards.map((c) => (
                              <tr key={c.id}>
                                <td><Link to={`/cartes/${c.id}`} className="num strong">{c.numberFormatted}</Link></td>
                                <td><Badge tone={STATUS_TONES[c.status] || 'gray'}>{c.statusLabel}</Badge></td>
                                <td>{vehicle(c) ? <><Car size={13} style={{ verticalAlign: '-2px' }} /> {vehicle(c)}</> : <span className="muted">—</span>}</td>
                                <td className="r num">{fcfa(c.balance)}</td>
                                <td className="r num">{money(c.spent30d)}</td>
                                <td className="muted num">{c.lastSaleAt ? dateTime(c.lastSaleAt) : '—'}</td>
                                {edit && (
                                  <td className="r"><Button size="sm" icon={X} title="Retirer de la flotte" onClick={() => remove(f, c)} /></td>
                                )}
                              </tr>
                            ))}
                          </tbody>
                        </table></div>
                      )}
                    </Card>
                    {modal?.type === 'attach' && <AttachModal fleet={f} onClose={() => setModal(null)} onSaved={done} />}
                  </>
                );
              }}
            </Async>
          )}
        </div>
      </div>
      {modal?.type === 'fleet' && <FleetModal fleet={modal.fleet} onClose={() => setModal(null)} onSaved={done} />}
    </>
  );
}
