package tg.zener.station.settings;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/** Paramètres (paliers de validation, horaires des emails, seuils…), jours fériés, file des emails. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final Db db;
    private final Settings settings;
    private final Journal journal;

    public SettingsController(Db db, Settings settings, Journal journal) {
        this.db = db;
        this.settings = settings;
        this.journal = journal;
    }

    @GetMapping
    public Map<String, Object> all() {
        return settings.all();
    }

    /** Mise à jour de paramètres existants : { "validation.threshold": "750000", … }. */
    @PutMapping
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public Map<String, Object> update(@RequestBody Map<String, String> values) {
        Set<String> known = settings.all().keySet();
        for (var e : values.entrySet()) {
            if (!known.contains(e.getKey())) {
                throw new ApiException("Paramètre inconnu : " + e.getKey());
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                throw new ApiException("Valeur vide pour " + e.getKey());
            }
        }
        values.forEach(settings::set);
        journal.log("PARAMETRES", Journal.MODIFICATIONS, "Paramètres modifiés", String.join(", ", values.keySet()),
                null, null, null);
        return settings.all();
    }

    @GetMapping("/holidays")
    public List<Map<String, Object>> holidays() {
        return db.list("SELECT day, label FROM public_holiday ORDER BY day");
    }

    public record Holiday(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, String label) {}

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public List<Map<String, Object>> addHoliday(@RequestBody Holiday h) {
        if (h.day() == null || h.label() == null || h.label().isBlank()) {
            throw new ApiException("Date et libellé requis");
        }
        db.update("""
                \sINSERT INTO public_holiday (day, label) VALUES (:d, :l)
                ON CONFLICT (day) DO UPDATE SET label = EXCLUDED.label""", Db.p("d", h.day(), "l", h.label().trim()));
        return holidays();
    }

    @DeleteMapping("/holidays/{day}")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public List<Map<String, Object>> deleteHoliday(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        db.update("DELETE FROM public_holiday WHERE day = :d", Db.p("d", day));
        return holidays();
    }

    /** File des emails (alertes, rappels, résumés) : aperçu même sans serveur SMTP. */
    @GetMapping("/emails")
    @PreAuthorize("hasAuthority('PARAMETRES') or hasAuthority('JOURNAL')")
    public List<Map<String, Object>> emails(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) Integer limit) {
        return db.list("""
                \sSELECT id, recipients, subject, body, kind, created_at, sent_at, error FROM email_outbox
                WHERE CAST(:k AS text) IS NULL OR kind = :k ORDER BY id DESC LIMIT :l""",
                Db.p("k", kind, "l", limit == null ? 50 : Math.min(limit, 200)));
    }
}
