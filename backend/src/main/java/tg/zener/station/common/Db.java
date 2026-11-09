package tg.zener.station.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * Accès SQL : requêtes nommées, lignes renvoyées en Map avec des clés en camelCase
 * (prêtes à être sérialisées en JSON pour le frontend).
 */
@Component
public class Db {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public Db(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Paramètres : p("a", 1, "b", 2). Les valeurs nulles sont acceptées. */
    public static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.query(sql, new MapSqlParameterSource(params), rowMapper);
    }

    public List<Map<String, Object>> list(String sql) {
        return list(sql, Map.of());
    }

    /** Une ligne, ou null. */
    public Map<String, Object> one(String sql, Map<String, ?> params) {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Une ligne, ou une erreur 404 avec le message donné. */
    public Map<String, Object> require(String sql, Map<String, ?> params, String notFound) {
        Map<String, Object> row = one(sql, params);
        if (row == null) {
            throw ApiException.notFound(notFound);
        }
        return row;
    }

    public <T> T value(String sql, Map<String, ?> params, Class<T> type) {
        try {
            return jdbc.queryForObject(sql, new MapSqlParameterSource(params), type);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public long count(String sql, Map<String, ?> params) {
        Long n = value(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    public long sum(String sql, Map<String, ?> params) {
        Number n = value(sql, params, Number.class);
        return n == null ? 0 : n.longValue();
    }

    public int update(String sql, Map<String, ?> params) {
        return jdbc.update(sql, new MapSqlParameterSource(params));
    }

    /** INSERT … renvoyant l'identifiant généré (colonne id). */
    public long insert(String sql, Map<String, ?> params) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(sql, new MapSqlParameterSource(params), keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    // ------------------------------------------------------------------ conversion des lignes

    private final RowMapper<Map<String, Object>> rowMapper = this::mapRow;

    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            row.put(camel(md.getColumnLabel(i)), convert(rs.getObject(i)));
        }
        return row;
    }

    private Object convert(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (v instanceof Array a) {
            Object arr = a.getArray();
            return arr instanceof Object[] objs ? new ArrayList<>(Arrays.asList(objs)) : arr;
        }
        if (v instanceof PGobject pg) {
            if (("jsonb".equals(pg.getType()) || "json".equals(pg.getType())) && pg.getValue() != null) {
                try {
                    return json.readTree(pg.getValue());
                } catch (Exception e) {
                    return pg.getValue();
                }
            }
            return pg.getValue();
        }
        return v;
    }

    static String camel(String column) {
        StringBuilder sb = new StringBuilder(column.length());
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /** Valeur JSON pour une colonne jsonb : CAST(:x AS jsonb). */
    public String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tableau PostgreSQL pour un paramètre (smallint[], text[], bigint[]). */
    public java.sql.Array array(String type, Object[] values) {
        return jdbc.getJdbcTemplate().execute((java.sql.Connection c) -> c.createArrayOf(type, values));
    }
}
