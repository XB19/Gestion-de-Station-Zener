import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Download, UserPlus, Users, Search, CircleCheck, Fuel, IdCard, Check, Pencil, Trophy, Crown, Camera, UserX, UserCheck, X,
} from 'lucide-react';
import { PageHeader, Card, Badge, Button, Select, SearchInput, Avatar, KV, Async, Empty, Pager, StatusBadge } from '../components/ui.jsx';
import { FormModal, Field, Modal, TextInput, SelectInput, useToast } from '../components/feedback.jsx';
import { useApi, useLiveReload } from '../api/hooks.js';
import { del, fileUrl, post, put, qs, upload } from '../api/client.js';
import { useSession } from '../api/session.jsx';
import { avatarColor, dateTime, exportCsv, fcfa, money, num, ROLE_TONES } from '../lib/format.js';

const ROLE_SHORT = { ADMIN: 'Admin', GERANT: 'Gérant', CHEF_POSTE: 'Chef p.', POMPISTE: 'Pomp.', COMPTABLE: 'Compta.', DAF: 'DAF',
  AGENT_PDV: 'PDV', LIVREUR: 'Livr.' };
const RESERVED = ['ADMIN', 'DAF', 'COMPTABLE'];

function EmployeeFormModal({ employee, roles, stations, onClose, onSaved }) {
  const notify = useToast();
  const { user } = useSession();
  const scoped = !!user.stationBound;
  const [f, setF] = useState({
    firstName: employee?.firstName || '', lastName: employee?.lastName || '', email: employee?.email || '', phone: employee?.phone || '',
    role: employee?.role || 'POMPISTE', stationId: employee?.stationId || (scoped ? user.stationId : ''), idDocType: employee?.idDocType || 'CNI',
    idDocNumber: employee?.idDocNumber || '', password: '',
  });
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const roleOptions = roles.filter((r) => !scoped || !RESERVED.includes(r.code)).map((r) => ({ value: r.code, label: r.label }));
  return (
    <FormModal title={employee ? `Modifier · ${employee.name}` : 'Nouvel employé'} wide submitLabel={employee ? 'Enregistrer' : 'Créer l’employé'}
      onClose={onClose} onSubmit={async () => {
        const body = { ...f, stationId: f.stationId ? Number(f.stationId) : null, email: f.email || null, phone: f.phone || null,
          idDocNumber: f.idDocNumber || null, password: f.password || null };
        const r = employee ? await put(`/employees/${employee.id}`, body) : await post('/employees', body);
        notify(employee ? 'Employé modifié' : `Employé créé · matricule ${r.matricule}`);
        onSaved(r);
      }}>
      <div className="form-grid">
        <Field label="Prénom"><TextInput value={f.firstName} onChange={set('firstName')} required /></Field>
        <Field label="Nom"><TextInput value={f.lastName} onChange={set('lastName')} required /></Field>
        <Field label="Rôle"><SelectInput value={f.role} onChange={set('role')} options={roleOptions} /></Field>
        <Field label="Station">
          <SelectInput value={f.stationId} onChange={set('stationId')} placeholder={scoped ? undefined : 'Siège (aucune station)'}
            options={stations.map((s) => ({ value: s.id, label: s.shortName }))} />
        </Field>
        <Field label="Email (identifiant de connexion)"><TextInput type="email" value={f.email} onChange={set('email')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Pièce d’identité"><SelectInput value={f.idDocType} onChange={set('idDocType')} options={['CNI', 'Passeport', 'Permis']} /></Field>
        <Field label="Numéro de la pièce"><TextInput value={f.idDocNumber} onChange={set('idDocNumber')} /></Field>
        <Field label={employee ? 'Nouveau mot de passe (facultatif)' : 'Mot de passe (8 caractères min.)'} full>
          <TextInput type="password" value={f.password} onChange={set('password')} minLength={8} />
        </Field>
      </div>
    </FormModal>
  );
}

/** Fiche employé : photo, carte employé, activité du mois, postes. */
function EmployeeSheet({ id, roles, stations, onClose, onChanged }) {
  const notify = useToast();
  const navigate = useNavigate();
  const { can, user } = useSession();
  const e = useApi(`/employees/${id}`);
  const [edit, setEdit] = useState(false);
  const fileRef = useRef(null);
  const run = async (fn, message) => {
    try {
      await fn();
      notify(message);
      e.reload();
      onChanged();
    } catch (err) {
      notify(err.message, 'error');
    }
  };
  const manage = can('EMPLOYES') && (!user.stationBound || !RESERVED.includes(e.data?.role));
  return (
    <Modal title="Fiche employé" onClose={onClose} wide>
      <Async state={e} minHeight={260}>
        {(x) => (
          <>
            <div className="row" style={{ gap: 18 }}>
              <div style={{ position: 'relative' }}>
                <Avatar initials={x.initials} color={avatarColor(x.id)} size={76} photo={fileUrl(x.photoUrl)} />
                {manage && (
                  <button className="icon-btn" title="Changer la photo" style={{ position: 'absolute', right: -6, bottom: -6, width: 30, height: 30 }}
                    onClick={() => fileRef.current?.click()}>
                    <Camera size={15} />
                  </button>
                )}
                <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/webp" style={{ display: 'none' }}
                  onChange={(ev) => ev.target.files[0] && run(() => upload(`/employees/${x.id}/photo`, ev.target.files[0]), 'Photo enregistrée')} />
              </div>
              <div style={{ flex: 1 }}>
                <div className="strong" style={{ fontSize: 19 }}>{x.name}</div>
                <div className="muted" style={{ marginTop: 3 }}>{x.matricule} · {x.roleLabel}{x.stationName ? ` · ${x.stationName}` : ' · Siège'}</div>
                <div className="row" style={{ gap: 8, marginTop: 8 }}>
                  <Badge tone={ROLE_TONES[x.role] || 'gray'}>{x.roleLabel}</Badge>
                  <StatusBadge status={x.active ? 'Active' : 'Désactivé'}>{x.active ? 'Actif' : 'Désactivé'}</StatusBadge>
                  {x.currentShift && <Badge tone="green" dot>En poste · {x.currentShift.shiftName || ''}</Badge>}
                </div>
              </div>
              {manage && (
                <div className="row" style={{ gap: 8, alignSelf: 'flex-start' }}>
                  <Button size="sm" icon={Pencil} onClick={() => setEdit(true)}>Modifier</Button>
                  {x.active && x.id !== user.id && (
                    <Button size="sm" variant="danger" icon={UserX} onClick={() => run(() => del(`/employees/${x.id}`), 'Employé désactivé · carte bloquée')}>Désactiver</Button>
                  )}
                  {!x.active && <Button size="sm" icon={UserCheck} onClick={() => run(() => post(`/employees/${x.id}/reactivate`), 'Employé réactivé')}>Réactiver</Button>}
                </div>
              )}
            </div>

            <div className="grid g2" style={{ gap: 24, marginTop: 20 }}>
              <div>
                <div className="section-label">Coordonnées</div>
                <KV k="Email" v={x.email || '—'} />
                <KV k="Téléphone" v={x.phone || '—'} />
                <KV k={x.idDocType || 'Pièce'} v={x.idDocNumber || '—'} />
                <KV k="Connexion" v={x.hasPassword ? 'Mot de passe défini' : 'Pas de mot de passe'} />
              </div>
              <div>
                <div className="section-label">Ce mois-ci</div>
                <KV k="Produits vendus" v={num(x.month.productsSold)} />
                <KV k="Litres servis" v={`${num(x.month.liters)} L`} />
                <KV k="Lubrifiants" v={num(x.month.lubricants)} />
                <KV k="Recette" v={money(x.month.revenue)} />
              </div>
            </div>

            <div className="section-label" style={{ marginTop: 18 }}>Carte employé</div>
            {x.card ? (
              <div className="row" style={{ gap: 14, border: '1px solid var(--border)', borderRadius: 10, padding: '12px 16px' }}>
                <IdCard size={22} color="var(--primary)" />
                <div style={{ flex: 1 }}>
                  <div className="strong num">{x.card.numberFormatted}</div>
                  <div className="muted" style={{ fontSize: 13 }}>{x.card.profile === 'EMPLOYE_GROUPE' ? 'Profil employé groupe' : 'Profil employé station'} · solde {money(x.card.balance)}</div>
                </div>
                <StatusBadge status={{ ACTIVE: 'Active', A_ACTIVER: 'À activer', BLOQUEE: 'Bloquée' }[x.card.status]} />
                <Button size="sm" onClick={() => navigate(`/cartes/${x.card.id}`)}>Ouvrir</Button>
              </div>
            ) : (
              <div className="row" style={{ gap: 12 }}>
                <span className="muted" style={{ flex: 1 }}>Aucune carte : elle sert à suivre l’historique de l’employé (employé du mois, clôture de poste).</span>
                {can('CARTES_EMPLOYES') && x.active && (
                  <Button size="sm" variant="primary" icon={IdCard} onClick={() => run(() => post(`/employees/${x.id}/card`, {}), 'Carte employé créée · à activer par une autre personne')}>
                    Créer la carte
                  </Button>
                )}
              </div>
            )}

            {(x.shifts || []).length > 0 && (
              <>
                <div className="section-label" style={{ marginTop: 18 }}>Derniers postes</div>
                <table className="table">
                  <thead><tr><th>Début</th><th>Fin</th><th className="r">Espèces attendues</th><th className="r">Remis</th><th className="r">Écart</th></tr></thead>
                  <tbody>
                    {x.shifts.slice(0, 5).map((sh) => (
                      <tr key={sh.id}>
                        <td className="num">{dateTime(sh.startedAt)}</td>
                        <td className="num muted">{sh.endedAt ? dateTime(sh.endedAt) : 'En cours'}</td>
                        <td className="r num">{sh.expectedCash != null ? fcfa(sh.expectedCash) : '—'}</td>
                        <td className="r num">{fcfa(sh.remittedCash || 0)}</td>
                        <td className="r num strong" style={{ color: sh.gap > 0 && sh.endedAt ? 'var(--red)' : undefined }}>{sh.endedAt ? fcfa(sh.gap) : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
            {edit && <EmployeeFormModal employee={x} roles={roles} stations={stations} onClose={() => setEdit(false)} onSaved={() => { e.reload(); onChanged(); }} />}
          </>
        )}
      </Async>
    </Modal>
  );
}

/** Matrice des rôles et droits, modifiable avec le droit Paramètres. */
function RightsMatrix() {
  const notify = useToast();
  const { can } = useSession();
  const matrix = useApi('/roles');
  const [edit, setEdit] = useState(false);
  const [draft, setDraft] = useState(null);
  const [all, setAll] = useState(false);
  useEffect(() => {
    if (matrix.data) setDraft(Object.fromEntries(matrix.data.rights.map((r) => [r.code, [...r.roles]])));
  }, [matrix.data]);
  const save = async () => {
    try {
      for (const role of matrix.data.roles) {
        const wanted = matrix.data.rights.filter((r) => draft[r.code].includes(role.code)).map((r) => r.code);
        const before = matrix.data.rights.filter((r) => r.roles.includes(role.code)).map((r) => r.code);
        if (wanted.sort().join() !== before.sort().join()) await put(`/roles/${role.code}/rights`, { rights: wanted });
      }
      notify('Droits enregistrés · appliqués à la prochaine requête des utilisateurs');
      setEdit(false);
      matrix.reload();
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Card>
      <div className="card-head" style={{ paddingBottom: 14 }}>
        <div>
          <div className="card-title">Rôles & droits</div>
          <div className="card-sub">{matrix.data ? `${matrix.data.roles.length} rôles · ${matrix.data.rights.length} droits` : '…'}</div>
        </div>
        <span className="row" style={{ gap: 6 }}>
          <Button size="sm" onClick={() => setAll(!all)}>{all ? 'Droits principaux' : 'Tous les droits'}</Button>
          {can('PARAMETRES') && !edit && <button className="link-more" title="Modifier" onClick={() => setEdit(true)}><Pencil size={16} /></button>}
          {edit && (
            <>
              <Button size="sm" icon={X} onClick={() => { setEdit(false); matrix.reload(); }}>Annuler</Button>
              <Button size="sm" variant="primary" icon={Check} onClick={save}>Enregistrer</Button>
            </>
          )}
        </span>
      </div>
      <Async state={matrix}>
        {(m) => (
          <div style={{ overflowX: 'auto' }}>
            <table className="table">
              <thead>
                <tr><th>Droit</th>{m.roles.map((r) => <th key={r.code} className="c" style={{ padding: '11px 6px' }} title={r.label}>{ROLE_SHORT[r.code] || r.label}</th>)}</tr>
              </thead>
              <tbody>
                {m.rights.filter((r) => all || r.sort <= 7).map((right) => (
                  <tr key={right.code}>
                    <td style={{ fontWeight: 500, padding: '13px 8px 13px 20px', whiteSpace: 'nowrap' }}>{right.label}</td>
                    {m.roles.map((role) => {
                      const on = draft?.[right.code]?.includes(role.code);
                      const locked = right.code === 'PARAMETRES' && role.code === 'ADMIN';
                      return (
                        <td key={role.code} className="c" style={{ padding: '10px 6px' }}>
                          <button
                            type="button"
                            disabled={!edit || locked}
                            onClick={() => setDraft({ ...draft, [right.code]: on ? draft[right.code].filter((x) => x !== role.code) : [...draft[right.code], role.code] })}
                            style={{ border: 0, background: 'transparent', padding: 0, cursor: edit && !locked ? 'pointer' : 'default', opacity: 1 }}
                          >
                            {on ? (
                              <span style={{ display: 'inline-grid', placeItems: 'center', width: 22, height: 22, borderRadius: 5, background: 'var(--green-50)', color: 'var(--green)' }}>
                                <Check size={14} strokeWidth={2.6} />
                              </span>
                            ) : (
                              <span style={{ display: 'inline-block', width: 16, height: 1.5, background: edit ? '#94a3b8' : '#d5dce5' }} />
                            )}
                          </button>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Async>
    </Card>
  );
}

export default function Employes() {
  const { can, user } = useSession();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [role, setRole] = useState('');
  const [stationId, setStationId] = useState('');
  const [page, setPage] = useState(0);
  const [modal, setModal] = useState(params.get('id') ? { type: 'sheet', id: Number(params.get('id')) } : null);

  const month = useApi('/employees/of-the-month');
  const summary = useApi(can('EMPLOYES') ? '/employees/summary' : null);
  const list = useApi(`/employees${qs({ q: search, role, stationId, page, size: 12 })}`);
  const roles = useApi('/roles');
  const stations = useApi('/stations');
  useLiveReload(list.reload, ['shift'], 5000);

  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(q.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const w = month.data?.winner;
  const s = summary.data;
  const close = () => {
    setModal(null);
    if (params.get('id')) setParams({});
  };

  return (
    <>
      <PageHeader
        title="Employés & rôles"
        subtitle="Fiches employés avec photo, rôles et droits d’accès, employé du mois"
        actions={
          <>
            <Button icon={Download} disabled={!list.data?.items.length} onClick={() => exportCsv('employes.csv', [
              { label: 'Matricule', value: 'matricule' }, { label: 'Nom', value: 'name' }, { label: 'Rôle', value: 'roleLabel' },
              { label: 'Station', value: 'stationName' }, { label: 'Email', value: 'email' }, { label: 'Téléphone', value: 'phone' },
            ], list.data.items)}>Exporter</Button>
            {can('EMPLOYES') && <Button variant="primary" icon={UserPlus} disabled={!roles.data || !stations.data} onClick={() => setModal({ type: 'new' })}>Nouvel employé</Button>}
          </>
        }
      />

      <div className="grid mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 300px' }}>
        <div
          className="row"
          style={{
            position: 'relative', overflow: 'hidden', borderRadius: 'var(--radius)', color: '#fff', padding: '0 34px', gap: 26, minHeight: 150,
            background: 'linear-gradient(110deg, #13306b 0%, #174a92 55%, #1b66b8 100%)',
          }}
        >
          <div style={{ position: 'absolute', right: -40, top: -10, width: 230, height: 230, borderRadius: '50%', border: '24px solid rgba(90,169,53,.45)' }} />
          <div style={{ position: 'absolute', right: -80, top: -50, width: 330, height: 330, borderRadius: '50%', border: '1px solid rgba(255,255,255,.12)' }} />
          <div style={{ position: 'relative' }}>
            {w?.photoUrl ? (
              <img src={fileUrl(w.photoUrl)} alt="" style={{ width: 76, height: 76, borderRadius: '50%', objectFit: 'cover', border: '3px solid #fff' }} />
            ) : (
              <div style={{ width: 76, height: 76, borderRadius: '50%', background: '#fff', color: 'var(--primary)', display: 'grid', placeItems: 'center', fontSize: 26, fontWeight: 700 }}>
                {w?.initials || '—'}
              </div>
            )}
            <span style={{ position: 'absolute', right: -4, bottom: -2, width: 26, height: 26, borderRadius: '50%', background: '#f6c445', border: '2px solid #fff', display: 'grid', placeItems: 'center', color: '#7a4b00' }}>
              <Crown size={13} strokeWidth={2.4} />
            </span>
          </div>
          <div style={{ position: 'relative', whiteSpace: 'nowrap' }}>
            <div className="row" style={{ gap: 7, fontSize: 13, opacity: 0.85 }}><Trophy size={15} /> Meilleur employé · {month.data?.label || '…'}</div>
            <div style={{ fontSize: 23, fontWeight: 700, marginTop: 6 }}>{w?.name || (month.data ? 'Pas encore de ventes' : '…')}</div>
            <div style={{ fontSize: 14, opacity: 0.8, marginTop: 4 }}>{w ? `${w.roleLabel} · ${w.stationName || 'Siège'}` : ''}</div>
          </div>
          <div className="row" style={{ marginLeft: 'auto', gap: 12, position: 'relative' }}>
            {[['Produits vendus', w?.productsSold], ['Litres servis', w?.liters], ['Lubrifiants', w?.lubricants]].map(([l, v]) => (
              <div key={l} style={{ width: 112, whiteSpace: 'nowrap', border: '1px solid rgba(255,255,255,.22)', background: 'rgba(255,255,255,.1)', borderRadius: 10, padding: '10px 14px' }}>
                <div style={{ fontSize: 12, opacity: 0.8 }}>{l}</div>
                <div className="num" style={{ fontSize: 19, fontWeight: 700, marginTop: 2 }}>{v == null ? '—' : num(v)}</div>
              </div>
            ))}
          </div>
        </div>
        <Card className="kpi">
          <div className="kpi-top"><span className="kpi-icon tone-blue"><Users size={18} /></span> Employés</div>
          <div className="kpi-value num">{s ? num(s.total) : list.data ? num(list.data.total) : '…'}</div>
          <div className="kpi-foot">{s ? `sur ${s.stationsTotal} station${s.stationsTotal > 1 ? 's' : ''}` : user.stationName || ''}</div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between" style={{ alignItems: 'flex-start' }}>
            <div>
              <div className="card-title">Équipe</div>
              <div className="card-sub">{list.data ? `${num(list.data.total)} employés` : '…'} · photo affichée sur la carte de l’employé</div>
            </div>
            {can('EMPLOYES') && <Button variant="primary" size="sm" icon={UserPlus} disabled={!roles.data || !stations.data} onClick={() => setModal({ type: 'new' })}>Nouvel employé</Button>}
          </div>
          <div className="row" style={{ gap: 10, marginTop: 18 }}>
            <SearchInput icon={Search} placeholder="Rechercher un employé…" style={{ flex: 1 }} value={q} onChange={(e) => setQ(e.target.value)} />
            <Select icon={CircleCheck} value={role} onChange={(v) => { setRole(v); setPage(0); }} placeholder="Rôle"
              options={(roles.data?.roles || []).map((r) => ({ value: r.code, label: r.label }))} />
            {!user.stationBound && (
              <Select icon={Fuel} value={stationId} onChange={(v) => { setStationId(v); setPage(0); }} placeholder="Station"
                options={(stations.data || []).map((x) => ({ value: x.id, label: x.shortName }))} />
            )}
          </div>
          <Async state={list}>
            {(data) => (data.items.length === 0 ? <Empty>Aucun employé</Empty> : (
              <>
                <div className="grid g3" style={{ gap: 14, marginTop: 18 }}>
                  {data.items.map((m) => (
                    <div key={m.id} className="tr-click" onClick={() => setModal({ type: 'sheet', id: m.id })}
                      style={{ position: 'relative', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 14px 14px', textAlign: 'center' }}>
                      <div style={{ display: 'flex', justifyContent: 'center' }}>
                        <Avatar initials={m.initials} color={avatarColor(m.id)} size={56} photo={fileUrl(m.photoUrl)} />
                      </div>
                      <div className="strong" style={{ fontSize: 14, marginTop: 12 }}>{m.name}</div>
                      <div className="muted" style={{ fontSize: 13, marginTop: 3 }}>{m.stationShortName || 'Siège Lomé'}</div>
                      <Badge tone={ROLE_TONES[m.role] || 'gray'} style={{ marginTop: 10 }}>{m.roleLabel}</Badge>
                      <div style={{ borderTop: '1px solid var(--border-2)', marginTop: 14, paddingTop: 12 }}>
                        <Button size="sm" icon={IdCard} style={{ fontWeight: 500 }}>
                          {m.cardId ? `Carte ${{ ACTIVE: 'active', A_ACTIVER: 'à activer', BLOQUEE: 'bloquée' }[m.cardStatus] || ''}` : 'Carte employé'}
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
                <div style={{ margin: '14px -20px -20px' }}><Pager data={data} page={page} onPage={setPage} label="employés" /></div>
              </>
            ))}
          </Async>
        </Card>
        <RightsMatrix />
      </div>

      {modal?.type === 'new' && (
        <EmployeeFormModal roles={roles.data.roles} stations={stations.data} onClose={close}
          onSaved={(r) => { list.reload(); summary.reload(); setModal({ type: 'sheet', id: r.id }); }} />
      )}
      {modal?.type === 'sheet' && roles.data && stations.data && (
        <EmployeeSheet id={modal.id} roles={roles.data.roles} stations={stations.data} onClose={close} onChanged={() => { list.reload(); summary.reload(); }} />
      )}
    </>
  );
}

