package tg.zener.station.recharges;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.Db;

/**
 * Recharge en masse préparée dans l'application : sélection souple des cartes (flotte, titulaire, type, numéros
 * collés, nom, dépenses récentes, solde, inactivité), montant par carte modifiable ligne à ligne ou d'un coup
 * à l'écran, puis envoi au contrôle habituel du lot (POST /api/recharges/batches). La sélection s'exporte aussi en
 * fichier (CSV ou Excel) avec la colonne « montant » à remplir, relu tel quel par l'import.
 */
@RestController
@PreAuthorize("hasAuthority('RECHARGER')")
public class RechargeSelectionController {

    private final Db db;

    public RechargeSelectionController(Db db) {
        this.db = db;
    }

    /** Cartes actives répondant aux critères (2 000 au plus), avec solde, dépenses sur la période et dernière recharge. */
    @GetMapping("/api/recharges/selection")
    public List<Map<String, Object>> selection(@RequestParam(required = false) Long groupId, @RequestParam(required = false) Long holderId,
                                               @RequestParam(required = false) String type, @RequestParam(required = false) String q,
                                               @RequestParam(required = false) String numbers,
                                               @RequestParam(required = false) Integer spentDays,
                                               @RequestParam(required = false) Long minSpent, @RequestParam(required = false) Long maxSpent,
                                               @RequestParam(required = false) Long balanceBelow, @RequestParam(required = false) Long balanceAbove,
                                               @RequestParam(required = false) Integer inactiveDays,
                                               @RequestParam(required = false) Integer limit) {
        int days = spentDays == null || spentDays < 1 ? 30 : Math.min(spentDays, 365);
        // un numéro écrit par groupes (« 6011 2847 0093 ») reste un seul numéro
        String joined = numbers == null ? "" : numbers.replaceAll("(\\d{4})[ .-](\\d{4})[ .-](\\d{4})", "$1$2$3");
        List<String> nums = Arrays.stream(joined.split("[\\s,;]+"))
                .map(n -> n.replaceAll("[^0-9]", "")).filter(n -> !n.isEmpty()).toList();
        // numéro complet, 4 ou 5 derniers chiffres, ou fragment (6 à 11 chiffres), comme la recherche de carte partagée
        List<String> full = nums.stream().filter(n -> n.length() == 12).toList();
        List<String> last5 = nums.stream().filter(n -> n.length() == 4 || n.length() == 5).toList();
        List<String> frag = nums.stream().filter(n -> n.length() > 5 && n.length() < 12).toList();
        List<Map<String, Object>> rows = db.list("""
                \sSELECT c.id, c.number, c.type_code, ct.label AS type_label, c.balance, h.id AS holder_id, h.name AS holder_name,
                       h.kind AS holder_kind, g.name AS group_name, c.vehicle_plate, c.driver_name,
                       COALESCE(sp.spent, 0) AS spent, sp.last_sale_at, rc.last_recharge_at, rc.last_recharge_amount
                FROM card c JOIN card_type ct ON ct.code = c.type_code LEFT JOIN holder h ON h.id = c.holder_id
                LEFT JOIN card_group g ON g.id = c.group_id
                LEFT JOIN LATERAL (SELECT sum(x.total) AS spent, max(x.at) AS last_sale_at FROM sale x
                                   WHERE x.card_id = c.id AND x.at >= now() - make_interval(days => :d)) sp ON true
                LEFT JOIN LATERAL (SELECT r.created_at AS last_recharge_at, r.amount AS last_recharge_amount FROM recharge r
                                   WHERE r.card_id = c.id AND r.status = 'CREDITEE' ORDER BY r.created_at DESC LIMIT 1) rc ON true
                WHERE c.status = 'ACTIVE' AND c.type_code <> 'EMPLOYE'
                  AND (CAST(:g AS bigint) IS NULL OR c.group_id = :g)
                  AND (CAST(:h AS bigint) IS NULL OR c.holder_id = :h)
                  AND (CAST(:t AS text) IS NULL OR c.type_code = :t)
                  AND (CAST(:q AS text) IS NULL OR h.name ILIKE '%' || :q || '%' OR c.vehicle_plate ILIKE '%' || :q || '%'
                       OR c.driver_name ILIKE '%' || :q || '%')
                  AND (cardinality(CAST(:full AS text[])) + cardinality(CAST(:l5 AS text[])) + cardinality(CAST(:fr AS text[])) = 0
                       OR c.number = ANY(CAST(:full AS text[])) OR right(c.number, 5) = ANY(CAST(:l5 AS text[]))
                       OR right(c.number, 4) = ANY(CAST(:l5 AS text[]))
                       OR EXISTS (SELECT 1 FROM unnest(CAST(:fr AS text[])) f WHERE strpos(c.number, f) > 0))
                  AND (CAST(:mins AS bigint) IS NULL OR COALESCE(sp.spent, 0) >= :mins)
                  AND (CAST(:maxs AS bigint) IS NULL OR COALESCE(sp.spent, 0) <= :maxs)
                  AND (CAST(:bb AS bigint) IS NULL OR c.balance < :bb)
                  AND (CAST(:ba AS bigint) IS NULL OR c.balance > :ba)
                  AND (CAST(:ina AS int) IS NULL OR NOT EXISTS (SELECT 1 FROM sale x WHERE x.card_id = c.id
                                                                   AND x.at >= now() - make_interval(days => CAST(:ina AS int))))
                ORDER BY h.name NULLS LAST, g.name NULLS FIRST, c.number LIMIT :lim""",
                Db.p("d", days, "g", groupId, "h", holderId, "t", type == null || type.isBlank() ? null : type,
                        "q", q == null || q.isBlank() ? null : q.trim(), "full", db.array("text", full.toArray()),
                        "l5", db.array("text", last5.toArray()), "fr", db.array("text", frag.toArray()), "mins", minSpent, "maxs", maxSpent, "bb", balanceBelow,
                        "ba", balanceAbove, "ina", inactiveDays, "lim", limit == null ? 500 : Math.min(Math.max(limit, 1), 2000)));
        rows.forEach(r -> r.put("numberFormatted", CardService.format((String) r.get("number"))));
        return rows;
    }

    public record ExportLine(String cardNumber, Long amount, String label) {}

    public record ExportForm(@NotEmpty List<ExportLine> lines, String format, Integer spentDays) {}

    /** Fichier de recharge prérempli : numéro, montant (modifiable), libellé, puis titulaire, solde et dépenses (ignorés à l'import). */
    @PostMapping("/api/recharges/selection/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportForm f) throws Exception {
        int days = f.spentDays() == null ? 30 : f.spentDays();
        List<String> numbers = f.lines().stream().map(l -> CardService.normalize(l.cardNumber())).toList();
        Map<String, Map<String, Object>> info = new java.util.HashMap<>();
        db.list("""
                \sSELECT c.number, h.name AS holder_name, g.name AS group_name, c.balance,
                       (SELECT COALESCE(sum(x.total), 0) FROM sale x WHERE x.card_id = c.id AND x.at >= now() - make_interval(days => :d)) AS spent
                FROM card c LEFT JOIN holder h ON h.id = c.holder_id LEFT JOIN card_group g ON g.id = c.group_id
                WHERE c.number = ANY(:n)""", Db.p("n", db.array("text", numbers.toArray()), "d", days))
                .forEach(r -> info.put((String) r.get("number"), r));
        String[] head = {"numero_carte", "montant", "libelle", "titulaire", "flotte", "solde_actuel", "depense_" + days + "_jours"};
        List<Object[]> rows = new ArrayList<>();
        for (ExportLine l : f.lines()) {
            Map<String, Object> i = info.getOrDefault(CardService.normalize(l.cardNumber()), Map.of());
            rows.add(new Object[] {CardService.format(CardService.normalize(l.cardNumber())), l.amount(), l.label(), i.get("holderName"),
                    i.get("groupName"), i.get("balance"), i.get("spent")});
        }
        String name = "recharges_" + LocalDate.now();
        if ("xlsx".equalsIgnoreCase(f.format())) {
            try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sh = wb.createSheet("Recharges");
                Row hr = sh.createRow(0);
                for (int c = 0; c < head.length; c++) {
                    hr.createCell(c).setCellValue(head[c]);
                }
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sh.createRow(r + 1);
                    Object[] v = rows.get(r);
                    for (int c = 0; c < v.length; c++) {
                        if (v[c] instanceof Number n) {
                            row.createCell(c).setCellValue(n.doubleValue());
                        } else if (v[c] != null) {
                            row.createCell(c).setCellValue(String.valueOf(v[c]));
                        }
                    }
                }
                for (int c = 0; c < head.length; c++) {
                    sh.setColumnWidth(c, c == 3 ? 9000 : 5000);
                }
                wb.write(out);
                return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".xlsx\"")
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .body(out.toByteArray());
            }
        }
        StringBuilder csv = new StringBuilder("﻿").append(String.join(";", head)).append('\n');
        for (Object[] v : rows) {
            for (int c = 0; c < v.length; c++) {
                if (c > 0) {
                    csv.append(';');
                }
                csv.append(v[c] == null ? "" : String.valueOf(v[c]).replace(';', ','));
            }
            csv.append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
