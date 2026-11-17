import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Rocket, CheckCircle2, Circle, Building2, Bell, Save, ChevronRight } from 'lucide-react';
import { PageHeader, Card, Badge, Button, Checkbox, Async, Progress } from '../components/ui.jsx';
import { Field, TextInput, useToast } from '../components/feedback.jsx';
import { useApi } from '../api/hooks.js';
import { put } from '../api/client.js';

const ROLES = [['ADMIN', 'Admin'], ['GERANT', 'Gérant'], ['CHEF_POSTE', 'Chef de poste'], ['POMPISTE', 'Pompiste'], ['COMPTABLE', 'Comptable'],
  ['DAF', 'DAF'], ['AGENT_PDV', 'Agent PDV'], ['LIVREUR', 'Livreur']];

function Company({ company, onSaved }) {
  const notify = useToast();
  const [f, setF] = useState(company);
  const [busy, setBusy] = useState(false);
  useEffect(() => setF(company), [company]);
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  const save = async () => {
    setBusy(true);
    try {
      await put('/settings', { 'company.name': f.name, 'company.legal': f.legal, 'company.address': f.address, 'company.phone': f.phone,
        'company.email': f.email });
      notify('Informations de l’entreprise enregistrées');
      onSaved();
    } catch (e) {
      notify(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card className="card-pad">
      <div className="row between">
        <div className="row" style={{ gap: 8 }}><Building2 size={18} color="var(--primary)" /><span className="card-title">Entreprise</span></div>
        <Button size="sm" variant="primary" icon={Save} disabled={busy} onClick={save}>Enregistrer</Button>
      </div>
      <div className="form-grid" style={{ marginTop: 12 }}>
        <Field label="Nom commercial"><TextInput value={f.name} onChange={set('name')} /></Field>
        <Field label="Raison sociale"><TextInput value={f.legal} onChange={set('legal')} /></Field>
        <Field label="Adresse" full><TextInput value={f.address} onChange={set('address')} /></Field>
        <Field label="Téléphone"><TextInput value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Email"><TextInput type="email" value={f.email} onChange={set('email')} /></Field>
      </div>
    </Card>
  );
}

/** Règles d'envoi : qui reçoit quoi, par la cloche et (ou) par email. */
function Rules() {
  const notify = useToast();
  const rules = useApi('/notifications/rules');
  const update = async (r, patch) => {
    try {
      rules.setData(await put(`/notifications/rules/${r.kind}`, patch));
    } catch (e) {
      notify(e.message, 'error');
    }
  };
  return (
    <Card>
      <div className="card-head">
        <div className="row" style={{ gap: 8 }}><Bell size={18} color="var(--primary)" /><span className="card-title">Notifications et rappels</span></div>
        <span className="muted" style={{ fontSize: 13 }}>Les rôles rattachés à une station ne reçoivent que celles de leur station</span>
      </div>
      <Async state={rules}>
        {(rows) => (
          <div className="table-wrap"><table className="table">
            <thead><tr><th>Événement</th><th>Destinataires</th><th>Email</th><th>Active</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.kind} style={{ opacity: r.active ? 1 : 0.55 }}>
                  <td className="strong">{r.label}</td>
                  <td>
                    <div className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
                      {ROLES.map(([code, label]) => {
                        const on = r.roles.includes(code);
                        return (
                          <button key={code} type="button" className={`badge ${on ? 'blue' : 'gray'}`} style={{ border: 0, height: 24, cursor: 'pointer', fontSize: 11.5 }}
                            onClick={() => update(r, { roles: on ? r.roles.filter((x) => x !== code) : [...r.roles, code] })}>{label}</button>
                        );
                      })}
                      {r.roles.length === 0 && <span className="muted" style={{ fontSize: 12 }}>personne concernée (demandeur, livreur…)</span>}
                    </div>
                  </td>
                  <td><Checkbox checked={r.email} onChange={(v) => update(r, { email: v })} /></td>
                  <td><Checkbox checked={r.active} onChange={(v) => update(r, { active: v })} /></td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </Async>
    </Card>
  );
}

export default function MiseEnRoute() {
  const status = useApi('/setup/status');
  return (
    <>
      <PageHeader title="Mise en route du réseau" subtitle="Ce qui est prêt et ce qui reste à configurer avant l'exploitation ; chaque point mène à l'écran où le faire." />
      <Async state={status}>
        {(s) => (
          <div className="grid split" style={{ gridTemplateColumns: 'minmax(0, 1.1fr) minmax(0, 1fr)', alignItems: 'start' }}>
            <Card className="card-pad">
              <div className="row between">
                <div className="row" style={{ gap: 8 }}><Rocket size={18} color="var(--primary)" /><span className="card-title">Étapes</span></div>
                <Badge tone={s.complete ? 'green' : 'orange'}>{s.done} / {s.total}</Badge>
              </div>
              <div className="row" style={{ gap: 12, margin: '12px 0' }}>
                <Progress value={Math.round((s.done / s.total) * 100)} color={s.complete ? 'var(--green-500)' : 'var(--primary)'} />
                <span className="muted num" style={{ fontSize: 13 }}>{Math.round((s.done / s.total) * 100)} %</span>
              </div>
              {s.items.map((i) => (
                <Link key={i.key} to={i.link} className="row" style={{ gap: 10, padding: '10px 4px', borderBottom: '1px solid var(--border)', color: 'inherit' }}>
                  {i.done ? <CheckCircle2 size={18} color="var(--green)" /> : <Circle size={18} color="var(--muted-2)" />}
                  <span style={{ flex: 1 }}>
                    <span className={i.done ? '' : 'strong'}>{i.label}</span>
                    {i.detail && <span className="muted" style={{ display: 'block', fontSize: 12.5 }}>{i.detail}</span>}
                  </span>
                  <ChevronRight size={16} color="var(--muted-2)" />
                </Link>
              ))}
              <div className="muted" style={{ fontSize: 12.5, marginTop: 10 }}>
                Chaque station a aussi sa propre liste de mise en route (fiche de la station) ; chaque nouvel employé, la sienne (écran Employés).
              </div>
            </Card>
            <div className="stack">
              <Company company={s.company} onSaved={status.reload} />
              <Rules />
            </div>
          </div>
        )}
      </Async>
    </>
  );
}
