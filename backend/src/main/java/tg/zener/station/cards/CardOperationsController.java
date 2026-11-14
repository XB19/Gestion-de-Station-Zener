package tg.zener.station.cards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.security.Auth;

/** Transferts entre cartes du même titulaire et remplacement d'une carte. */
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

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
