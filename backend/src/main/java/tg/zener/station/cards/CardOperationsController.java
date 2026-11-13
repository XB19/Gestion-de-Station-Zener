package tg.zener.station.cards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.security.Auth;

/** Transferts entre cartes, listes (noire, grise, blanche), remplacement, plafond de crédit. */
@RestController
public class CardOperationsController {

    private final Db db;
    private final CardOperations ops;

    public CardOperationsController(Db db, CardOperations ops) {
        this.db = db;
        this.ops = ops;
    }

    public record TransferForm(@NotNull Long toCardId, @Positive long amount, String note) {}

    @PostMapping("/api/cards/{id}/transfer")
    @PreAuthorize("hasAuthority('RECHARGER') or hasAuthority('CARTES_GERER') or hasAuthority('FLOTTES')")
    public Map<String, Object> transfer(@PathVariable long id, @Valid @RequestBody TransferForm f) {
        return ops.transfer(id, f.toCardId(), f.amount(), blank(f.note()), Auth.user().id());
    }

    /** Transferts d'une carte (envoyés et reçus). */
    @GetMapping("/api/cards/{id}/transfers")
    public List<Map<String, Object>> transfers(@PathVariable long id) {
        return db.list("""
                \sSELECT t.id, t.ref, t.at, t.amount, t.reason, t.note, t.from_card_id, f.number AS from_number, t.to_card_id,
                       c.number AS to_number, e.first_name || ' ' || e.last_name AS employee_name
                FROM card_transfer t JOIN card f ON f.id = t.from_card_id JOIN card c ON c.id = t.to_card_id
                LEFT JOIN employee e ON e.id = t.employee_id
                WHERE t.from_card_id = :id OR t.to_card_id = :id ORDER BY t.at DESC LIMIT 100""", Db.p("id", id));
    }

    public record ReplaceForm(@NotBlank @Pattern(regexp = "PERTE|VOL|DETERIORATION|AUTRE") String reason, String newNumber, String note) {}

    @PostMapping("/api/cards/{id}/replace")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> replace(@PathVariable long id, @Valid @RequestBody ReplaceForm f) {
        return ops.replace(id, f.reason(), blank(f.newNumber()), blank(f.note()), Auth.user().id());
    }

    public record CreditForm(@NotNull @Min(0) Long creditLimit) {}

    @PutMapping("/api/cards/{id}/credit")
    @PreAuthorize("hasAuthority('CREDIT')")
    public Map<String, Object> credit(@PathVariable long id, @Valid @RequestBody CreditForm f) {
        ops.creditLimit(id, f.creditLimit(), Auth.user().id());
        return db.one("SELECT id, balance, credit_limit FROM card WHERE id = :id", Db.p("id", id));
    }

    // ------------------------------------------------------------------ listes

    @GetMapping("/api/card-lists")
    @PreAuthorize("hasAuthority('CARTES_GERER') or hasAuthority('CARTES_ACTIVER') or hasAuthority('TRANSACTIONS')")
    public List<Map<String, Object>> lists(@RequestParam(required = false) String kind, @RequestParam(required = false) Long cardId,
                                           @RequestParam(required = false) Boolean all) {
        List<Map<String, Object>> rows = db.list("""
                \sSELECT l.id, l.kind, l.card_id, c.number, c.status, h.name AS holder_name, l.station_id, s.short_name AS station_name,
                       l.reason, l.added_at, l.valid_until, l.removed_at, l.removal_note,
                       COALESCE(a.first_name || ' ' || a.last_name, 'Automatique') AS added_by_name,
                       r.first_name || ' ' || r.last_name AS removed_by_name
                FROM card_list l JOIN card c ON c.id = l.card_id LEFT JOIN holder h ON h.id = c.holder_id
                LEFT JOIN station s ON s.id = l.station_id LEFT JOIN employee a ON a.id = l.added_by LEFT JOIN employee r ON r.id = l.removed_by
                WHERE (CAST(:k AS text) IS NULL OR l.kind = :k) AND (CAST(:c AS bigint) IS NULL OR l.card_id = :c)
                  AND (CAST(:all AS boolean) OR (l.removed_at IS NULL AND (l.valid_until IS NULL OR l.valid_until > now())))
                ORDER BY l.removed_at NULLS FIRST, l.added_at DESC LIMIT 500""",
                Db.p("k", kind, "c", cardId, "all", Boolean.TRUE.equals(all)));
        rows.forEach(r -> {
            r.put("numberFormatted", CardService.format((String) r.get("number")));
            r.put("label", CardOperations.label((String) r.get("kind")));
        });
        return rows;
    }

    public record ListForm(@NotNull Long cardId, @NotBlank @Pattern(regexp = "NOIRE|GRISE|BLANCHE") String kind, Long stationId,
                           @NotBlank String reason, Instant validUntil) {}

    @PostMapping("/api/card-lists")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> add(@Valid @RequestBody ListForm f) {
        return ops.list(f.cardId(), f.kind(), f.stationId(), f.reason().trim(), f.validUntil(), Auth.user().id());
    }

    public record RemoveForm(String note) {}

    @PostMapping("/api/card-lists/{id}/remove")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> remove(@PathVariable long id, @RequestBody(required = false) RemoveForm f) {
        ops.unlist(id, f == null ? null : blank(f.note()), Auth.user().id());
        return Map.of("ok", true);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
