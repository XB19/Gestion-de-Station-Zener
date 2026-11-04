import { useState } from 'react';
import { Mail, Download, CalendarDays, FileBarChart, Send, Link2, Check } from 'lucide-react';
import { PageHeader, Card, Badge, Button, Segmented, Trend, Avatar, IcoBox } from '../components/ui.jsx';
import { GroupedBars } from '../components/charts.jsx';

const REPORTS = [
  ['Agoè Assiyéyé', 'À commenter'],
  ['Bè Kpota', 'Commenté'],
  ['Adidogomé', 'Commenté'],
  ['Kara Centre', 'À commenter'],
  ['Sokodé', 'Commenté'],
  ['Atakpamé', 'Incomplet'],
];
const TONE = { 'À commenter': 'orange', Commenté: 'green', Incomplet: 'red' };

const BARS = [[40, 18], [86, 42], [72, 52], [96, 46], [86, 64], [74, 56], [118, 78], [58, 34], [24, 12]];

export default function Rapports() {
  const [tab, setTab] = useState('Rapports journaliers');
  const [sel, setSel] = useState('Agoè Assiyéyé');

  return (
    <>
      <PageHeader
        title="Rapports"
        subtitle="Rapports journaliers et de poste générés automatiquement · le gérant commente au lieu de rédiger"
        actions={
          <>
            <Button icon={Mail}>Planifier le résumé email</Button>
            <Button variant="primary" icon={Download}>Exporter</Button>
          </>
        }
      />

      <div className="mb20">
        <Segmented
          value={tab}
          onChange={setTab}
          items={[{ label: 'Rapports journaliers', count: 42 }, { label: 'Rapports de poste', count: 126 }, { label: 'Transactions' }, { label: 'Résumé email' }]}
        />
      </div>

      <div className="grid" style={{ gridTemplateColumns: '340px minmax(0, 1fr)', alignItems: 'start' }}>
        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Rapports du 01/11</div>
            <Button size="sm" icon={CalendarDays} className="num">01/11/2026</Button>
          </div>
          <div style={{ marginTop: 14 }}>
            {REPORTS.map(([n, s]) => {
              const active = n === sel;
              return (
                <button
                  key={n}
                  onClick={() => setSel(n)}
                  className="row"
                  style={{
                    width: '100%', gap: 14, textAlign: 'left', padding: '12px 14px', borderRadius: 10, marginBottom: 6,
                    background: active ? '#f5f9fe' : 'transparent', border: active ? '1px solid #bcd3ee' : '1px solid transparent',
                  }}
                >
                  <IcoBox icon={FileBarChart} tone="blue" size={34} />
                  <div style={{ flex: 1 }}>
                    <div className="strong" style={{ fontSize: 14 }}>{n}</div>
                    <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>01 nov. 2026</div>
                  </div>
                  <Badge tone={TONE[s]}>{s}</Badge>
                </button>
              );
            })}
          </div>
        </Card>

        <Card className="card-pad">
          <div className="row between">
            <div className="card-title">Rapport journalier · Zener {sel}</div>
            <div className="row" style={{ gap: 8 }}>
              <Button size="sm" icon={Download}>PDF</Button>
              <Button size="sm" icon={Send}>Envoyer</Button>
            </div>
          </div>
          <div className="row" style={{ gap: 8, marginTop: 10 }}>
            <Badge tone="blue">Généré automatiquement · 01/11 23:59</Badge>
            <Badge tone="orange">À commenter</Badge>
          </div>

          <div className="grid g4" style={{ gap: 12, marginTop: 18 }}>
            {[
              ['Recette', '3 112 400 F', <Trend value="8,2 %" />],
              ['Volume', '4 596 L', <Trend value="5,0 %" />],
              ['Transactions', '231', <Trend value="2 %" down />],
              ['Écart caisse', '0 F', <span style={{ color: 'var(--green)', fontWeight: 600 }}>Conforme</span>],
            ].map(([l, v, f]) => (
              <div key={l} className="mini-stat">
                <div className="l">{l}</div>
                <div className="v num">{v}</div>
                <div style={{ fontSize: 13, marginTop: 4 }}>{f}</div>
              </div>
            ))}
          </div>

          <div className="row between" style={{ marginTop: 22 }}>
            <div className="section-label" style={{ margin: 0 }}>Litres par heure</div>
            <div className="row" style={{ gap: 16 }}>
              <span className="legend"><i style={{ background: '#1b66b8' }} /> Super</span>
              <span className="legend"><i style={{ background: '#8cc63f' }} /> Gasoil</span>
            </div>
          </div>
          <div style={{ marginTop: 12 }}>
            <GroupedBars data={BARS} labels={['6h', '8h', '10h', '12h', '14h', '16h', '18h', '20h', '22h']} height={170} />
          </div>

          <div className="section-label" style={{ marginTop: 22 }}>Rapports de poste</div>
          <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr><th>Poste</th><th>Chef de poste</th><th className="r">Litres</th><th className="r">Recette (F)</th><th>Écart</th></tr>
              </thead>
              <tbody>
                <tr><td>Matin 06h–14h</td><td>Yao Agbeko</td><td className="r num">1 924</td><td className="r num">1 302 300</td><td><Badge tone="green">0 F</Badge></td></tr>
                <tr><td>Soir 14h–22h</td><td>Kodjo Agbodjan</td><td className="r num">2 318</td><td className="r num">1 568 900</td><td><Badge tone="green">0 F</Badge></td></tr>
                <tr><td>Nuit 22h–06h · autonome</td><td>—</td><td className="r num">354</td><td className="r num">241 200</td><td><Badge tone="navy">Mode auto</Badge></td></tr>
              </tbody>
            </table>
          </div>

          <div className="section-label" style={{ marginTop: 22 }}>Commentaire du gérant</div>
          <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
            <textarea
              className="textarea"
              style={{ border: 0, minHeight: 100, borderRadius: 0, fontSize: 14, lineHeight: 1.6, boxShadow: 'none' }}
              defaultValue={'Bonne journée. Pic à 18h lié à la sortie des bureaux. Pistolet P6 toujours hors service, technicien prévu mardi. Gasoil à surveiller : livraison demandée pour mercredi.'}
            />
            <div className="row" style={{ gap: 12, padding: '12px 16px', background: 'var(--surface-2)', borderTop: '1px solid var(--border-2)' }}>
              <Avatar initials="KA" color="blue" size={34} />
              <div style={{ flex: 1 }}>
                <div className="strong">Kossi Amegah</div>
                <div className="muted" style={{ fontSize: 13 }}>Gérant · brouillon enregistré 08:12</div>
              </div>
              <Button size="sm" icon={Link2}>Joindre</Button>
              <Button size="sm" variant="primary" icon={Check}>Valider le rapport</Button>
            </div>
          </div>
        </Card>
      </div>
    </>
  );
}
