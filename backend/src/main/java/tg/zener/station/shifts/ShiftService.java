package tg.zener.station.shifts;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.journal.Journal;

/**
 * Postes des pompistes et chefs de poste : ouverture, pause, remises d'espèces et clôture.
 * Espèces attendues = ventes payées en espèces pendant le poste ; reste à remettre = attendu − remis.
 */
@Service
public class ShiftService {

    private final Db db;
    private final Journal journal;
    private final LiveFeed live;

    public ShiftService(Db db, Journal journal, LiveFeed live) {
        this.db = db;
        this.journal = journal;
        this.live = live;
    }

    static final String SELECT = """
            \sSELECT es.id, es.employee_id, e.first_name || ' ' || e.last_name AS employee_name,
                   upper(left(e.first_name, 1) || left(e.last_name, 1)) AS employee_initials, r.label AS role_label,
                   es.station_id, s.short_name AS station_name, es.config_id, sc.name AS shift_name, sc.start_time, sc.end_time,
                   es.pumps, es.status, es.started_at, es.ended_at, es.note, es.closed_by,
                   cb.first_name || ' ' || cb.last_name AS closed_by_name,
                   COALESCE(es.expected_cash, (SELECT COALESCE(sum(x.total), 0) FROM sale x
                                               WHERE x.employee_shift_id = es.id AND x.payment_method = 'ESPECES')) AS expected_cash,
                   COALESCE((SELECT sum(amount) FROM shift_remittance sr WHERE sr.shift_id = es.id), 0) AS remitted_cash
            FROM employee_shift es
            JOIN employee e ON e.id = es.employee_id JOIN role r ON r.code = e.role_code
            JOIN station s ON s.id = es.station_id
            LEFT JOIN shift_config sc ON sc.id = es.config_id
            LEFT JOIN employee cb ON cb.id = es.closed_by""";

    /** Poste avec ses totaux : reste à remettre, ventes par mode de paiement, litres. */
    public Map<String, Object> summary(long shiftId) {
        Map<String, Object> s = db.require(SELECT + " WHERE es.id = :id", Db.p("id", shiftId), "Poste introuvable");
        decorate(s);
        s.putAll(db.one("""
                \sSELECT count(*) AS transactions, COALESCE(sum(x.total), 0) AS revenue,
                       COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'ESPECES'), 0) AS cash_sales,
                       COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'MOBILE_MONEY'), 0) AS mobile_money_sales,
                       COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'CARTE'), 0) AS card_sales,
                       COALESCE((SELECT sum(sl.quantity) FROM sale y JOIN sale_line sl ON sl.sale_id = y.id
                                 JOIN product p ON p.id = sl.product_id
                                 WHERE y.employee_shift_id = :id AND p.category = 'CARBURANT'), 0) AS liters
                FROM sale x WHERE x.employee_shift_id = :id""", Db.p("id", shiftId)));
        s.put("byProduct", db.list("""
                \sSELECT p.code, p.name, p.category, sum(sl.quantity) AS quantity, sum(sl.amount) AS amount
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                WHERE x.employee_shift_id = :id GROUP BY p.code, p.name, p.category ORDER BY amount DESC""", Db.p("id", shiftId)));
        s.put("remittances", db.list("""
                \sSELECT sr.id, sr.amount, sr.note, sr.at, e.first_name || ' ' || e.last_name AS received_by_name
                FROM shift_remittance sr JOIN employee e ON e.id = sr.received_by
                WHERE sr.shift_id = :id ORDER BY sr.at""", Db.p("id", shiftId)));
        return s;
    }

    /** Reste à remettre (négatif = excédent) et libellé d'état. */
    static void decorate(Map<String, Object> s) {
        long expected = ((Number) s.get("expectedCash")).longValue();
        long remitted = ((Number) s.get("remittedCash")).longValue();
        long remaining = expected - remitted;
        s.put("remainingCash", remaining);
        s.put("statusLabel", switch (String.valueOf(s.get("status"))) {
            case "PAUSE" -> "En pause";
            case "CLOS" -> remaining > 0 ? "Clos · reste à remettre" : remaining < 0 ? "Clos · excédent" : "Clos";
            default -> "En poste";
        });
    }

    public Long currentShiftId(long employeeId) {
        return db.value("SELECT id FROM employee_shift WHERE employee_id = :e AND status <> 'CLOS'", Db.p("e", employeeId), Long.class);
    }

    @Transactional
    public long start(long employeeId, long stationId, Long configId, String pumps) {
        if (currentShiftId(employeeId) != null) {
            throw ApiException.conflict("Vous avez déjà un poste en cours");
        }
        if (configId != null && db.count("SELECT count(*) FROM shift_config WHERE id = :c AND station_id = :s",
                Db.p("c", configId, "s", stationId)) == 0) {
            throw ApiException.notFound("Poste type introuvable dans cette station");
        }
        long id = db.insert("""
                \sINSERT INTO employee_shift (employee_id, station_id, config_id, pumps)
                VALUES (:e, :s, COALESCE(CAST(:c AS bigint), (SELECT id FROM shift_config WHERE station_id = :s
                                 AND ((start_time <= end_time AND localtime >= start_time AND localtime < end_time)
                                      OR (start_time > end_time AND (localtime >= start_time OR localtime < end_time)))
                                 LIMIT 1)), :p)""",
                Db.p("e", employeeId, "s", stationId, "c", configId, "p", pumps == null || pumps.isBlank() ? null : pumps.trim()));
        Map<String, Object> s = summary(id);
        journal.logAs(employeeId, "POSTE_OUVERT", Journal.MODIFICATIONS, "Prise de poste",
                s.get("employeeName") + (s.get("pumps") == null ? "" : " · " + s.get("pumps")), null, stationId, null);
        live.publish("shift", stationId, Map.of("shiftId", id, "status", "EN_POSTE"));
        return id;
    }

    @Transactional
    public void setPaused(long shiftId, boolean pause, long actorId) {
        Map<String, Object> s = db.require("SELECT id, status, station_id FROM employee_shift WHERE id = :id FOR UPDATE",
                Db.p("id", shiftId), "Poste introuvable");
        String expected = pause ? "EN_POSTE" : "PAUSE";
        if (!expected.equals(s.get("status"))) {
            throw ApiException.conflict(pause ? "Le poste n'est pas en cours" : "Le poste n'est pas en pause");
        }
        db.update("UPDATE employee_shift SET status = :st WHERE id = :id", Db.p("id", shiftId, "st", pause ? "PAUSE" : "EN_POSTE"));
        long station = ((Number) s.get("stationId")).longValue();
        journal.logAs(actorId, pause ? "POSTE_PAUSE" : "POSTE_REPRISE", Journal.MODIFICATIONS,
                pause ? "Pause" : "Reprise du poste", null, null, station, null);
        live.publish("shift", station, Map.of("shiftId", shiftId, "status", pause ? "PAUSE" : "EN_POSTE"));
    }

    /** Remise d'espèces (partielle ou solde), reçue par le chef de poste ou le gérant. */
    @Transactional
    public void remit(long shiftId, long amount, String note, long receiverId) {
        if (amount <= 0) {
            throw new ApiException("Montant invalide");
        }
        Map<String, Object> s = db.require("""
                \sSELECT es.id, es.employee_id, es.station_id, es.status, e.first_name || ' ' || e.last_name AS name
                FROM employee_shift es JOIN employee e ON e.id = es.employee_id WHERE es.id = :id FOR UPDATE OF es""",
                Db.p("id", shiftId), "Poste introuvable");
        if (((Number) s.get("employeeId")).longValue() == receiverId) {
            throw ApiException.forbidden("La remise doit être reçue par une autre personne");
        }
        db.update("INSERT INTO shift_remittance (shift_id, amount, received_by, note) VALUES (:s, :a, :u, :n)",
                Db.p("s", shiftId, "a", amount, "u", receiverId, "n", note == null || note.isBlank() ? null : note.trim()));
        syncRemitted(shiftId);
        long station = ((Number) s.get("stationId")).longValue();
        journal.logAs(receiverId, "REMISE_ESPECES", Journal.MODIFICATIONS, "Remise d'espèces",
                s.get("name") + ("CLOS".equals(s.get("status")) ? " · après clôture" : ""), amount, station, null);
    }

    /** Clôture : espèces attendues figées, remise finale enregistrée, écart calculé. */
    @Transactional
    public void close(long shiftId, long remittedCash, String note, long closerId) {
        Map<String, Object> s = db.require("""
                \sSELECT es.id, es.employee_id, es.station_id, es.status, e.first_name || ' ' || e.last_name AS name
                FROM employee_shift es JOIN employee e ON e.id = es.employee_id WHERE es.id = :id FOR UPDATE OF es""",
                Db.p("id", shiftId), "Poste introuvable");
        if ("CLOS".equals(s.get("status"))) {
            throw ApiException.conflict("Ce poste est déjà clôturé");
        }
        if (remittedCash < 0) {
            throw new ApiException("Montant invalide");
        }
        if (remittedCash > 0) {
            remit(shiftId, remittedCash, "Remise à la clôture", closerId);
        }
        long expected = db.sum("""
                \sSELECT COALESCE(sum(total), 0) FROM sale WHERE employee_shift_id = :id AND payment_method = 'ESPECES'""",
                Db.p("id", shiftId));
        db.update("""
                \sUPDATE employee_shift SET status = 'CLOS', ended_at = now(), expected_cash = :e, closed_by = :u,
                       note = COALESCE(:n, note)
                WHERE id = :id""",
                Db.p("id", shiftId, "e", expected, "u", closerId, "n", note == null || note.isBlank() ? null : note.trim()));
        syncRemitted(shiftId);
        long remitted = db.sum("SELECT remitted_cash FROM employee_shift WHERE id = :id", Db.p("id", shiftId));
        long gap = expected - remitted;
        long station = ((Number) s.get("stationId")).longValue();
        journal.logAs(closerId, "POSTE_CLOS", Journal.MODIFICATIONS, "Clôture de poste",
                s.get("name") + (gap == 0 ? " · caisse juste" : gap > 0 ? " · reste à remettre " + gap + " F"
                        : " · excédent " + (-gap) + " F"), expected, station, null);
        live.publish("shift", station, Map.of("shiftId", shiftId, "status", "CLOS"));
    }

    private void syncRemitted(long shiftId) {
        db.update("""
                \sUPDATE employee_shift SET remitted_cash = (SELECT COALESCE(sum(amount), 0) FROM shift_remittance WHERE shift_id = :id)
                WHERE id = :id""", Db.p("id", shiftId));
    }
}
