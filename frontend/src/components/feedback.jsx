import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { CircleCheck, CircleAlert, Info, X } from 'lucide-react';

// ---------------------------------------------------------------- notifications éphémères
const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const notify = useCallback((message, tone = 'success') => {
    const id = Math.random();
    setToasts((t) => [...t, { id, message, tone }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), tone === 'error' ? 6000 : 3500);
  }, []);
  return (
    <ToastContext.Provider value={notify}>
      {children}
      <div className="toasts">
        {toasts.map((t) => {
          const Icon = t.tone === 'error' ? CircleAlert : t.tone === 'info' ? Info : CircleCheck;
          return (
            <div key={t.id} className={`toast ${t.tone}`}>
              <Icon size={18} style={{ flexShrink: 0, marginTop: 1 }} />
              <span>{t.message}</span>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

/** notify(message, 'success' | 'error' | 'info') */
export function useToast() {
  return useContext(ToastContext);
}

// ---------------------------------------------------------------- fenêtre modale

export function Modal({ title, onClose, children, footer, wide }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose?.()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-label={title}>
        <div className="modal-head">
          <span className="t">{title}</span>
          <button className="link-more" onClick={onClose} title="Fermer"><X size={19} /></button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

/**
 * Formulaire en fenêtre modale : gère l'envoi, l'attente et l'erreur de l'API.
 * onSubmit renvoie une promesse ; en cas de succès la fenêtre se ferme.
 */
export function FormModal({ title, onClose, onSubmit, submitLabel = 'Enregistrer', children, wide, danger }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const submit = async (e) => {
    e?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSubmit();
      onClose();
    } catch (err) {
      setError(err.message || 'Erreur');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={title}
      onClose={onClose}
      wide={wide}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>Annuler</button>
          <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} type="submit" form="modal-form" disabled={busy}>
            {busy ? <span className="spinner" style={{ width: 15, height: 15 }} /> : null} {submitLabel}
          </button>
        </>
      }
    >
      <form id="modal-form" onSubmit={submit}>
        {children}
        {error && <div className="form-error">{error}</div>}
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- champs de formulaire

export function Field({ label, children, full, hint }) {
  return (
    <div className={full ? 'full' : undefined}>
      <label className="field-label">{label}</label>
      {children}
      {hint && <div className="muted" style={{ fontSize: 12.5, marginTop: 6 }}>{hint}</div>}
    </div>
  );
}

export function TextInput({ value, onChange, type = 'text', placeholder, required, className = '', ...rest }) {
  return (
    <label className="input">
      <input
        type={type}
        className={className}
        value={value ?? ''}
        placeholder={placeholder}
        required={required}
        onChange={(e) => onChange(e.target.value)}
        {...rest}
      />
    </label>
  );
}

export function SelectInput({ value, onChange, options, placeholder, required }) {
  return (
    <label className="input">
      <select value={value ?? ''} required={required} onChange={(e) => onChange(e.target.value)}>
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => {
          const opt = typeof o === 'string' ? { value: o, label: o } : o;
          return <option key={opt.value} value={opt.value}>{opt.label}</option>;
        })}
      </select>
    </label>
  );
}
