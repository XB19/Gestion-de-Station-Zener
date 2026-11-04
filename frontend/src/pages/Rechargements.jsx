import { useState } from 'react';
import {
  History, Plus, Wallet, Send, FileSpreadsheet, Download, Upload, ChevronLeft, ChevronRight, Check, CircleCheck,
  Smartphone,
} from 'lucide-react';
import { PageHeader, Card, Badge, StatusBadge, Button, Segmented, Checkbox, KV } from '../components/ui.jsx';

const ROWS = [
  { n: '6011 2847 0093', h: 'Translom SARL · Camion 12', m: '250 000', s: 'Prête' },
  { n: '6011 2847 0094', h: 'Translom SARL · Camion 14', m: '250 000', s: 'Prête' },
  { n: '6011 2847 0101', h: 'Translom SARL · Direction', m: '750 000', s: 'Validation requise' },
  { n: '6011 2847 0102', h: 'Translom SARL · Camion 21', m: '250 000', s: 'Prête' },
  { n: '6011 2847 0115', h: 'Translom SARL · Camion 22', m: '1 200 000', s: 'Validation requise' },
  { n: '6011 2847 9999', h: '—', m: '150 000', s: 'Carte introuvable' },
  { n: '6011 2847 0120', h: 'Translom SARL · Navette 3', m: '180 000', s: 'Prête' },
];

const STEPS = [
  { t: 'Import & contrôle', s: 'Essi Lawson · 10:28', done: true },
  { t: 'Validation Comptable', s: 'Abla Gbeasor', current: true },
  { t: 'Validation DAF', s: 'Mawuli Kpodar · carte entreprise' },
  { t: 'Crédit des cartes', s: 'Automatique après approbation' },
];

export function ValidationSteps({ steps }) {
  return (
    <div>
      {steps.map((st, i) => (
        <div key={st.t} className="row" style={{ alignItems: 'flex-start', gap: 14, paddingBottom: 16 }}>
          <span
            style={{
              width: 30, height: 30, borderRadius: '50%', flexShrink: 0, display: 'grid', placeItems: 'center',
              fontWeight: 600, fontSize: 13,
              background: st.done ? 'var(--green-500)' : st.current ? 'var(--primary-50)' : 'var(--chip)',
              color: st.done ? '#fff' : st.current ? 'var(--primary)' : 'var(--muted)',
            }}
          >
            {st.done ? <Check size={16} strokeWidth={3} /> : i + 1}
          </span>
          <div>
            <div className="strong">{st.t}</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>{st.s}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

export default function Rechargements() {
  const [mode, setMode] = useState('En masse');
  const [checked, setChecked] = useState(ROWS.map((r) => r.s !== 'Carte introuvable'));
  const [amount, setAmount] = useState('25 000');

  return (
    <>
      <PageHeader
        title="Rechargements"
        subtitle="Recharges unitaires, à distance et en masse, avec validation des montants élevés"
        actions={
          <>
            <Button icon={History}>Historique</Button>
            <Button variant="primary" icon={Plus}>Nouvelle recharge</Button>
          </>
        }
      />

      <div className="mb20">
        <Segmented
          items={[{ label: 'Recharge unitaire', icon: Wallet }, { label: 'À distance', icon: Send }, { label: 'En masse', icon: FileSpreadsheet }]}
          value={mode}
          onChange={setMode}
        />
      </div>

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1fr) 360px', alignItems: 'start' }}>
        <div className="stack">
          <Card className="card-pad">
            <div className="card-title">Rechargement en masse</div>
            <div className="card-sub">Liste ou fichier de cartes (.xlsx, .csv)</div>
            <div className="row" style={{ gap: 14, marginTop: 18, border: '1.5px dashed #b9cde6', background: '#f7faff', borderRadius: 10, padding: '16px 18px' }}>
              <span className="ico-box tone-green" style={{ width: 44, height: 44 }}><FileSpreadsheet size={21} strokeWidth={1.7} /></span>
              <div style={{ flex: 1 }}>
                <div className="strong">recharges_translom_novembre.xlsx</div>
                <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>48 lignes · 24 Ko · importé il y a 1 min</div>
              </div>
              <Button icon={Download}>Modèle de fichier</Button>
              <Button icon={Upload}>Remplacer</Button>
            </div>
          </Card>

          <Card>
            <div className="card-head" style={{ paddingBottom: 16 }}>
              <div>
                <div className="card-title">Aperçu de l’import</div>
                <div className="card-sub">Contrôle automatique de chaque ligne avant envoi</div>
              </div>
              <div className="row" style={{ gap: 8 }}>
                <Badge tone="green">45 prêtes</Badge>
                <Badge tone="orange">2 à valider</Badge>
                <Badge tone="red">1 erreur</Badge>
              </div>
            </div>
            <table className="table">
              <thead>
                <tr><th style={{ width: 40 }} /><th>N° de carte</th><th>Titulaire</th><th className="r">Montant (F)</th><th>Contrôle</th></tr>
              </thead>
              <tbody>
                {ROWS.map((r, i) => (
                  <tr key={r.n}>
                    <td><Checkbox checked={checked[i]} onChange={(v) => setChecked(checked.map((x, j) => (j === i ? v : x)))} /></td>
                    <td className="num strong" style={{ letterSpacing: '.02em' }}>{r.n}</td>
                    <td>{r.h}</td>
                    <td className="r num strong">{r.m}</td>
                    <td><StatusBadge status={r.s} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="row between" style={{ padding: '14px 20px', borderTop: '1px solid var(--border-2)' }}>
              <span className="muted">Affichage de 7 sur 48 lignes</span>
              <div className="row" style={{ gap: 8 }}>
                <Button size="sm" className="btn-ghost-icon"><ChevronLeft size={16} /></Button>
                <Button size="sm" className="btn-ghost-icon"><ChevronRight size={16} /></Button>
              </div>
            </div>
          </Card>
        </div>

        <div className="stack">
          <Card className="card-pad">
            <div className="card-title">Récapitulatif</div>
            <div style={{ marginTop: 8 }}>
              <KV k="Cartes" v="47 valides" />
              <KV k="Montant total" v="14 830 000 F" />
              <KV k="Dont soumis à validation" v="1 950 000 F" />
              <KV k="Palier de validation" v="500 000 F / carte" />
              <KV k="Mode de paiement" v="Virement · Ecobank" />
            </div>
            <div className="section-label" style={{ marginTop: 16 }}>Circuit de validation</div>
            <ValidationSteps steps={STEPS} />
            <Button variant="primary" icon={Send} block style={{ height: 42 }}>Soumettre pour validation</Button>
            <Button block style={{ height: 42, marginTop: 10 }}>Créditer les 45 cartes prêtes</Button>
          </Card>

          <Card className="card-pad">
            <div className="card-title">Recharge à distance</div>
            <div className="card-sub">Nom + numéro de carte suffisent</div>
            <label className="field-label" style={{ marginTop: 18 }}>N° de carte</label>
            <label className="input" style={{ height: 42 }}><input className="num strong" defaultValue="6011 3302 1209" /></label>
            <label className="field-label" style={{ marginTop: 14 }}>Nom de l’utilisateur</label>
            <label className="input" style={{ height: 42 }}>
              <input defaultValue="Akossiwa Lawson" />
              <CircleCheck size={18} color="var(--green)" />
            </label>
            <div className="muted" style={{ fontSize: 13, marginTop: 8 }}>Nom et numéro concordent</div>
            <label className="field-label" style={{ marginTop: 14 }}>Montant</label>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 8 }}>
              {['5 000', '10 000', '25 000', 'Autre'].map((a) => (
                <button
                  key={a}
                  className="btn num"
                  onClick={() => setAmount(a)}
                  style={{
                    padding: 0, fontWeight: 500,
                    ...(amount === a ? { borderColor: 'var(--primary)', borderWidth: 1.5, color: 'var(--primary)', background: 'var(--primary-50)' } : {}),
                  }}
                >
                  {a}
                </button>
              ))}
            </div>
            <Button icon={Smartphone} block style={{ height: 42, marginTop: 14 }}>Envoyer via mobile money</Button>
          </Card>
        </div>
      </div>
    </>
  );
}
