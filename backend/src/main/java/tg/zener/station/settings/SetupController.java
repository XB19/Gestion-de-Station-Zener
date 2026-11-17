package tg.zener.station.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;

/**
 * Mise en route du réseau (premier démarrage) : liste calculée de ce qui est prêt et de ce qui reste à faire,
 * chaque point renvoyant vers l'écran où le faire. Vue par l'Admin sur le tableau de bord tant qu'elle n'est pas complète.
 */
@RestController
public class SetupController {

    private final Db db;
    private final Settings settings;

    public SetupController(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    @GetMapping("/api/setup/status")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public Map<String, Object> status() {
        Map<String, Object> c = db.one("""
                \sSELECT (SELECT count(*) FROM station WHERE active) AS stations,
                       (SELECT count(*) FROM station WHERE active AND api_key_hash IS NOT NULL) AS stations_with_key,
                       (SELECT count(*) FROM station s WHERE s.active
                          AND NOT EXISTS (SELECT 1 FROM pump p WHERE p.station_id = s.id)) AS stations_without_pumps,
                       (SELECT count(*) FROM client) AS clients,
                       (SELECT count(*) FROM licence WHERE status = 'ACTIVE' AND end_date >= current_date) AS licences,
                       (SELECT count(*) FROM product p WHERE p.active AND p.category <> 'CARTE'
                          AND NOT EXISTS (SELECT 1 FROM product_price pp WHERE pp.product_id = p.id AND pp.profile = 'GRAND_PUBLIC')) AS unpriced,
                       (SELECT count(*) FROM product WHERE active AND category = 'CARBURANT') AS fuels,
                       (SELECT count(*) FROM employee WHERE active AND role_code = 'DAF') AS daf,
                       (SELECT count(*) FROM employee WHERE active AND role_code = 'COMPTABLE') AS accountants,
                       (SELECT count(*) FROM employee WHERE active AND role_code = 'ADMIN') AS admins,
                       (SELECT count(*) FROM station s WHERE s.active AND s.manager_id IS NULL) AS stations_without_manager,
                       (SELECT count(*) FROM card WHERE status = 'STOCK') AS cards_in_stock,
                       (SELECT count(*) FROM pdv WHERE kind = 'PERMANENT' AND status = 'OUVERT') AS permanent_pdv,
                       (SELECT count(*) FROM public_holiday WHERE day >= date_trunc('year', current_date)) AS holidays,
                       (SELECT count(*) FROM supplier WHERE active) AS suppliers""", Map.of());
        List<Map<String, Object>> items = new ArrayList<>();
        add(items, "ENTREPRISE", "Informations de l'entreprise (nom, adresse, contact)",
                !settings.get("company.name", "").isBlank() && !settings.get("company.email", "").isBlank(), "/mise-en-route", null);
        add(items, "COMPTES", "Comptes clés : au moins 2 Admin, un DAF et un Comptable (la seconde personne des actions sensibles)",
                n(c, "admins") >= 2 && n(c, "daf") >= 1 && n(c, "accountants") >= 1, "/employes",
                n(c, "admins") + " Admin, " + n(c, "daf") + " DAF, " + n(c, "accountants") + " Comptable");
        add(items, "CLIENTS", "Clients et licences annuelles", n(c, "clients") > 0 && n(c, "licences") > 0, "/clients",
                n(c, "licences") + " licence(s) active(s)");
        add(items, "STATIONS", "Stations créées et attribuées", n(c, "stations") > 0, "/stations", n(c, "stations") + " station(s)");
        add(items, "PISTOLETS", "Pistolets et cuves de chaque station", n(c, "stations") > 0 && n(c, "stationsWithoutPumps") == 0, "/stations",
                n(c, "stationsWithoutPumps") == 0 ? null : n(c, "stationsWithoutPumps") + " station(s) sans pistolet");
        add(items, "GERANTS", "Un gérant par station", n(c, "stations") > 0 && n(c, "stationsWithoutManager") == 0, "/employes",
                n(c, "stationsWithoutManager") == 0 ? null : n(c, "stationsWithoutManager") + " station(s) sans gérant");
        add(items, "PC_STATIONS", "Clé du PC de chaque station", n(c, "stations") > 0 && n(c, "stationsWithKey") == n(c, "stations"), "/stations",
                n(c, "stationsWithKey") + " / " + n(c, "stations"));
        add(items, "PRIX", "Prix des produits par profil (carburants, boutique, cartes)", n(c, "fuels") > 0 && n(c, "unpriced") == 0, "/ventes-stocks",
                n(c, "unpriced") == 0 ? null : n(c, "unpriced") + " produit(s) sans prix");
        add(items, "CARTES", "Stock de cartes activées (prêtes à attribuer)", n(c, "cardsInStock") > 0, "/cartes", n(c, "cardsInStock") + " carte(s)");
        add(items, "PDV", "Point de vente permanent du siège ouvert", n(c, "permanentPdv") > 0, "/points-de-vente", null);
        add(items, "FERIES", "Jours fériés de l'année (fêtes musulmanes comprises)", n(c, "holidays") >= 8, "/parametres",
                n(c, "holidays") + " jour(s)");
        add(items, "FOURNISSEURS", "Fournisseurs de carburant", n(c, "suppliers") > 0, "/approvisionnement", null);
        add(items, "SMTP", "Serveur d'envoi des emails", !settings.get("mail.configured", "false").equals("false")
                || System.getenv("MAIL_HOST") != null, "/parametres", "Variable MAIL_HOST de l'API");
        long done = items.stream().filter(i -> Boolean.TRUE.equals(i.get("done"))).count();
        return Map.of("items", items, "done", done, "total", items.size(), "complete", done == items.size(),
                "company", Map.of("name", settings.get("company.name", ""), "legal", settings.get("company.legal", ""),
                        "address", settings.get("company.address", ""), "phone", settings.get("company.phone", ""),
                        "email", settings.get("company.email", "")));
    }

    private static long n(Map<String, Object> c, String k) {
        return ((Number) c.get(k)).longValue();
    }

    private static void add(List<Map<String, Object>> items, String key, String label, boolean done, String link, String detail) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("done", done);
        m.put("link", link);
        m.put("detail", detail);
        items.add(m);
    }
}
