package tg.zener.station.extractions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Définition d'une extraction : colonnes, requête (SELECT / FROM / GROUP BY / ORDER BY) et filtres acceptés.
 * <p>
 * {@code filters} associe un nom de filtre ({@link ExtractionFilters#NAMES}) à sa condition SQL, par exemple
 * {@code "station" → "x.station_id = :stationId"} ; pour {@code "period"}, la valeur est la colonne de date
 * comparée à {@code :from} / {@code :to}. Les paramètres {@code :from} et {@code :to} sont toujours liés
 * (éventuellement nuls) : une sous-requête peut s'en servir avec {@code CAST(:from AS timestamptz) IS NULL OR …}.
 */
public record Extraction(String code, String section, String label, String description, List<Col> cols,
                         String select, String from, String baseWhere, String groupBy, String orderBy,
                         Map<String, String> filters) {

    /** Types de colonne : format à l'écran, en CSV et en XLSX. */
    public enum Type { TEXT, DATETIME, DATE, MONEY, QTY, INT }

    /** Colonne : alias SQL (snake_case), en-tête français, type, totalisable. */
    public record Col(String alias, String header, Type type, boolean sum) {
        public String key() {
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (char c : alias.toCharArray()) {
                if (c == '_') {
                    up = true;
                } else {
                    sb.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            return sb.toString();
        }
    }

    /** Une station peut-elle restreindre l'extraction (utilisateur rattaché à une station) ? */
    public boolean stationScoped() {
        return filters.containsKey("station");
    }

    // ------------------------------------------------------------------ construction fluide

    static Builder of(String code, String section, String label, String description) {
        return new Builder(code, section, label, description);
    }

    static final class Builder {
        private final String code;
        private final String section;
        private final String label;
        private final String description;
        private final List<Col> cols = new java.util.ArrayList<>();
        private String select;
        private String from;
        private String baseWhere;
        private String groupBy;
        private String orderBy;
        private final Map<String, String> filters = new LinkedHashMap<>();

        private Builder(String code, String section, String label, String description) {
            this.code = code;
            this.section = section;
            this.label = label;
            this.description = description;
        }

        Builder col(String alias, String header, Type type) {
            cols.add(new Col(alias, header, type, false));
            return this;
        }

        /** Colonne totalisée en bas du tableau. */
        Builder sum(String alias, String header, Type type) {
            cols.add(new Col(alias, header, type, true));
            return this;
        }

        Builder select(String sql) {
            this.select = sql;
            return this;
        }

        Builder from(String sql) {
            this.from = sql;
            return this;
        }

        Builder where(String sql) {
            this.baseWhere = sql;
            return this;
        }

        Builder groupBy(String sql) {
            this.groupBy = sql;
            return this;
        }

        Builder orderBy(String sql) {
            this.orderBy = sql;
            return this;
        }

        Builder filter(String name, String condition) {
            if (!ExtractionFilters.NAMES.contains(name)) {
                throw new IllegalArgumentException("Filtre inconnu : " + name);
            }
            filters.put(name, condition);
            return this;
        }

        Extraction build() {
            return new Extraction(code, section, label, description, List.copyOf(cols), select, from, baseWhere,
                    groupBy, orderBy, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(filters)));
        }
    }
}
