package tg.zener.station.extractions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;

/**
 * Exécution des extractions : page affichée à l'écran (avec totaux) et fichiers CSV / XLSX lus en flux
 * (curseur PostgreSQL par lots de 1 000 lignes, classeur XLSX en écriture continue).
 */
@Service
public class ExtractionService {

    /** Limite d'une feuille Excel (1 048 576 lignes, moins l'en-tête). */
    static final int XLSX_MAX_ROWS = 1_048_000;

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Db db;
    private final NamedParameterJdbcTemplate streaming;
    private final TransactionTemplate readOnly;
    private final ZoneId zone = ZoneId.systemDefault();

    public ExtractionService(Db db, DataSource dataSource) {
        this.db = db;
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.setFetchSize(1000);
        this.streaming = new NamedParameterJdbcTemplate(jt);
        this.readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.readOnly.setReadOnly(true);
    }

    // ------------------------------------------------------------------ construction de la requête

    record Query(String sql, Map<String, Object> params) {}

    Query build(Extraction e, ExtractionFilters f) {
        Map<String, Object> params = f.params();
        List<String> conds = new ArrayList<>();
        if (e.baseWhere() != null) {
            conds.add(e.baseWhere());
        }
        for (Map.Entry<String, String> fe : e.filters().entrySet()) {
            String name = fe.getKey();
            if (f.value(name) == null) {
                continue;
            }
            if ("period".equals(name)) {
                if (f.from() != null) {
                    conds.add(fe.getValue() + " >= :from");
                }
                if (f.to() != null) {
                    conds.add(fe.getValue() + " < :to");
                }
            } else {
                conds.add(fe.getValue());
            }
        }
        StringBuilder sql = new StringBuilder(" SELECT ").append(e.select()).append(" FROM ").append(e.from());
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        if (e.groupBy() != null) {
            sql.append(" GROUP BY ").append(e.groupBy());
        }
        return new Query(sql.toString(), params);
    }

    /** Filtres renseignés que l'extraction ne sait pas appliquer (signalés plutôt qu'ignorés en silence). */
    List<String> unsupported(Extraction e, ExtractionFilters f) {
        List<String> out = new ArrayList<>();
        for (String name : ExtractionFilters.NAMES) {
            if (f.value(name) != null && !e.filters().containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ page affichée

    public Map<String, Object> page(Extraction e, ExtractionFilters f, Integer page, Integer size) {
        Query q = build(e, f);
        int pg = Page.page(page);
        int sz = Page.size(size);
        Map<String, Object> params = new LinkedHashMap<>(q.params());
        params.put("limit", sz);
        params.put("offset", (long) pg * sz);
        List<Map<String, Object>> items = db.list(q.sql() + " ORDER BY " + e.orderBy() + " LIMIT :limit OFFSET :offset", params);

        StringBuilder totals = new StringBuilder(" SELECT count(*) AS row_count");
        for (Extraction.Col c : e.cols()) {
            if (c.sum()) {
                totals.append(", sum(t.").append(c.alias()).append(") AS ").append(c.alias());
            }
        }
        totals.append(" FROM (").append(q.sql()).append(") t");
        Map<String, Object> sums = db.one(totals.toString(), q.params());
        long total = ((Number) sums.remove("rowCount")).longValue();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", e.code());
        out.put("label", e.label());
        out.put("columns", columns(e));
        out.put("items", items);
        out.put("total", total);
        out.put("page", pg);
        out.put("size", sz);
        out.put("totals", sums);
        out.put("ignoredFilters", unsupported(e, f));
        return out;
    }

    public long count(Extraction e, ExtractionFilters f) {
        Query q = build(e, f);
        return db.count(" SELECT count(*) FROM (" + q.sql() + ") t", q.params());
    }

    static List<Map<String, Object>> columns(Extraction e) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Extraction.Col c : e.cols()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("label", c.header());
            m.put("type", c.type().name());
            m.put("sum", c.sum());
            cols.add(m);
        }
        return cols;
    }

    // ------------------------------------------------------------------ lecture en flux

    @FunctionalInterface
    interface RowSink {
        void row(Object[] values) throws IOException;
    }

    private void stream(Extraction e, ExtractionFilters f, RowSink sink) {
        Query q = build(e, f);
        String sql = q.sql() + " ORDER BY " + e.orderBy();
        int n = e.cols().size();
        readOnly.executeWithoutResult(status -> streaming.query(sql, q.params(), (RowCallbackHandler) rs -> {
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                values[i] = value(rs, e.cols().get(i));
            }
            try {
                sink.row(values);
            } catch (IOException ex) {
                throw new SQLException("Écriture du fichier interrompue : " + ex.getMessage(), ex);
            }
        }));
    }

    private Object value(ResultSet rs, Extraction.Col c) throws SQLException {
        Object v = rs.getObject(c.alias());
        if (v == null) {
            return null;
        }
        return switch (c.type()) {
            case DATETIME -> v instanceof Timestamp t ? LocalDateTime.ofInstant(t.toInstant(), zone) : v;
            case DATE -> v instanceof java.sql.Date d ? d.toLocalDate() : v;
            case MONEY, INT -> v instanceof Number num ? num.longValue() : v;
            case QTY -> v instanceof BigDecimal b ? b : v instanceof Number num ? new BigDecimal(num.toString()) : v;
            case TEXT -> v.toString();
        };
    }

    // ------------------------------------------------------------------ CSV (UTF-8 avec BOM, « ; »)

    public void writeCsv(Extraction e, ExtractionFilters f, OutputStream os) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 1 << 16);
        w.write('﻿');
        List<String> headers = e.cols().stream().map(Extraction.Col::header).toList();
        w.write(String.join(";", headers.stream().map(ExtractionService::csv).toList()));
        w.write("\r\n");
        stream(e, f, values -> {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    w.write(';');
                }
                w.write(csv(format(values[i], e.cols().get(i).type())));
            }
            w.write("\r\n");
        });
        w.flush();
    }

    static String format(Object v, Extraction.Type type) {
        if (v == null) {
            return "";
        }
        return switch (type) {
            case DATETIME -> v instanceof LocalDateTime d ? d.format(DATE_TIME) : v.toString();
            case DATE -> v instanceof LocalDate d ? d.format(DATE_ONLY) : v.toString();
            case QTY -> v instanceof BigDecimal b ? String.format(Locale.FRANCE, "%.2f", b) : v.toString();
            default -> v.toString();
        };
    }

    static String csv(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ------------------------------------------------------------------ XLSX (écriture continue)

    public void writeXlsx(Extraction e, ExtractionFilters f, List<String[]> description, OutputStream os) throws IOException {
        SXSSFWorkbook wb = new SXSSFWorkbook(200);
        wb.setCompressTempFiles(true);
        try {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            CellStyle qty = wb.createCellStyle();
            qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle dateTime = wb.createCellStyle();
            dateTime.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
            CellStyle dateOnly = wb.createCellStyle();
            dateOnly.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));

            SXSSFSheet sheet = wb.createSheet(sheetName(e.label()));
            List<Extraction.Col> cols = e.cols();
            Row hr = sheet.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                hr.createCell(i).setCellValue(cols.get(i).header());
                hr.getCell(i).setCellStyle(head);
                sheet.setColumnWidth(i, width(cols.get(i)) * 256);
            }
            sheet.createFreezePane(0, 1);
            int[] r = {1};
            stream(e, f, values -> {
                if (r[0] > XLSX_MAX_ROWS) {
                    throw new IOException("Plus de " + XLSX_MAX_ROWS + " lignes : utilisez le format CSV ou réduisez la période");
                }
                Row row = sheet.createRow(r[0]++);
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    if (v == null) {
                        continue;
                    }
                    var cell = row.createCell(i);
                    if (v instanceof LocalDateTime d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateTime);
                    } else if (v instanceof LocalDate d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateOnly);
                    } else if (v instanceof BigDecimal b) {
                        cell.setCellValue(b.doubleValue());
                        cell.setCellStyle(qty);
                    } else if (v instanceof Long l) {
                        cell.setCellValue(l);
                        if (cols.get(i).type() == Extraction.Type.MONEY) {
                            cell.setCellStyle(money);
                        }
                    } else {
                        cell.setCellValue(v.toString());
                    }
                }
            });
            if (r[0] > 1) {
                sheet.setAutoFilter(new CellRangeAddress(0, r[0] - 1, 0, cols.size() - 1));
            }

            Sheet info = wb.createSheet("Filtres");
            int i = 0;
            for (String[] line : description) {
                Row row = info.createRow(i++);
                row.createCell(0).setCellValue(line[0]);
                row.getCell(0).setCellStyle(head);
                row.createCell(1).setCellValue(line[1]);
            }
            info.setColumnWidth(0, 24 * 256);
            info.setColumnWidth(1, 60 * 256);
            wb.write(os);
        } finally {
            wb.dispose();
            wb.close();
        }
    }

    static String sheetName(String label) {
        String s = label.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    static int width(Extraction.Col c) {
        return switch (c.type()) {
            case DATETIME -> 17;
            case DATE -> 12;
            case MONEY -> 15;
            case QTY -> 13;
            case INT -> 10;
            case TEXT -> Math.max(14, Math.min(40, c.header().length() + 6));
        };
    }

    // ------------------------------------------------------------------ description des filtres

    /** Libellés lisibles des filtres appliqués (feuille « Filtres », journal). */
    public List<String[]> describe(Extraction e, ExtractionFilters f, String user) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"Extraction", e.label()});
        out.add(new String[] {"Générée le", LocalDateTime.now(zone).format(DATE_TIME)});
        out.add(new String[] {"Par", user});
        if (f.from() != null || f.to() != null) {
            out.add(new String[] {"Période", (f.from() == null ? "…" : f.from().format(DATE_ONLY)) + " → "
                    + (f.to() == null ? "…" : f.to().format(DATE_ONLY))});
        }
        label(out, "Station", f.stationId(), "SELECT name FROM station WHERE id = :id");
        label(out, "Point de vente", f.pdvId(), "SELECT name FROM pdv WHERE id = :id");
        label(out, "Carte", f.cardId(), "SELECT substr(number, 1, 4) || ' ' || substr(number, 5, 4) || ' ' || substr(number, 9, 4) FROM card WHERE id = :id");
        label(out, "Client", f.holderId(), "SELECT name FROM holder WHERE id = :id");
        label(out, "Groupe de cartes", f.groupId(), "SELECT name FROM card_group WHERE id = :id");
        label(out, "Produit", f.productId(), "SELECT name FROM product WHERE id = :id");
        label(out, "Opérateur", f.employeeId(), "SELECT first_name || ' ' || last_name FROM employee WHERE id = :id");
        label(out, "Poste", f.shiftId(), """
                \sSELECT COALESCE(sc.name, 'Poste') || ' du ' || to_char(es.started_at, 'DD/MM/YYYY') || ' · '
                       || e.first_name || ' ' || e.last_name
                FROM employee_shift es JOIN employee e ON e.id = es.employee_id
                LEFT JOIN shift_config sc ON sc.id = es.config_id WHERE es.id = :id""");
        return out;
    }

    private void label(List<String[]> out, String name, Long id, String sql) {
        if (id == null) {
            return;
        }
        String v = db.value(sql, Db.p("id", id), String.class);
        if (v == null) {
            throw ApiException.notFound(name + " introuvable (n° " + id + ")");
        }
        out.add(new String[] {name, v});
    }
}
