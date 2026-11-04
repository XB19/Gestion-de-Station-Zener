import { useState } from 'react';
import {
  Receipt, Plus, LayoutGrid, Fuel, Droplet, Flame, Package, CreditCard, ShoppingCart, ArrowLeftRight, CircleDollarSign,
  TriangleAlert, Pencil, Cylinder, Users, List, Sparkles, TrendingUp, TrendingDown, Mail,
} from 'lucide-react';
import { PageHeader, Card, Kpi, Trend, Badge, StatusBadge, Button, Segmented, Select, IcoBox } from '../components/ui.jsx';
import { Donut } from '../components/charts.jsx';

const PRICES = [
  [CreditCard, 'Carte classique', '1 000', '500', '500', '800'],
  [CreditCard, 'Carte VIP', '5 000', '3 000', '3 000', '4 000'],
  [Flame, 'Gaz butane 6 kg', '4 500', '4 100', '4 200', '4 300'],
  [Flame, 'Gaz butane 12,5 kg', '6 500', '6 000', '6 100', '6 250'],
  [Droplet, 'Huile moteur 5W30 · 5 L', '22 500', '19 500', '20 000', '21 000'],
  [Droplet, 'Huile 2T · 1 L', '3 500', '3 000', '3 100', '3 200'],
  [Package, 'Brûleur gaz', '3 000', '2 500', '2 600', '2 800'],
];

const STOCKS = [
  ['Gasoil', 'Bè Kpota', '2 150 L', 12, '3 400 L', '+9 %', 'Critique'],
  ['Gaz 6 kg', 'Kara Centre', '14', 22, '20', '+12 %', 'Bas'],
  ['Huile 5W30 · 5 L', 'Agoè Assiyéyé', '18', 30, '24', '+4 %', 'Bas'],
  ['Super', 'Adidogomé', '21 400 L', 71, '6 200 L', '+2 %', 'Normal'],
  ['Cartes classiques', 'Siège Lomé', '86', 16, '150', '+18 %', 'Critique'],
  ['Brûleurs gaz', 'Tsévié Nord', '42', 60, '15', '−3 %', 'Normal'],
];
const LEVEL_COLOR = { Critique: '#e5484d', Bas: '#f29e1f', Normal: '#5aa935' };

const BOTTLES = [
  { icon: Cylinder, tone: 'green', l: 'Pleines', v: '420', color: '#5aa935' },
  { icon: Cylinder, tone: 'gray', l: 'Vides', v: '138', color: '#cbd5e1' },
  { icon: Users, tone: 'blue', l: 'Chez les clients', v: '1 240', color: '#1b66b8' },
  { icon: TriangleAlert, tone: 'red', l: 'Endommagées', v: '12', color: '#e5484d' },
];

export default function VentesStocks() {
  const [tab, setTab] = useState('Vue d’ensemble');

  return (
    <>
      <PageHeader
        title="Ventes & stocks"
        subtitle="Cartes, gaz, lubrifiants et accessoires · prix par profil · stocks et alertes intelligentes"
        actions={
          <>
            <Button icon={Receipt}>Nouvel achat</Button>
            <Button variant="primary" icon={Plus}>Nouvelle vente</Button>
          </>
        }
      />

      <div className="mb20">
        <Segmented
          value={tab}
          onChange={setTab}
          items={[
            { label: 'Vue d’ensemble', icon: LayoutGrid },
            { label: 'Carburant', icon: Fuel },
            { label: 'Lubrifiants', icon: Droplet },
            { label: 'Gaz', icon: Flame },
            { label: 'Accessoires', icon: Package },
            { label: 'Cartes', icon: CreditCard },
          ]}
        />
      </div>

      <div className="grid g4 mb20">
        <Kpi icon={ShoppingCart} label="Ventes boutique du jour" value="832 300" unit="FCFA" foot={<><Trend value="9 %" /> gaz, lubrifiants, accessoires</>} />
        <Kpi icon={ArrowLeftRight} tone="orange" label="Bouteilles échangées" value="146" foot="consigne : 92 / 146" />
        <Kpi icon={CircleDollarSign} tone="navy" label="Achats du mois" value="41,2 M" unit="FCFA" foot="12 bons de livraison" />
        <Kpi icon={TriangleAlert} tone="red" label="Alertes de stock" value="7" foot={<><Badge tone="red">2 critiques</Badge> 5 à surveiller</>} />
      </div>

      <div className="grid mb20" style={{ gridTemplateColumns: 'minmax(0, 1fr) 420px', alignItems: 'start' }}>
        <Card>
          <div className="card-head" style={{ paddingBottom: 14 }}>
            <div>
              <div className="card-title">Prix par profil d’acheteur</div>
              <div className="card-sub">FCFA TTC · appliqués automatiquement selon le profil</div>
            </div>
            <Button size="sm" icon={Pencil}>Modifier les prix</Button>
          </div>
          <table className="table">
            <thead>
              <tr><th>Produit</th><th className="r">Grand public</th><th className="r">Employé station</th><th className="r">Employé groupe</th><th className="r">Partenaire</th></tr>
            </thead>
            <tbody>
              {PRICES.map(([Icon, n, a, b, c, d]) => (
                <tr key={n}>
                  <td>
                    <div className="row" style={{ gap: 12 }}>
                      <IcoBox icon={Icon} tone="gray" size={30} />
                      <span className="strong">{n}</span>
                    </div>
                  </td>
                  <td className="r num strong">{a}</td>
                  <td className="r num" style={{ color: 'var(--text-2)' }}>{b}</td>
                  <td className="r num" style={{ color: 'var(--text-2)' }}>{c}</td>
                  <td className="r num" style={{ color: 'var(--text-2)' }}>{d}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <Card className="card-pad">
          <div className="card-title">Bouteilles de gaz</div>
          <div className="card-sub">État du parc et consigne</div>
          <div className="row" style={{ gap: 20, marginTop: 18 }}>
            <Donut size={140} thickness={16} segments={[
              { value: 420, color: '#5aa935' }, { value: 138, color: '#cbd5e1' }, { value: 1240, color: '#1b66b8' }, { value: 12, color: '#e5484d' },
            ]}>
              <div>
                <div className="num" style={{ fontSize: 22, fontWeight: 700 }}>1 810</div>
                <div className="muted" style={{ fontSize: 12 }}>bouteilles</div>
              </div>
            </Donut>
            <div style={{ flex: 1 }}>
              {BOTTLES.map((b) => (
                <div key={b.l} className="list-item" style={{ padding: '10px 0' }}>
                  <IcoBox icon={b.icon} tone={b.tone} size={32} />
                  <span style={{ flex: 1 }}>{b.l}</span>
                  <span className="strong num" style={{ fontSize: 16 }}>{b.v}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="row" style={{ gap: 12, background: 'var(--surface-2)', borderRadius: 10, padding: '12px 14px', marginTop: 16 }}>
            <ArrowLeftRight size={18} color="var(--orange)" />
            <div style={{ flex: 1, fontSize: 13 }}>
              <div className="strong">Échange vide ⇄ pleine ·</div>
              <div>consigne 12,5 kg : 15 000 F</div>
            </div>
            <Button size="sm">Enregistrer un échange</Button>
          </div>
        </Card>
      </div>

      <Card>
        <div className="card-head" style={{ paddingBottom: 14, alignItems: 'center' }}>
          <div className="card-title">Stocks & alertes</div>
          <div className="row" style={{ gap: 8 }}>
            <Select icon={Fuel}>Toutes les stations</Select>
            <Button icon={List}>Inventaire</Button>
            <Button variant="primary" icon={Plus}>Entrée de stock</Button>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr><th>Produit</th><th>Station</th><th className="r">Stock</th><th style={{ width: 230 }}>Niveau</th><th>Seuil d’alerte</th><th>Tendance 7 j</th><th>État</th></tr>
          </thead>
          <tbody>
            {STOCKS.map(([p, s, st, lvl, seuil, tr, etat]) => (
              <tr key={p}>
                <td className="strong">{p}</td>
                <td>{s}</td>
                <td className="r num strong">{st}</td>
                <td><div className="progress" style={{ maxWidth: 170 }}><span style={{ width: `${lvl}%`, background: LEVEL_COLOR[etat] }} /></div></td>
                <td>
                  <span className="row num" style={{ gap: 8 }}>
                    {seuil}
                    <Badge tone="blue" style={{ height: 22, fontSize: 12 }}><Sparkles size={12} /> auto</Badge>
                  </span>
                </td>
                <td>
                  <span className="row num" style={{ gap: 4, fontWeight: 500 }}>
                    {tr.startsWith('−') ? <TrendingDown size={14} /> : <TrendingUp size={14} />} {tr}
                  </span>
                </td>
                <td><StatusBadge status={etat} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row" style={{ gap: 10, padding: '14px 20px', borderTop: '1px solid var(--border-2)', color: 'var(--text-2)' }}>
          <Mail size={17} color="var(--primary)" />
          Email automatique au gérant et à la logistique dès qu’un stock passe sous son seuil · seuils recalculés chaque nuit selon la consommation.
        </div>
      </Card>
    </>
  );
}
