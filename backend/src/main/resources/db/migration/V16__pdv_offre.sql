-- Ce que vend chaque point de vente : tous ne vendent pas des cartes (stand de lubrifiants, de gaz, salon…).
-- Gammes Zener connues + « Autre » précisé en texte libre.
ALTER TABLE pdv
    ADD COLUMN sells       text[] NOT NULL DEFAULT '{CARTES}',
    ADD COLUMN sells_other text;

ALTER TABLE pdv ADD CONSTRAINT pdv_sells_check CHECK (
    cardinality(sells) > 0
    AND sells <@ ARRAY['CARTES', 'LUBRIFIANTS', 'GAZ', 'ACCESSOIRES', 'ZEN_MOBILITY', 'AUTRE']::text[]
    AND (NOT ('AUTRE' = ANY (sells)) OR length(trim(COALESCE(sells_other, ''))) > 0));

-- points de vente existants : cartes + gammes des produits déjà proposés
UPDATE pdv p
SET sells = ARRAY(SELECT DISTINCT x FROM unnest(p.sells || ARRAY(
        SELECT DISTINCT CASE pr.category WHEN 'LUBRIFIANT' THEN 'LUBRIFIANTS' WHEN 'GAZ' THEN 'GAZ' WHEN 'ACCESSOIRE' THEN 'ACCESSOIRES' END
        FROM pdv_product pp JOIN product pr ON pr.id = pp.product_id
        WHERE pp.pdv_id = p.id AND pr.category IN ('LUBRIFIANT', 'GAZ', 'ACCESSOIRE'))) x ORDER BY x);
