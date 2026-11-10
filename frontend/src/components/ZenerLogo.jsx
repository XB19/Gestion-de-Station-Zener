// Logo officiel ZENER (fichiers du site zener : anneau bleu / vert et logo complet)
import logoUrl from '../assets/brand/zener-logo.webp';
import markUrl from '../assets/brand/zener-mark.webp';

// Logo complet 720 × 516 : l'anneau en haut, le mot « ZENER® » en bas (à partir de y = 330)
const LOGO_W = 720;
const LOGO_H = 516;
const WORD_TOP = 330;

/** Anneau seul. */
export function ZenerMark({ size = 34 }) {
  return <img src={markUrl} width={size} height={size} alt="ZENER" style={{ display: 'block', objectFit: 'contain' }} draggable={false} />;
}

/** Mot « ZENER® » seul, découpé dans le logo officiel ; size = hauteur des lettres. */
export function ZenerWordmark({ size = 22 }) {
  const k = size / (LOGO_H - WORD_TOP);
  return (
    <span
      role="img"
      aria-label="ZENER"
      style={{
        display: 'inline-block',
        width: LOGO_W * k,
        height: (LOGO_H - WORD_TOP) * k,
        backgroundImage: `url(${logoUrl})`,
        backgroundRepeat: 'no-repeat',
        backgroundSize: `${LOGO_W * k}px ${LOGO_H * k}px`,
        backgroundPosition: `0 ${-WORD_TOP * k}px`,
      }}
    />
  );
}

/** Logo complet (anneau au-dessus du mot), comme sur le site. */
export function ZenerFullLogo({ width = 160 }) {
  return <img src={logoUrl} width={width} height={(width * LOGO_H) / LOGO_W} alt="ZENER" style={{ display: 'block' }} draggable={false} />;
}

/** Version horizontale pour la barre latérale : anneau + mot. */
export default function ZenerLogo({ size = 34 }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 9 }}>
      <ZenerMark size={size} />
      <ZenerWordmark size={size * 0.7} />
    </span>
  );
}
