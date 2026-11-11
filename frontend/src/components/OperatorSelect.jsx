import { UserRound } from 'lucide-react';
import { Select } from './ui.jsx';
import { useApi } from '../api/hooks.js';

/**
 * Filtre « Opérateur » des historiques : employés groupés par type d'opérateur (rôle).
 * Un utilisateur rattaché à une station ne voit que l'équipe de sa station (côté API).
 */
export default function OperatorSelect({ value, onChange, stationId, placeholder = 'Tous les opérateurs' }) {
  const ops = useApi(`/employees/operators${stationId ? `?stationId=${stationId}` : ''}`);
  return (
    <Select icon={UserRound} value={value} onChange={onChange} placeholder={placeholder}
      options={(ops.data || []).map((o) => ({ value: o.id, label: `${o.name} · ${o.roleLabel}${o.active ? '' : ' (inactif)'}` }))} />
  );
}
