package tg.zener.station.extractions;

import static tg.zener.station.extractions.Extraction.Type.DATE;
import static tg.zener.station.extractions.Extraction.Type.DATETIME;
import static tg.zener.station.extractions.Extraction.Type.INT;
import static tg.zener.station.extractions.Extraction.Type.MONEY;
import static tg.zener.station.extractions.Extraction.Type.QTY;
import static tg.zener.station.extractions.Extraction.Type.TEXT;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tg.zener.station.common.ApiException;

/**
 * Catalogue des extractions du module Gestionnaire. Chaque extraction s'affiche en tableau paginé
 * et se télécharge en CSV ou XLSX (voir {@link ExtractionService}).
 */
@Component
public class ExtractionCatalog {

    /** Rubriques de l'écran, dans l'ordre. */
    public static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("CARTES", "Cartes et clients");
        SECTIONS.put("MOUVEMENTS", "Recharges et mouvements");
        SECTIONS.put("FLOTTES", "Flottes, véhicules et chauffeurs");
        SECTIONS.put("VENTES", "Ventes");
        SECTIONS.put("STOCKS", "Pistolets, cuves et stocks");
        SECTIONS.put("FIDELITE", "Fidélité et remises");
        SECTIONS.put("CREDIT", "Crédit clients");
        SECTIONS.put("APPRO", "Prix et approvisionnement");
        SECTIONS.put("OPERATEURS", "Opérateurs et postes");
        SECTIONS.put("OPERATIONS", "Ordres de travail et audit");
    }

    // ------------------------------------------------------------------ fragments SQL communs

    static String cardNo(String c) {
        return "substr(" + c + ".number, 1, 4) || ' ' || substr(" + c + ".number, 5, 4) || ' ' || substr(" + c + ".number, 9, 4)";
    }

    static String person(String e) {
        return e + ".first_name || ' ' || " + e + ".last_name";
    }

    static String payment(String col) {
        return "CASE " + col + " WHEN 'ESPECES' THEN 'Espèces' WHEN 'MOBILE_MONEY' THEN 'Mobile money' WHEN 'CARTE' THEN 'Carte'"
                + " WHEN 'VIREMENT' THEN 'Virement' WHEN 'CARTE_BANCAIRE' THEN 'Carte bancaire' ELSE " + col + " END";
    }

    static final String SHIFT_LABEL =
            "CASE WHEN es.id IS NULL THEN NULL ELSE COALESCE(sc.name, 'Poste') || ' du ' || to_char(es.started_at, 'DD/MM/YYYY') END";

    static final String CARD_STATUS = "CASE c.status WHEN 'STOCK' THEN 'En stock' WHEN 'PDV' THEN 'En point de vente'"
            + " WHEN 'A_ACTIVER' THEN 'À activer' WHEN 'ACTIVE' THEN 'Active' WHEN 'BLOQUEE' THEN 'Bloquée'"
            + " WHEN 'INACTIVE' THEN 'Inactive' ELSE c.status END";

    static final String CATEGORY = "CASE pr.category WHEN 'CARBURANT' THEN 'Carburant' WHEN 'LUBRIFIANT' THEN 'Lubrifiant'"
            + " WHEN 'GAZ' THEN 'Gaz' WHEN 'ACCESSOIRE' THEN 'Accessoire' WHEN 'CARTE' THEN 'Carte' ELSE pr.category END";

    /** Conditions de filtre des requêtes fondées sur la table sale (alias x, carte c). */
    private static Extraction.Builder saleFilters(Extraction.Builder b, boolean lines) {
        return b.filter("period", "x.at")
                .filter("station", "x.station_id = :stationId")
                .filter("pdv", "x.pdv_id = :pdvId")
                .filter("card", "x.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("product", lines ? "l.product_id = :productId"
                        : "EXISTS (SELECT 1 FROM sale_line l2 WHERE l2.sale_id = x.id AND l2.product_id = :productId)")
                .filter("employee", "x.employee_id = :employeeId")
                .filter("shift", "x.employee_shift_id = :shiftId");
    }

    private final Map<String, Extraction> all = new LinkedHashMap<>();

    public ExtractionCatalog() {
        // ============================================================== Cartes et clients
        add(Extraction.of("historique-carte", "CARTES", "Historique des transactions par carte",
                        "Chaque achat, recharge ou ajustement d'une carte : lieu, produit, quantité, prix, opérateur, poste et solde après l'opération.")
                .col("date", "Date", DATETIME)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("operation", "Opération", TEXT)
                .col("place", "Station / PDV", TEXT)
                .col("pump", "Pompe", TEXT)
                .col("product", "Produit", TEXT)
                .sum("quantity", "Quantité", QTY)
                .col("unit", "Unité", TEXT)
                .col("unit_price", "Prix unitaire (F)", MONEY)
                .sum("amount", "Montant (F)", MONEY)
                .col("operator", "Opérateur", TEXT)
                .col("shift", "Poste", TEXT)
                .col("balance_after", "Solde après (F)", MONEY)
                .select("\sm.at AS date, " + cardNo("c") + " AS card, h.name AS holder, g.name AS card_group,"
                        + " CASE m.kind WHEN 'VENTE' THEN 'Achat' WHEN 'RECHARGE' THEN 'Recharge' WHEN 'DOTATION' THEN 'Dotation flotte'"
                        + " WHEN 'REPRISE' THEN 'Reprise flotte' ELSE 'Ajustement' END AS operation,"
                        + " COALESCE(st.short_name, pv.name) AS place, pu.code AS pump, pr.name AS product, l.quantity, pr.unit,"
                        + " l.unit_price, CASE WHEN l.id IS NULL THEN m.amount ELSE -l.amount END AS amount,"
                        + " " + person("e") + " AS operator, " + SHIFT_LABEL + " AS shift, m.balance_after")
                .from("""
                        \scard_movement m
                        JOIN card c ON c.id = m.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN sale x ON x.id = m.sale_id
                        LEFT JOIN sale_line l ON l.sale_id = x.id
                        LEFT JOIN product pr ON pr.id = l.product_id
                        LEFT JOIN pump pu ON pu.id = x.pump_id
                        LEFT JOIN recharge r ON r.id = m.recharge_id
                        LEFT JOIN station st ON st.id = COALESCE(x.station_id, m.station_id, r.station_id)
                        LEFT JOIN pdv pv ON pv.id = COALESCE(x.pdv_id, r.pdv_id)
                        LEFT JOIN employee e ON e.id = m.employee_id
                        LEFT JOIN employee_shift es ON es.id = x.employee_shift_id
                        LEFT JOIN shift_config sc ON sc.id = es.config_id""")
                .orderBy("m.at DESC, m.id DESC, l.id")
                .filter("period", "m.at")
                .filter("station", "COALESCE(x.station_id, m.station_id, r.station_id) = :stationId")
                .filter("pdv", "COALESCE(x.pdv_id, r.pdv_id) = :pdvId")
                .filter("card", "m.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("product", "l.product_id = :productId")
                .filter("employee", "m.employee_id = :employeeId")
                .filter("shift", "x.employee_shift_id = :shiftId"));

        add(saleFilters(Extraction.of("carte-produits", "CARTES", "Achats par carte et par produit",
                        "Synthèse des achats payés par carte : pour chaque carte et chaque produit, nombre d'achats, quantité et montant.")
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("product", "Produit", TEXT)
                .col("unit", "Unité", TEXT)
                .sum("transactions", "Achats", INT)
                .sum("quantity", "Quantité", QTY)
                .sum("amount", "Montant (F)", MONEY)
                .col("first_at", "Premier achat", DATETIME)
                .col("last_at", "Dernier achat", DATETIME)
                .select("\s" + cardNo("c") + " AS card, h.name AS holder, g.name AS card_group, pr.name AS product, pr.unit,"
                        + " count(DISTINCT x.id) AS transactions, sum(l.quantity) AS quantity, sum(l.amount) AS amount,"
                        + " min(x.at) AS first_at, max(x.at) AS last_at")
                .from("""
                        \ssale x
                        JOIN sale_line l ON l.sale_id = x.id
                        JOIN product pr ON pr.id = l.product_id
                        JOIN card c ON c.id = x.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id""")
                .where("x.payment_method = 'CARTE'")
                .groupBy("c.id, c.number, h.name, g.name, pr.id, pr.name, pr.unit")
                .orderBy("h.name, c.number, sum(l.amount) DESC"), true));

        add(Extraction.of("client-cartes", "CARTES", "Cartes d'un client",
                        "Toutes les cartes de chaque titulaire (particulier, entreprise, employé) avec solde, achats et recharges sur la période.")
                .col("holder", "Titulaire", TEXT)
                .col("holder_kind", "Type de client", TEXT)
                .col("card", "Carte", TEXT)
                .col("card_type", "Type de carte", TEXT)
                .col("status", "État", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("driver", "Conducteur", TEXT)
                .sum("purchases", "Achats", INT)
                .sum("spent", "Dépensé (F)", MONEY)
                .sum("recharged", "Rechargé (F)", MONEY)
                .sum("balance", "Solde actuel (F)", MONEY)
                .col("last_at", "Dernière opération", DATETIME)
                .select("\sh.name AS holder, CASE h.kind WHEN 'PARTICULIER' THEN 'Particulier' WHEN 'ENTREPRISE' THEN 'Entreprise'"
                        + " ELSE 'Employé' END AS holder_kind, " + cardNo("c") + " AS card, ct.label AS card_type, "
                        + CARD_STATUS + " AS status, g.name AS card_group,"
                        + " NULLIF(concat_ws(' · ', c.vehicle_plate, c.vehicle_label), '') AS vehicle, c.driver_name AS driver,"
                        + " COALESCE(mv.purchases, 0) AS purchases, COALESCE(mv.spent, 0) AS spent,"
                        + " COALESCE(mv.recharged, 0) AS recharged, c.balance, mv.last_at")
                .from("""
                        \scard c
                        JOIN holder h ON h.id = c.holder_id
                        JOIN card_type ct ON ct.code = c.type_code
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN LATERAL (
                            SELECT count(*) FILTER (WHERE m.kind = 'VENTE') AS purchases,
                                   -sum(m.amount) FILTER (WHERE m.kind = 'VENTE') AS spent,
                                   sum(m.amount) FILTER (WHERE m.kind = 'RECHARGE') AS recharged,
                                   max(m.at) AS last_at
                            FROM card_movement m
                            WHERE m.card_id = c.id
                              AND (CAST(:from AS timestamptz) IS NULL OR m.at >= :from)
                              AND (CAST(:to AS timestamptz) IS NULL OR m.at < :to)) mv ON true""")
                .orderBy("h.name, g.name NULLS FIRST, c.number")
                .filter("card", "c.id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId"));

        add(Extraction.of("operations-cartes", "CARTES", "Opérations sur les cartes",
                        "Émission, activation, blocage, déblocage, PIN, permissions, attribution et vente en point de vente : qui, quand, où.")
                .col("date", "Date", DATETIME)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("operation", "Opération", TEXT)
                .col("detail", "Détail", TEXT)
                .col("place", "Station / PDV", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("operator_type", "Type d'opérateur", TEXT)
                .select("\sce.at AS date, " + cardNo("c") + " AS card, h.name AS holder, g.name AS card_group,"
                        + tg.zener.station.cards.CardEvents.LABEL_SQL + " AS operation, ce.detail,"
                        + " COALESCE(st.short_name, pv.name) AS place, COALESCE(" + person("e") + ", 'Système') AS operator,"
                        + " ro.label AS operator_type")
                .from("""
                        \scard_event ce
                        JOIN card c ON c.id = ce.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN station st ON st.id = ce.station_id
                        LEFT JOIN pdv pv ON pv.id = ce.pdv_id
                        LEFT JOIN employee e ON e.id = ce.employee_id
                        LEFT JOIN role ro ON ro.code = e.role_code""")
                .orderBy("ce.at DESC, ce.id DESC")
                .filter("period", "ce.at")
                .filter("station", "ce.station_id = :stationId")
                .filter("pdv", "ce.pdv_id = :pdvId")
                .filter("card", "ce.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("employee", "ce.employee_id = :employeeId"));

        // ============================================================== Recharges et mouvements
        add(Extraction.of("recharges", "MOUVEMENTS", "Recharges",
                        "Recharges unitaires, à distance et en masse : carte, montant, paiement, statut, lieu et opérateur.")
                .col("date", "Date", DATETIME)
                .col("ref", "Référence", TEXT)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("card_group", "Groupe", TEXT)
                .sum("amount", "Montant (F)", MONEY)
                .col("channel", "Canal", TEXT)
                .col("payment", "Paiement", TEXT)
                .col("payment_ref", "Réf. paiement", TEXT)
                .col("status", "Statut", TEXT)
                .col("validation", "Validation", TEXT)
                .col("place", "Station / PDV", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("credited_at", "Créditée le", DATETIME)
                .select("\sr.created_at AS date, r.ref, " + cardNo("c") + " AS card, h.name AS holder, g.name AS card_group, r.amount,"
                        + " CASE r.channel WHEN 'UNITAIRE' THEN 'Unitaire' WHEN 'DISTANCE' THEN 'À distance' ELSE 'En masse' END AS channel,"
                        + " " + payment("r.payment_method") + " AS payment, r.payment_ref,"
                        + " CASE r.status WHEN 'EN_VALIDATION' THEN 'En validation' WHEN 'CREDITEE' THEN 'Créditée' ELSE 'Refusée' END AS status,"
                        + " v.ref AS validation, COALESCE(st.short_name, pv.name, 'Siège') AS place, " + person("e") + " AS operator,"
                        + " r.credited_at")
                .from("""
                        \srecharge r
                        JOIN card c ON c.id = r.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN station st ON st.id = r.station_id
                        LEFT JOIN pdv pv ON pv.id = r.pdv_id
                        LEFT JOIN employee e ON e.id = r.requested_by
                        LEFT JOIN validation_request v ON v.id = r.validation_id""")
                .orderBy("r.created_at DESC, r.id DESC")
                .filter("period", "r.created_at")
                .filter("station", "r.station_id = :stationId")
                .filter("pdv", "r.pdv_id = :pdvId")
                .filter("card", "r.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("employee", "r.requested_by = :employeeId"));

        add(Extraction.of("mouvements-cartes", "MOUVEMENTS", "Mouvements des cartes",
                        "Crédits et débits de solde de chaque carte, avec leur origine (vente, recharge, ajustement), le lieu et l'opérateur.")
                .col("date", "Date", DATETIME)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("nature", "Nature", TEXT)
                .sum("credit", "Crédit (F)", MONEY)
                .sum("debit", "Débit (F)", MONEY)
                .col("balance_after", "Solde après (F)", MONEY)
                .col("source", "Origine", TEXT)
                .col("place", "Station / PDV", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("detail", "Détail", TEXT)
                .select("\sm.at AS date, " + cardNo("c") + " AS card, h.name AS holder,"
                        + " CASE m.kind WHEN 'VENTE' THEN 'Achat' WHEN 'RECHARGE' THEN 'Recharge' WHEN 'DOTATION' THEN 'Dotation flotte'"
                        + " WHEN 'REPRISE' THEN 'Reprise flotte' ELSE 'Ajustement' END AS nature,"
                        + " CASE WHEN m.amount > 0 THEN m.amount END AS credit, CASE WHEN m.amount < 0 THEN -m.amount END AS debit,"
                        + " m.balance_after,"
                        + " CASE WHEN r.id IS NOT NULL THEN 'Recharge ' || r.ref WHEN x.id IS NOT NULL THEN 'Vente n° ' || x.id"
                        + " WHEN m.fleet_movement_id IS NOT NULL THEN 'Compte flotte'"
                        + " ELSE 'Ajustement' END AS source,"
                        + " COALESCE(st.short_name, pv.name) AS place, " + person("e") + " AS operator, m.detail")
                .from("""
                        \scard_movement m
                        JOIN card c ON c.id = m.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN sale x ON x.id = m.sale_id
                        LEFT JOIN recharge r ON r.id = m.recharge_id
                        LEFT JOIN station st ON st.id = COALESCE(m.station_id, x.station_id, r.station_id)
                        LEFT JOIN pdv pv ON pv.id = COALESCE(x.pdv_id, r.pdv_id)
                        LEFT JOIN employee e ON e.id = m.employee_id""")
                .orderBy("m.at DESC, m.id DESC")
                .filter("period", "m.at")
                .filter("station", "COALESCE(m.station_id, x.station_id, r.station_id) = :stationId")
                .filter("pdv", "COALESCE(x.pdv_id, r.pdv_id) = :pdvId")
                .filter("card", "m.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("employee", "m.employee_id = :employeeId")
                .filter("shift", "x.employee_shift_id = :shiftId"));

        // ============================================================== Flottes, véhicules et chauffeurs
        add(Extraction.of("flottes", "FLOTTES", "Flottes et comptes flotte",
                        "Chaque flotte d'entreprise : contact, cartes, véhicules, chauffeurs, solde du compte flotte et des cartes, consommation.")
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .col("code", "Code", TEXT)
                .col("contact", "Contact", TEXT)
                .col("phone", "Téléphone", TEXT)
                .sum("cards", "Cartes", INT)
                .sum("vehicles", "Véhicules", INT)
                .sum("drivers", "Chauffeurs", INT)
                .sum("account_balance", "Compte flotte (F)", MONEY)
                .sum("cards_balance", "Soldes des cartes (F)", MONEY)
                .sum("liters", "Carburant (L)", QTY)
                .sum("spent", "Consommation (F)", MONEY)
                .col("state", "État", TEXT)
                .select("\sh.name AS holder, g.name AS fleet, g.code, g.contact_name AS contact, COALESCE(g.phone, h.phone) AS phone,"
                        + " (SELECT count(*) FROM card c WHERE c.group_id = g.id AND c.status <> 'INACTIVE') AS cards,"
                        + " (SELECT count(*) FROM vehicle v WHERE v.group_id = g.id AND v.active) AS vehicles,"
                        + " (SELECT count(*) FROM driver d WHERE d.group_id = g.id AND d.active) AS drivers,"
                        + " g.balance AS account_balance, (SELECT COALESCE(sum(c.balance), 0) FROM card c WHERE c.group_id = g.id) AS cards_balance,"
                        + " COALESCE(cs.liters, 0) AS liters, COALESCE(cs.spent, 0) AS spent,"
                        + " CASE WHEN g.active THEN 'Active' ELSE 'Désactivée' END AS state")
                .from("""
                        \scard_group g
                        JOIN holder h ON h.id = g.holder_id
                        LEFT JOIN LATERAL (
                            SELECT sum(l.quantity) FILTER (WHERE p.category = 'CARBURANT') AS liters, sum(l.amount) AS spent
                            FROM sale x JOIN sale_line l ON l.sale_id = x.id JOIN product p ON p.id = l.product_id
                            JOIN card c ON c.id = x.card_id
                            WHERE c.group_id = g.id
                              AND (CAST(:from AS timestamptz) IS NULL OR x.at >= :from)
                              AND (CAST(:to AS timestamptz) IS NULL OR x.at < :to)) cs ON true""")
                .where("h.kind = 'ENTREPRISE'")
                .orderBy("h.name, g.name")
                .filter("holder", "g.holder_id = :holderId")
                .filter("group", "g.id = :groupId"));

        add(Extraction.of("compte-flotte", "FLOTTES", "Mouvements des comptes flotte",
                        "Recharges du compte flotte, dotations des cartes et reprises : montants, soldes avant / après du compte et de la carte.")
                .col("date", "Date", DATETIME)
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .col("nature", "Nature", TEXT)
                .sum("credit", "Crédit du compte (F)", MONEY)
                .sum("debit", "Débit du compte (F)", MONEY)
                .col("balance_after", "Solde du compte après (F)", MONEY)
                .col("card", "Carte", TEXT)
                .col("driver", "Chauffeur", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("card_balance_before", "Carte avant (F)", MONEY)
                .col("card_balance_after", "Carte après (F)", MONEY)
                .col("ref", "Référence", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sm.at AS date, h.name AS holder, g.name AS fleet,"
                        + " CASE m.kind WHEN 'RECHARGE' THEN 'Recharge du compte' WHEN 'DOTATION' THEN 'Dotation de la carte'"
                        + " WHEN 'REPRISE' THEN 'Reprise sur la carte' ELSE 'Ajustement' END AS nature,"
                        + " CASE WHEN m.amount > 0 THEN m.amount END AS credit, CASE WHEN m.amount < 0 THEN -m.amount END AS debit,"
                        + " m.balance_after, CASE WHEN c.id IS NULL THEN NULL ELSE " + cardNo("c") + " END AS card,"
                        + " d.name AS driver, v.plate AS vehicle, m.card_balance_before, m.card_balance_after, r.ref,"
                        + " " + person("e") + " AS operator, m.note")
                .from("""
                        \sfleet_movement m
                        JOIN card_group g ON g.id = m.group_id
                        JOIN holder h ON h.id = g.holder_id
                        LEFT JOIN card c ON c.id = m.card_id
                        LEFT JOIN driver d ON d.id = c.driver_id
                        LEFT JOIN vehicle v ON v.id = c.vehicle_id
                        LEFT JOIN fleet_recharge r ON r.id = m.fleet_recharge_id
                        LEFT JOIN employee e ON e.id = m.employee_id""")
                .orderBy("m.at DESC, m.id DESC")
                .filter("period", "m.at")
                .filter("card", "m.card_id = :cardId")
                .filter("holder", "g.holder_id = :holderId")
                .filter("group", "m.group_id = :groupId")
                .filter("vehicle", "c.vehicle_id = :vehicleId")
                .filter("driver", "c.driver_id = :driverId")
                .filter("employee", "m.employee_id = :employeeId"));

        add(Extraction.of("vehicules", "FLOTTES", "Véhicules",
                        "Parc des entreprises clientes : immatriculation, type, marque, carburant, réservoir, kilométrage, chauffeur et cartes.")
                .col("plate", "Immatriculation", TEXT)
                .col("type", "Type", TEXT)
                .col("brand", "Marque", TEXT)
                .col("model", "Modèle", TEXT)
                .col("color", "Couleur", TEXT)
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .col("fuel", "Carburant", TEXT)
                .col("tank_capacity", "Réservoir (L)", QTY)
                .col("odometer_km", "Kilométrage", INT)
                .col("driver", "Chauffeur actuel", TEXT)
                .col("cards", "Cartes", TEXT)
                .col("state", "État", TEXT)
                .select("\sv.plate, vt.label AS type, v.brand, v.model, v.color, h.name AS holder, g.name AS fleet, p.name AS fuel,"
                        + " v.tank_capacity, v.odometer_km, d.name AS driver,"
                        + " (SELECT string_agg(" + cardNo("c") + ", ', ') FROM card c WHERE c.vehicle_id = v.id) AS cards,"
                        + " CASE WHEN v.active THEN 'Actif' ELSE 'Retiré' END AS state")
                .from("""
                        \svehicle v
                        JOIN holder h ON h.id = v.holder_id
                        JOIN vehicle_type vt ON vt.code = v.type_code
                        LEFT JOIN card_group g ON g.id = v.group_id
                        LEFT JOIN product p ON p.id = v.product_id
                        LEFT JOIN vehicle_assignment a ON a.vehicle_id = v.id AND a.ended_at IS NULL
                        LEFT JOIN driver d ON d.id = a.driver_id""")
                .orderBy("h.name, v.plate")
                .filter("holder", "v.holder_id = :holderId")
                .filter("group", "v.group_id = :groupId")
                .filter("vehicle", "v.id = :vehicleId")
                .filter("driver", "a.driver_id = :driverId")
                .filter("product", "v.product_id = :productId"));

        add(Extraction.of("chauffeurs", "FLOTTES", "Chauffeurs",
                        "Chauffeurs des entreprises clientes : téléphone, permis et échéance, véhicule conduit, cartes.")
                .col("name", "Chauffeur", TEXT)
                .col("phone", "Téléphone", TEXT)
                .col("licence_number", "N° de permis", TEXT)
                .col("licence_expires", "Fin de validité du permis", DATE)
                .col("licence_state", "Permis", TEXT)
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("cards", "Cartes", TEXT)
                .col("state", "État", TEXT)
                .select("\sd.name, d.phone, d.licence_number, d.licence_expires,"
                        + " CASE WHEN d.licence_expires IS NULL THEN NULL WHEN d.licence_expires < current_date THEN 'Expiré'"
                        + " WHEN d.licence_expires < current_date + 30 THEN 'Expire bientôt' ELSE 'Valide' END AS licence_state,"
                        + " h.name AS holder, g.name AS fleet, v.plate AS vehicle,"
                        + " (SELECT string_agg(" + cardNo("c") + ", ', ') FROM card c WHERE c.driver_id = d.id) AS cards,"
                        + " CASE WHEN d.active THEN 'Actif' ELSE 'Inactif' END AS state")
                .from("""
                        \sdriver d
                        JOIN holder h ON h.id = d.holder_id
                        LEFT JOIN card_group g ON g.id = d.group_id
                        LEFT JOIN vehicle_assignment a ON a.driver_id = d.id AND a.ended_at IS NULL
                        LEFT JOIN vehicle v ON v.id = a.vehicle_id""")
                .orderBy("h.name, d.name")
                .filter("holder", "d.holder_id = :holderId")
                .filter("group", "d.group_id = :groupId")
                .filter("vehicle", "a.vehicle_id = :vehicleId")
                .filter("driver", "d.id = :driverId"));

        add(Extraction.of("affectations", "FLOTTES", "Affectations véhicule / chauffeur",
                        "Historique de qui a conduit quel véhicule, depuis quand et jusqu'à quand, et qui l'a enregistré.")
                .col("started_at", "Début", DATETIME)
                .col("ended_at", "Fin", DATETIME)
                .col("vehicle", "Véhicule", TEXT)
                .col("vehicle_type", "Type", TEXT)
                .col("driver", "Chauffeur", TEXT)
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sa.started_at, a.ended_at, v.plate AS vehicle, vt.label AS vehicle_type, d.name AS driver, h.name AS holder,"
                        + " g.name AS fleet, " + person("e") + " AS operator, a.note")
                .from("""
                        \svehicle_assignment a
                        JOIN vehicle v ON v.id = a.vehicle_id
                        JOIN vehicle_type vt ON vt.code = v.type_code
                        JOIN driver d ON d.id = a.driver_id
                        JOIN holder h ON h.id = v.holder_id
                        LEFT JOIN card_group g ON g.id = v.group_id
                        LEFT JOIN employee e ON e.id = a.assigned_by""")
                .orderBy("a.started_at DESC, a.id DESC")
                .filter("period", "a.started_at")
                .filter("holder", "v.holder_id = :holderId")
                .filter("group", "v.group_id = :groupId")
                .filter("vehicle", "a.vehicle_id = :vehicleId")
                .filter("driver", "a.driver_id = :driverId")
                .filter("employee", "a.assigned_by = :employeeId"));

        consumption("consommation-vehicules", "Consommation par véhicule",
                "Pleins, litres et montants par véhicule ; kilomètres parcourus et consommation aux 100 km quand le kilométrage est saisi.",
                "x.vehicle_id", "v.plate", "Véhicule");
        consumption("consommation-chauffeurs", "Consommation par chauffeur",
                "Pleins, litres et montants par chauffeur, avec les véhicules utilisés.", "x.driver_id", "d.name", "Chauffeur");

        // ============================================================== Ventes
        add(saleFilters(Extraction.of("ventes-produits", "VENTES", "Ventes par produit",
                        "Quantités et montants vendus par produit, avec la répartition par mode de paiement.")
                .col("code", "Code", TEXT)
                .col("product", "Produit", TEXT)
                .col("category", "Catégorie", TEXT)
                .col("unit", "Unité", TEXT)
                .sum("transactions", "Ventes", INT)
                .sum("quantity", "Quantité", QTY)
                .col("avg_price", "Prix moyen (F)", MONEY)
                .sum("cash", "Espèces (F)", MONEY)
                .sum("mobile_money", "Mobile money (F)", MONEY)
                .sum("card_amount", "Carte (F)", MONEY)
                .sum("transfer", "Virement (F)", MONEY)
                .sum("amount", "Total (F)", MONEY)
                .select("\spr.code, pr.name AS product, " + CATEGORY + " AS category, pr.unit,"
                        + " count(DISTINCT x.id) AS transactions, sum(l.quantity) AS quantity,"
                        + " round(sum(l.amount) / NULLIF(sum(l.quantity), 0)) AS avg_price,"
                        + " COALESCE(sum(l.amount) FILTER (WHERE x.payment_method = 'ESPECES'), 0) AS cash,"
                        + " COALESCE(sum(l.amount) FILTER (WHERE x.payment_method = 'MOBILE_MONEY'), 0) AS mobile_money,"
                        + " COALESCE(sum(l.amount) FILTER (WHERE x.payment_method = 'CARTE'), 0) AS card_amount,"
                        + " COALESCE(sum(l.amount) FILTER (WHERE x.payment_method = 'VIREMENT'), 0) AS transfer,"
                        + " sum(l.amount) AS amount")
                .from("""
                        \ssale x
                        JOIN sale_line l ON l.sale_id = x.id
                        JOIN product pr ON pr.id = l.product_id
                        LEFT JOIN card c ON c.id = x.card_id""")
                .groupBy("pr.id, pr.code, pr.name, pr.category, pr.unit")
                .orderBy("sum(l.amount) DESC"), true));

        add(saleFilters(Extraction.of("ventes-stations", "VENTES", "Ventes par station et point de vente",
                        "Chiffre d'affaires, volume de carburant et répartition par mode de paiement pour chaque station et PDV.")
                .col("place", "Station / PDV", TEXT)
                .col("place_kind", "Type", TEXT)
                .sum("transactions", "Ventes", INT)
                .sum("liters", "Carburant (L)", QTY)
                .sum("cash", "Espèces (F)", MONEY)
                .sum("mobile_money", "Mobile money (F)", MONEY)
                .sum("card_amount", "Carte (F)", MONEY)
                .sum("transfer", "Virement (F)", MONEY)
                .sum("amount", "Total (F)", MONEY)
                .select("\sCOALESCE(st.short_name, pv.name) AS place, CASE WHEN st.id IS NOT NULL THEN 'Station' ELSE 'Point de vente' END AS place_kind,"
                        + " count(*) AS transactions, COALESCE(sum(fl.liters), 0) AS liters,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'ESPECES'), 0) AS cash,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'MOBILE_MONEY'), 0) AS mobile_money,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'CARTE'), 0) AS card_amount,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'VIREMENT'), 0) AS transfer,"
                        + " sum(x.total) AS amount")
                .from("""
                        \ssale x
                        LEFT JOIN station st ON st.id = x.station_id
                        LEFT JOIN pdv pv ON pv.id = x.pdv_id
                        LEFT JOIN card c ON c.id = x.card_id
                        LEFT JOIN LATERAL (
                            SELECT sum(fl.quantity) AS liters FROM sale_line fl JOIN product fp ON fp.id = fl.product_id
                            WHERE fl.sale_id = x.id AND fp.category = 'CARBURANT') fl ON true""")
                .groupBy("st.id, st.short_name, pv.id, pv.name")
                .orderBy("sum(x.total) DESC"), false));

        add(saleFilters(Extraction.of("ventes-detail", "VENTES", "Détail des ventes",
                        "Une ligne par produit vendu : lieu, pompe, quantité, prix, paiement, carte, profil de prix, opérateur et poste.")
                .col("date", "Date", DATETIME)
                .col("sale_no", "N° vente", TEXT)
                .col("place", "Station / PDV", TEXT)
                .col("channel", "Canal", TEXT)
                .col("pump", "Pompe", TEXT)
                .col("product", "Produit", TEXT)
                .sum("quantity", "Quantité", QTY)
                .col("unit", "Unité", TEXT)
                .col("unit_price", "Prix unitaire (F)", MONEY)
                .sum("amount", "Montant (F)", MONEY)
                .col("payment", "Paiement", TEXT)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("profile", "Profil de prix", TEXT)
                .col("mode", "Mode", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("shift", "Poste", TEXT)
                .select("\sx.at AS date, CAST(x.id AS text) AS sale_no, COALESCE(st.short_name, pv.name) AS place,"
                        + " CASE x.channel WHEN 'POMPE' THEN 'Pompe' WHEN 'BOUTIQUE' THEN 'Boutique' WHEN 'DISTRIBUTEUR_GAZ' THEN 'Distributeur de gaz'"
                        + " WHEN 'PDV' THEN 'Point de vente' WHEN 'LIVRAISON' THEN 'Livraison' ELSE x.channel END AS channel,"
                        + " pu.code AS pump, pr.name AS product, l.quantity, pr.unit, l.unit_price, l.amount,"
                        + " " + payment("x.payment_method") + " AS payment,"
                        + " CASE WHEN c.id IS NULL THEN NULL ELSE " + cardNo("c") + " END AS card, h.name AS holder,"
                        + " CASE x.buyer_profile WHEN 'GRAND_PUBLIC' THEN 'Grand public' WHEN 'EMPLOYE_STATION' THEN 'Employé station'"
                        + " WHEN 'EMPLOYE_GROUPE' THEN 'Employé groupe' WHEN 'PARTENAIRE' THEN 'Partenaire' ELSE x.buyer_profile END AS profile,"
                        + " CASE x.mode WHEN 'AUTONOME' THEN 'Autonome' WHEN 'CONVENTIONNEL' THEN 'Conventionnel' END AS mode,"
                        + " " + person("e") + " AS operator, " + SHIFT_LABEL + " AS shift")
                .from("""
                        \ssale x
                        JOIN sale_line l ON l.sale_id = x.id
                        JOIN product pr ON pr.id = l.product_id
                        LEFT JOIN station st ON st.id = x.station_id
                        LEFT JOIN pdv pv ON pv.id = x.pdv_id
                        LEFT JOIN pump pu ON pu.id = x.pump_id
                        LEFT JOIN card c ON c.id = x.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN employee e ON e.id = x.employee_id
                        LEFT JOIN employee_shift es ON es.id = x.employee_shift_id
                        LEFT JOIN shift_config sc ON sc.id = es.config_id""")
                .orderBy("x.at DESC, x.id DESC, l.id"), true));

        // ============================================================== Pistolets, cuves et stocks
        pumpReport("POSTE", "releves-pistolets-postes", "Relevés des pistolets par poste",
                "Index de début et de fin de chaque pistolet sur chaque poste, volume compteur comparé au volume vendu.");
        pumpReport("JOUR", "releves-pistolets-jours", "Relevés des pistolets par jour",
                "Index journaliers de chaque pistolet ; l'écart compteur − ventes signale une distribution non enregistrée.");
        pumpReport("MOIS", "releves-pistolets-mois", "Relevés des pistolets par mois",
                "Cumul mensuel des relevés journaliers : index, volume compteur, ventes et écart.");

        add(Extraction.of("historique-cuves", "STOCKS", "Historique des cuves (jauges et piges)",
                        "Mesures des cuves : volume mesuré, stock théorique, écart, hauteur, température, eau en fond de cuve, densité.")
                .col("date", "Date", DATETIME)
                .col("station", "Station", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("product", "Produit", TEXT)
                .col("source", "Source", TEXT)
                .col("volume", "Volume mesuré (L)", QTY)
                .col("book_volume", "Stock théorique (L)", QTY)
                .col("gap", "Écart (L)", QTY)
                .col("height_mm", "Hauteur (mm)", QTY)
                .col("temperature", "Température (°C)", QTY)
                .col("water_height_mm", "Eau (mm)", QTY)
                .col("water_volume", "Eau (L)", QTY)
                .col("density", "Densité", QTY)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sr.at AS date, st.short_name AS station, t.name AS tank, pr.name AS product,"
                        + " CASE r.source WHEN 'JAUGE' THEN 'Jauge' ELSE 'Pige' END AS source, r.volume, r.book_volume, r.gap,"
                        + " r.height_mm, r.temperature, r.water_height_mm, r.water_volume, r.density, " + person("e") + " AS operator, r.note")
                .from("""
                        \stank_reading r
                        JOIN tank t ON t.id = r.tank_id
                        JOIN station st ON st.id = r.station_id
                        JOIN product pr ON pr.id = r.product_id
                        LEFT JOIN employee e ON e.id = r.employee_id""")
                .orderBy("r.at DESC, r.id DESC")
                .filter("period", "r.at")
                .filter("station", "r.station_id = :stationId")
                .filter("product", "r.product_id = :productId")
                .filter("employee", "r.employee_id = :employeeId"));

        add(Extraction.of("receptions-carburant", "STOCKS", "Réceptions de carburant",
                        "Dépotages : fournisseur, camion, chauffeur, bon de livraison, volume du bon, volume reçu en cuve et écart.")
                .col("ref", "Référence", TEXT)
                .col("started_at", "Début", DATETIME)
                .col("ended_at", "Fin", DATETIME)
                .col("station", "Station", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("product", "Produit", TEXT)
                .col("supplier", "Fournisseur", TEXT)
                .col("truck_plate", "Camion", TEXT)
                .col("driver", "Chauffeur", TEXT)
                .col("ticket_number", "N° du bon", TEXT)
                .sum("ticket_volume", "Volume du bon (L)", QTY)
                .sum("received_volume", "Volume reçu (L)", QTY)
                .sum("gap", "Écart (L)", QTY)
                .col("volume_before", "Cuve avant (L)", QTY)
                .col("volume_after", "Cuve après (L)", QTY)
                .col("ticket_density", "Densité", QTY)
                .col("temperature", "Température (°C)", QTY)
                .col("operator", "Opérateur", TEXT)
                .select("\sd.ref, d.started_at, d.ended_at, st.short_name AS station, t.name AS tank, pr.name AS product, d.supplier,"
                        + " d.truck_plate, d.driver, d.ticket_number, d.ticket_volume, d.received_volume, d.gap, d.volume_before,"
                        + " d.volume_after, d.ticket_density, d.temperature, " + person("e") + " AS operator")
                .from("""
                        \stank_delivery d
                        JOIN tank t ON t.id = d.tank_id
                        JOIN station st ON st.id = d.station_id
                        JOIN product pr ON pr.id = d.product_id
                        LEFT JOIN employee e ON e.id = d.employee_id""")
                .orderBy("d.ended_at DESC, d.id DESC")
                .filter("period", "d.ended_at")
                .filter("station", "d.station_id = :stationId")
                .filter("product", "d.product_id = :productId")
                .filter("employee", "d.employee_id = :employeeId"));

        add(Extraction.of("inventaires", "STOCKS", "Inventaires (théorique / compté)",
                        "Comptages d'articles et piges de cuves : stock théorique, stock compté, écart, ajustement et opérateur.")
                .col("date", "Date", DATETIME)
                .col("place", "Station / PDV", TEXT)
                .col("product", "Produit", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("book_quantity", "Théorique", QTY)
                .col("counted_quantity", "Compté", QTY)
                .sum("gap", "Écart", QTY)
                .col("unit", "Unité", TEXT)
                .col("adjusted", "Ajusté", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sc.at AS date, COALESCE(st.short_name, pv.name) AS place, pr.name AS product, t.name AS tank, c.book_quantity,"
                        + " c.counted_quantity, c.gap, pr.unit, CASE WHEN c.adjusted THEN 'Oui' ELSE 'Non' END AS adjusted,"
                        + " " + person("e") + " AS operator, c.note")
                .from("""
                        \sstock_count c
                        JOIN product pr ON pr.id = c.product_id
                        LEFT JOIN tank t ON t.id = c.tank_id
                        LEFT JOIN station st ON st.id = c.station_id
                        LEFT JOIN pdv pv ON pv.id = c.pdv_id
                        LEFT JOIN employee e ON e.id = c.employee_id""")
                .orderBy("c.at DESC, c.id DESC")
                .filter("period", "c.at")
                .filter("station", "c.station_id = :stationId")
                .filter("pdv", "c.pdv_id = :pdvId")
                .filter("product", "c.product_id = :productId")
                .filter("employee", "c.employee_id = :employeeId"));

        add(Extraction.of("alarmes-cuves", "STOCKS", "Alarmes des cuves",
                        "Niveau haut, eau en fond de cuve, température, écart jauge / théorique : début, fin, valeur, seuil, acquittement.")
                .col("started_at", "Début", DATETIME)
                .col("ended_at", "Fin", DATETIME)
                .col("station", "Station", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("product", "Produit", TEXT)
                .col("alarm", "Alarme", TEXT)
                .col("state", "État", TEXT)
                .col("value", "Valeur", QTY)
                .col("threshold", "Seuil", QTY)
                .col("acknowledged_by", "Acquittée par", TEXT)
                .col("acknowledged_at", "Acquittée le", DATETIME)
                .col("note", "Note", TEXT)
                .select("\sa.started_at, a.ended_at, st.short_name AS station, t.name AS tank, pr.name AS product,"
                        + " CASE a.kind WHEN 'NIVEAU_HAUT' THEN 'Niveau haut' WHEN 'EAU' THEN 'Eau en fond de cuve'"
                        + " WHEN 'TEMPERATURE' THEN 'Température élevée' ELSE 'Écart jauge / théorique' END AS alarm,"
                        + " CASE WHEN a.ended_at IS NULL THEN 'En cours' ELSE 'Terminée' END AS state, a.value, a.threshold,"
                        + " " + person("e") + " AS acknowledged_by, a.acknowledged_at, a.note")
                .from("""
                        \stank_alarm a
                        JOIN tank t ON t.id = a.tank_id
                        JOIN station st ON st.id = a.station_id
                        JOIN product pr ON pr.id = t.product_id
                        LEFT JOIN employee e ON e.id = a.acknowledged_by""")
                .orderBy("a.ended_at NULLS FIRST, a.started_at DESC")
                .filter("period", "a.started_at")
                .filter("station", "a.station_id = :stationId")
                .filter("product", "t.product_id = :productId")
                .filter("employee", "a.acknowledged_by = :employeeId"));

        add(Extraction.of("mouvements-stock", "STOCKS", "Mouvements de stock",
                        "Entrées, ventes, échanges, livraisons et ajustements d'inventaire, par article ou par cuve.")
                .col("date", "Date", DATETIME)
                .col("place", "Station / PDV", TEXT)
                .col("product", "Produit", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("nature", "Nature", TEXT)
                .sum("quantity", "Quantité", QTY)
                .col("unit", "Unité", TEXT)
                .col("ref", "Référence", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sm.at AS date, COALESCE(st.short_name, pv.name) AS place, pr.name AS product, t.name AS tank,"
                        + " CASE m.kind WHEN 'ENTREE' THEN 'Entrée' WHEN 'VENTE' THEN 'Vente' WHEN 'ECHANGE' THEN 'Échange de bouteille'"
                        + " WHEN 'AJUSTEMENT' THEN 'Ajustement' WHEN 'INVENTAIRE' THEN 'Inventaire' WHEN 'LIVRAISON' THEN 'Livraison'"
                        + " ELSE m.kind END AS nature, m.quantity, pr.unit, m.ref, " + person("e") + " AS operator, m.note")
                .from("""
                        \sstock_movement m
                        JOIN product pr ON pr.id = m.product_id
                        LEFT JOIN tank t ON t.id = m.tank_id
                        LEFT JOIN station st ON st.id = m.station_id
                        LEFT JOIN pdv pv ON pv.id = m.pdv_id
                        LEFT JOIN employee e ON e.id = m.user_id""")
                .orderBy("m.at DESC, m.id DESC")
                .filter("period", "m.at")
                .filter("station", "m.station_id = :stationId")
                .filter("pdv", "m.pdv_id = :pdvId")
                .filter("product", "m.product_id = :productId")
                .filter("employee", "m.user_id = :employeeId"));

        // ============================================================== Cartes : transferts, plafonds, listes (2c, 2f)
        add(Extraction.of("transferts-cartes", "CARTES", "Transferts entre cartes",
                        "Transferts de solde d'une carte à une autre du même titulaire (dont report lors d'un remplacement), avec les soldes avant / après.")
                .col("date", "Date", DATETIME)
                .col("ref", "Référence", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("from_card", "Carte débitée", TEXT)
                .col("to_card", "Carte créditée", TEXT)
                .sum("amount", "Montant (F)", MONEY)
                .col("reason", "Motif", TEXT)
                .col("from_after", "Débitée : solde après (F)", MONEY)
                .col("to_after", "Créditée : solde après (F)", MONEY)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\st.at AS date, t.ref, h.name AS holder, " + cardNo("f") + " AS from_card, " + cardNo("c") + " AS to_card, t.amount,"
                        + " CASE t.reason WHEN 'REMPLACEMENT' THEN 'Remplacement de carte' ELSE 'Transfert' END AS reason,"
                        + " t.from_balance_after AS from_after, t.to_balance_after AS to_after, " + person("e") + " AS operator, t.note")
                .from("""
                        \scard_transfer t
                        JOIN card f ON f.id = t.from_card_id
                        JOIN card c ON c.id = t.to_card_id
                        LEFT JOIN holder h ON h.id = f.holder_id
                        LEFT JOIN employee e ON e.id = t.employee_id""")
                .orderBy("t.at DESC, t.id DESC")
                .filter("period", "t.at")
                .filter("card", "(t.from_card_id = :cardId OR t.to_card_id = :cardId)")
                .filter("holder", "f.holder_id = :holderId")
                .filter("group", "(f.group_id = :groupId OR c.group_id = :groupId)")
                .filter("employee", "t.employee_id = :employeeId"));

        add(Extraction.of("plafonds-cartes", "CARTES", "Plafonds et restrictions des cartes",
                        "Pour chaque carte : plafonds en montant et en litres, pleins par jour, solde minimum, crédit, produits, jours, horaires et stations autorisés.")
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("status", "État", TEXT)
                .col("balance", "Solde (F)", MONEY)
                .col("credit_limit", "Crédit autorisé (F)", MONEY)
                .col("per_tx", "Par transaction (F)", MONEY)
                .col("daily", "Par jour (F)", MONEY)
                .col("monthly", "Par mois (F)", MONEY)
                .col("daily_volume", "Litres par jour", QTY)
                .col("monthly_volume", "Litres par mois", QTY)
                .col("fills", "Pleins par jour", INT)
                .col("min_balance", "Solde minimum (F)", MONEY)
                .col("products", "Produits", TEXT)
                .col("days", "Jours", TEXT)
                .col("hours", "Horaires", TEXT)
                .col("stations", "Stations", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .select("\s" + cardNo("c") + " AS card, h.name AS holder, g.name AS card_group, " + CARD_STATUS + " AS status, c.balance,"
                        + " c.credit_limit, cp.per_tx_limit AS per_tx, cp.daily_limit AS daily, cp.monthly_limit AS monthly,"
                        + " cp.daily_volume_limit AS daily_volume, cp.monthly_volume_limit AS monthly_volume, cp.daily_fills_limit AS fills,"
                        + " cp.min_balance, array_to_string(cp.products, ', ') AS products,"
                        + " CASE WHEN cardinality(cp.days) = 7 THEN 'Tous' ELSE array_to_string(cp.days, ', ') END AS days,"
                        + " CASE WHEN cp.time_from IS NULL THEN 'Toute la journée' ELSE to_char(cp.time_from, 'HH24:MI') || ' – ' || to_char(cp.time_to, 'HH24:MI') END AS hours,"
                        + " CASE WHEN cp.station_ids IS NULL THEN 'Toutes' ELSE (SELECT string_agg(s.short_name, ', ') FROM station s WHERE s.id = ANY(cp.station_ids)) END AS stations,"
                        + " c.vehicle_plate AS vehicle")
                .from("""
                        \scard c
                        JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN card_permission cp ON cp.card_id = c.id""")
                .where("c.status IN ('ACTIVE', 'A_ACTIVER', 'BLOQUEE')")
                .orderBy("h.name, c.number")
                .filter("card", "c.id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("vehicle", "c.vehicle_id = :vehicleId"));

        add(Extraction.of("listes-cartes", "CARTES", "Listes de cartes (noire, grise, blanche)",
                        "Oppositions, cartes sous surveillance et listes blanches des stations : motif, dates, auteur, levée.")
                .col("list", "Liste", TEXT)
                .col("card", "Carte", TEXT)
                .col("holder", "Titulaire", TEXT)
                .col("station", "Station", TEXT)
                .col("reason", "Motif", TEXT)
                .col("added_at", "Ajoutée le", DATETIME)
                .col("added_by", "Par", TEXT)
                .col("valid_until", "Jusqu'au", DATETIME)
                .col("removed_at", "Levée le", DATETIME)
                .col("removed_by", "Levée par", TEXT)
                .col("state", "État", TEXT)
                .select("\sCASE l.kind WHEN 'NOIRE' THEN 'Noire (opposition)' WHEN 'GRISE' THEN 'Grise (surveillance)' ELSE 'Blanche' END AS list,"
                        + " " + cardNo("c") + " AS card, h.name AS holder, s.short_name AS station, l.reason, l.added_at,"
                        + " COALESCE(" + person("a") + ", 'Automatique') AS added_by, l.valid_until, l.removed_at, " + person("r") + " AS removed_by,"
                        + " CASE WHEN l.removed_at IS NOT NULL THEN 'Levée' WHEN l.valid_until IS NOT NULL AND l.valid_until <= now() THEN 'Expirée'"
                        + " ELSE 'En cours' END AS state")
                .from("""
                        \scard_list l
                        JOIN card c ON c.id = l.card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN station s ON s.id = l.station_id
                        LEFT JOIN employee a ON a.id = l.added_by
                        LEFT JOIN employee r ON r.id = l.removed_by""")
                .orderBy("l.removed_at NULLS FIRST, l.added_at DESC")
                .filter("period", "l.added_at")
                .filter("station", "l.station_id = :stationId")
                .filter("card", "l.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("employee", "l.added_by = :employeeId"));

        add(Extraction.of("remplacements-cartes", "CARTES", "Remplacements de cartes",
                        "Cartes perdues, volées ou abîmées remplacées : ancienne et nouvelle carte, solde reporté, frais, opérateur.")
                .col("date", "Date", DATETIME)
                .col("holder", "Titulaire", TEXT)
                .col("old_card", "Ancienne carte", TEXT)
                .col("new_card", "Nouvelle carte", TEXT)
                .col("reason", "Motif", TEXT)
                .sum("balance", "Solde reporté (F)", MONEY)
                .sum("fee", "Frais (F)", MONEY)
                .col("new_status", "Nouvelle carte : état", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sr.at AS date, h.name AS holder, " + cardNo("o") + " AS old_card, " + cardNo("c") + " AS new_card,"
                        + " CASE r.reason WHEN 'PERTE' THEN 'Perte' WHEN 'VOL' THEN 'Vol' WHEN 'DETERIORATION' THEN 'Carte abîmée' ELSE 'Autre' END AS reason,"
                        + " r.balance_transferred AS balance, r.fee, " + CARD_STATUS + " AS new_status, " + person("e") + " AS operator, r.note")
                .from("""
                        \scard_replacement r
                        JOIN card o ON o.id = r.old_card_id
                        JOIN card c ON c.id = r.new_card_id
                        LEFT JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN employee e ON e.id = r.employee_id""")
                .orderBy("r.at DESC")
                .filter("period", "r.at")
                .filter("card", "(r.old_card_id = :cardId OR r.new_card_id = :cardId)")
                .filter("holder", "c.holder_id = :holderId")
                .filter("employee", "r.employee_id = :employeeId"));

        // ============================================================== Fidélité et remises (2d)
        add(Extraction.of("points-fidelite", "FIDELITE", "Mouvements de points de fidélité",
                        "Points gagnés à chaque achat, utilisés (convertis en crédit sur une carte) ou ajustés, avec le solde après.")
                .col("date", "Date", DATETIME)
                .col("holder", "Client", TEXT)
                .col("card", "Carte", TEXT)
                .col("nature", "Nature", TEXT)
                .sum("points", "Points", INT)
                .col("balance_after", "Solde de points", INT)
                .col("sale_total", "Achat (F)", MONEY)
                .sum("amount", "Crédité (F)", MONEY)
                .col("place", "Station", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sm.at AS date, h.name AS holder, CASE WHEN c.id IS NULL THEN NULL ELSE " + cardNo("c") + " END AS card,"
                        + " CASE m.kind WHEN 'GAIN' THEN 'Gain' WHEN 'UTILISATION' THEN 'Utilisation' WHEN 'EXPIRATION' THEN 'Expiration' ELSE 'Ajustement' END AS nature,"
                        + " m.points, m.balance_after, x.total AS sale_total, m.amount, st.short_name AS place, " + person("e") + " AS operator, m.note")
                .from("""
                        \sloyalty_movement m
                        JOIN holder h ON h.id = m.holder_id
                        LEFT JOIN card c ON c.id = m.card_id
                        LEFT JOIN sale x ON x.id = m.sale_id
                        LEFT JOIN station st ON st.id = x.station_id
                        LEFT JOIN employee e ON e.id = m.employee_id""")
                .orderBy("m.at DESC, m.id DESC")
                .filter("period", "m.at")
                .filter("station", "x.station_id = :stationId")
                .filter("card", "m.card_id = :cardId")
                .filter("holder", "m.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("employee", "m.employee_id = :employeeId"));

        add(Extraction.of("soldes-points", "FIDELITE", "Soldes de points et paliers",
                        "Points de chaque client, gagnés et utilisés sur la période, consommation des 12 derniers mois et palier de remise atteint.")
                .col("holder", "Client", TEXT)
                .col("kind", "Type", TEXT)
                .col("points", "Points", INT)
                .sum("earned", "Gagnés", INT)
                .sum("used", "Utilisés", INT)
                .sum("consumption", "Achats 12 mois (F)", MONEY)
                .col("tier", "Palier", TEXT)
                .select("\sh.name AS holder, CASE h.kind WHEN 'PARTICULIER' THEN 'Particulier' ELSE 'Entreprise' END AS kind, h.loyalty_points AS points,"
                        + " COALESCE(mv.earned, 0) AS earned, COALESCE(mv.used, 0) AS used, c12.amount AS consumption,"
                        + " (SELECT d.name FROM discount_rule d WHERE d.active AND d.scope = 'PALIER' AND d.min_consumption <= c12.amount"
                        + " ORDER BY d.min_consumption DESC LIMIT 1) AS tier")
                .from("""
                        \sholder h
                        CROSS JOIN LATERAL (SELECT COALESCE(sum(x.total), 0) AS amount FROM sale x JOIN card c ON c.id = x.card_id
                                            WHERE c.holder_id = h.id AND x.at >= now() - interval '12 months') c12
                        LEFT JOIN LATERAL (
                            SELECT sum(m.points) FILTER (WHERE m.kind = 'GAIN') AS earned, -sum(m.points) FILTER (WHERE m.kind = 'UTILISATION') AS used
                            FROM loyalty_movement m WHERE m.holder_id = h.id
                              AND (CAST(:from AS timestamptz) IS NULL OR m.at >= :from)
                              AND (CAST(:to AS timestamptz) IS NULL OR m.at < :to)) mv ON true""")
                .where("h.kind <> 'EMPLOYE' AND (h.loyalty_points > 0 OR c12.amount > 0)")
                .orderBy("h.loyalty_points DESC, h.name")
                .filter("holder", "h.id = :holderId"));

        add(Extraction.of("remises-accordees", "FIDELITE", "Remises accordées",
                        "Chaque ligne de vente remisée : client, produit, montant brut, remise, montant payé et règle appliquée.")
                .col("date", "Date", DATETIME)
                .col("place", "Station / PDV", TEXT)
                .col("holder", "Client", TEXT)
                .col("card", "Carte", TEXT)
                .col("product", "Produit", TEXT)
                .sum("quantity", "Quantité", QTY)
                .sum("gross", "Montant brut (F)", MONEY)
                .sum("discount", "Remise (F)", MONEY)
                .sum("amount", "Payé (F)", MONEY)
                .col("rule", "Remise appliquée", TEXT)
                .col("operator", "Opérateur", TEXT)
                .select("\sx.at AS date, COALESCE(st.short_name, pv.name) AS place, h.name AS holder, " + cardNo("c") + " AS card, pr.name AS product,"
                        + " l.quantity, l.amount + l.discount_amount AS gross, l.discount_amount AS discount, l.amount,"
                        + " d.name || ' (' || d.discount_pct || ' %)' AS rule, " + person("e") + " AS operator")
                .from("""
                        \ssale_line l
                        JOIN sale x ON x.id = l.sale_id
                        JOIN product pr ON pr.id = l.product_id
                        JOIN card c ON c.id = x.card_id
                        JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN discount_rule d ON d.id = l.discount_rule_id
                        LEFT JOIN station st ON st.id = x.station_id
                        LEFT JOIN pdv pv ON pv.id = x.pdv_id
                        LEFT JOIN employee e ON e.id = x.employee_id""")
                .where("l.discount_amount > 0")
                .orderBy("x.at DESC, l.id")
                .filter("period", "x.at")
                .filter("station", "x.station_id = :stationId")
                .filter("card", "x.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("product", "l.product_id = :productId")
                .filter("employee", "x.employee_id = :employeeId"));

        // ============================================================== Crédit clients (2e)
        add(Extraction.of("factures-credit", "CREDIT", "Relevés de crédit",
                        "Relevés mensuels des clients à crédit : report, consommation, montant dû, réglé, reste, échéance et statut.")
                .col("ref", "Relevé", TEXT)
                .col("holder", "Client", TEXT)
                .col("period_start", "Du", DATE)
                .col("period_end", "Au", DATE)
                .sum("previous_due", "Report (F)", MONEY)
                .sum("consumption", "Consommation (F)", MONEY)
                .sum("amount_due", "Montant dû (F)", MONEY)
                .sum("paid", "Réglé (F)", MONEY)
                .sum("remaining", "Reste (F)", MONEY)
                .col("due_date", "Échéance", DATE)
                .col("status", "Statut", TEXT)
                .col("paid_at", "Soldé le", DATETIME)
                .select("\sb.ref, h.name AS holder, b.period_start, b.period_end, b.previous_due, b.consumption, b.amount_due, b.paid,"
                        + " CASE WHEN b.status = 'REPORTEE' THEN 0 ELSE b.amount_due - b.paid END AS remaining, b.due_date,"
                        + " CASE b.status WHEN 'A_PAYER' THEN 'À payer' WHEN 'PARTIELLE' THEN 'Payé en partie' WHEN 'PAYEE' THEN 'Payé'"
                        + " WHEN 'EN_RETARD' THEN 'En retard' ELSE 'Reporté sur le relevé suivant' END AS status, b.paid_at")
                .from("""
                        \scredit_bill b
                        JOIN holder h ON h.id = b.holder_id""")
                .orderBy("b.period_start DESC, h.name")
                .filter("period", "b.period_start")
                .filter("holder", "b.holder_id = :holderId"));

        add(Extraction.of("lignes-factures", "CREDIT", "Détail des relevés de crédit",
                        "Chaque achat à crédit repris sur un relevé : date, carte, véhicule, station, produit et montant.")
                .col("ref", "Relevé", TEXT)
                .col("holder", "Client", TEXT)
                .col("date", "Date", DATETIME)
                .col("card", "Carte", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("driver", "Chauffeur", TEXT)
                .col("place", "Station", TEXT)
                .col("detail", "Produits", TEXT)
                .sum("amount", "Montant (F)", MONEY)
                .select("\sb.ref, h.name AS holder, l.at AS date, " + cardNo("c") + " AS card, c.vehicle_plate AS vehicle, c.driver_name AS driver,"
                        + " st.short_name AS place, (SELECT string_agg(trim_scale(sl.quantity) || ' ' || p.unit || ' ' || p.name, ', ') FROM sale_line sl"
                        + " JOIN product p ON p.id = sl.product_id WHERE sl.sale_id = l.sale_id) AS detail, l.amount")
                .from("""
                        \scredit_bill_line l
                        JOIN credit_bill b ON b.id = l.bill_id
                        JOIN holder h ON h.id = b.holder_id
                        JOIN card c ON c.id = l.card_id
                        LEFT JOIN sale x ON x.id = l.sale_id
                        LEFT JOIN station st ON st.id = x.station_id""")
                .orderBy("b.period_start DESC, l.at")
                .filter("period", "l.at")
                .filter("station", "x.station_id = :stationId")
                .filter("card", "l.card_id = :cardId")
                .filter("holder", "b.holder_id = :holderId")
                .filter("vehicle", "c.vehicle_id = :vehicleId"));

        add(Extraction.of("reglements-credit", "CREDIT", "Règlements des relevés",
                        "Paiements reçus des clients à crédit : relevé, montant, mode et référence de paiement, opérateur.")
                .col("date", "Date", DATETIME)
                .col("ref", "Relevé", TEXT)
                .col("holder", "Client", TEXT)
                .sum("amount", "Montant (F)", MONEY)
                .col("payment", "Paiement", TEXT)
                .col("payment_ref", "Référence", TEXT)
                .col("operator", "Opérateur", TEXT)
                .col("note", "Note", TEXT)
                .select("\sp.at AS date, b.ref, h.name AS holder, p.amount,"
                        + " CASE p.payment_method WHEN 'ESPECES' THEN 'Espèces' WHEN 'MOBILE_MONEY' THEN 'Mobile money' WHEN 'VIREMENT' THEN 'Virement'"
                        + " WHEN 'CHEQUE' THEN 'Chèque' ELSE 'Carte bancaire' END AS payment, p.payment_ref, " + person("e") + " AS operator, p.note")
                .from("""
                        \scredit_payment p
                        JOIN credit_bill b ON b.id = p.bill_id
                        JOIN holder h ON h.id = b.holder_id
                        LEFT JOIN employee e ON e.id = p.employee_id""")
                .orderBy("p.at DESC")
                .filter("period", "p.at")
                .filter("holder", "b.holder_id = :holderId")
                .filter("employee", "p.employee_id = :employeeId"));

        add(Extraction.of("encours-credit", "CREDIT", "Encours des cartes de crédit",
                        "Cartes de crédit : plafond, utilisé, disponible, consommation du mois en cours.")
                .col("holder", "Client", TEXT)
                .col("card", "Carte", TEXT)
                .col("card_group", "Groupe", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("status", "État", TEXT)
                .sum("credit_limit", "Plafond (F)", MONEY)
                .sum("used", "Utilisé (F)", MONEY)
                .sum("available", "Disponible (F)", MONEY)
                .sum("month", "Mois en cours (F)", MONEY)
                .select("\sh.name AS holder, " + cardNo("c") + " AS card, g.name AS card_group, c.vehicle_plate AS vehicle, " + CARD_STATUS + " AS status,"
                        + " c.credit_limit, GREATEST(-c.balance, 0) AS used, c.credit_limit + c.balance AS available,"
                        + " (SELECT COALESCE(-sum(m.amount), 0) FROM card_movement m WHERE m.card_id = c.id AND m.kind = 'VENTE'"
                        + " AND m.at >= date_trunc('month', now())) AS month")
                .from("""
                        \scard c
                        JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id""")
                .where("c.credit_limit > 0")
                .orderBy("h.name, c.number")
                .filter("card", "c.id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId"));

        // ============================================================== Prix et approvisionnement (2g)
        add(Extraction.of("historique-prix", "APPRO", "Historique des prix",
                        "Chaque changement de prix par produit et profil : ancien, nouveau, écart, date d'effet, statut (appliqué, planifié, annulé), auteur.")
                .col("effective_at", "Date d'effet", DATETIME)
                .col("product", "Produit", TEXT)
                .col("category", "Catégorie", TEXT)
                .col("profile", "Profil", TEXT)
                .col("old_price", "Ancien prix (F)", MONEY)
                .col("new_price", "Nouveau prix (F)", MONEY)
                .col("change", "Écart (F)", MONEY)
                .col("status", "Statut", TEXT)
                .col("author", "Auteur", TEXT)
                .col("note", "Note", TEXT)
                .select("\spc.effective_at, pr.name AS product, " + CATEGORY + " AS category,"
                        + " CASE pc.profile WHEN 'GRAND_PUBLIC' THEN 'Grand public' WHEN 'EMPLOYE_STATION' THEN 'Employé station'"
                        + " WHEN 'EMPLOYE_GROUPE' THEN 'Employé groupe' ELSE 'Partenaire' END AS profile, pc.old_price, pc.new_price,"
                        + " pc.new_price - pc.old_price AS change,"
                        + " CASE pc.status WHEN 'APPLIQUE' THEN 'Appliqué' WHEN 'PLANIFIE' THEN 'Planifié' ELSE 'Annulé' END AS status,"
                        + " " + person("e") + " AS author, pc.note")
                .from("""
                        \sprice_change pc
                        JOIN product pr ON pr.id = pc.product_id
                        LEFT JOIN employee e ON e.id = pc.created_by""")
                .orderBy("pc.effective_at DESC, pr.name")
                .filter("period", "pc.effective_at")
                .filter("product", "pc.product_id = :productId")
                .filter("employee", "pc.created_by = :employeeId"));

        add(Extraction.of("commandes-fournisseurs", "APPRO", "Commandes fournisseurs",
                        "Commandes de carburant : fournisseur, station, cuve, quantité commandée et reçue, écart, prix d'achat, montant, dates et statut.")
                .col("ref", "Commande", TEXT)
                .col("ordered_at", "Commandée le", DATETIME)
                .col("supplier", "Fournisseur", TEXT)
                .col("station", "Station", TEXT)
                .col("tank", "Cuve", TEXT)
                .col("product", "Produit", TEXT)
                .sum("quantity", "Commandé (L)", QTY)
                .sum("received", "Reçu (L)", QTY)
                .sum("gap", "Écart (L)", QTY)
                .col("unit_price", "Prix d'achat (F/L)", MONEY)
                .sum("amount", "Montant (F)", MONEY)
                .col("status", "Statut", TEXT)
                .col("expected_at", "Prévue le", DATETIME)
                .col("delivered_at", "Livrée le", DATETIME)
                .col("truck", "Camion", TEXT)
                .col("driver", "Chauffeur", TEXT)
                .col("seller", "Commercial", TEXT)
                .col("operator", "Opérateur", TEXT)
                .select("\so.ref, o.ordered_at, s.name AS supplier, st.short_name AS station, t.name AS tank, pr.name AS product, o.quantity,"
                        + " o.received_volume AS received, o.received_volume - o.quantity AS gap, o.unit_price, o.amount,"
                        + " CASE o.status WHEN 'BROUILLON' THEN 'Brouillon' WHEN 'COMMANDEE' THEN 'Commandée' WHEN 'EXPEDIEE' THEN 'Expédiée'"
                        + " WHEN 'LIVREE' THEN 'Livrée' ELSE 'Annulée' END AS status, o.expected_at, o.delivered_at, o.truck_plate AS truck,"
                        + " o.driver_name AS driver, o.seller_name AS seller, " + person("e") + " AS operator")
                .from("""
                        \ssupplier_order o
                        JOIN supplier s ON s.id = o.supplier_id
                        JOIN station st ON st.id = o.station_id
                        JOIN product pr ON pr.id = o.product_id
                        LEFT JOIN tank t ON t.id = o.tank_id
                        LEFT JOIN employee e ON e.id = o.created_by""")
                .orderBy("o.ordered_at DESC, o.id DESC")
                .filter("period", "o.ordered_at")
                .filter("station", "o.station_id = :stationId")
                .filter("product", "o.product_id = :productId")
                .filter("employee", "o.created_by = :employeeId"));

        add(Extraction.of("fournisseurs", "APPRO", "Fournisseurs",
                        "Dépôts pétroliers et fournisseurs : contact, commandes, volumes et montants livrés sur la période, écart moyen.")
                .col("name", "Fournisseur", TEXT)
                .col("kind", "Type", TEXT)
                .col("contact", "Contact", TEXT)
                .col("phone", "Téléphone", TEXT)
                .col("email", "Email", TEXT)
                .sum("orders", "Commandes", INT)
                .sum("delivered", "Livré (L)", QTY)
                .sum("amount", "Montant (F)", MONEY)
                .sum("gap", "Écart cumulé (L)", QTY)
                .col("state", "État", TEXT)
                .select("\ss.name, CASE s.kind WHEN 'CARBURANT' THEN 'Carburant' WHEN 'BOUTIQUE' THEN 'Boutique' WHEN 'GAZ' THEN 'Gaz' ELSE 'Autre' END AS kind,"
                        + " s.contact_name AS contact, s.phone, s.email, COALESCE(o.n, 0) AS orders, COALESCE(o.delivered, 0) AS delivered,"
                        + " COALESCE(o.amount, 0) AS amount, COALESCE(o.gap, 0) AS gap, CASE WHEN s.active THEN 'Actif' ELSE 'Inactif' END AS state")
                .from("""
                        \ssupplier s
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS n, sum(so.received_volume) AS delivered, sum(so.amount) FILTER (WHERE so.status = 'LIVREE') AS amount,
                                   sum(so.received_volume - so.quantity) AS gap
                            FROM supplier_order so WHERE so.supplier_id = s.id
                              AND (CAST(:from AS timestamptz) IS NULL OR so.ordered_at >= :from)
                              AND (CAST(:to AS timestamptz) IS NULL OR so.ordered_at < :to)) o ON true""")
                .orderBy("s.active DESC, s.name"));

        // ============================================================== Opérateurs et postes
        add(Extraction.of("postes", "OPERATEURS", "Postes et remises d'espèces",
                        "Chaque poste : opérateur, station, ventes, espèces à remettre, espèces remises et reste à remettre.")
                .col("started_at", "Début", DATETIME)
                .col("ended_at", "Fin", DATETIME)
                .col("operator", "Opérateur", TEXT)
                .col("role", "Rôle", TEXT)
                .col("station", "Station", TEXT)
                .col("shift", "Poste", TEXT)
                .col("status", "État", TEXT)
                .col("pumps", "Pompes", TEXT)
                .sum("transactions", "Ventes", INT)
                .sum("revenue", "Chiffre d'affaires (F)", MONEY)
                .sum("expected_cash", "Espèces à remettre (F)", MONEY)
                .sum("remitted", "Espèces remises (F)", MONEY)
                .sum("remaining", "Reste à remettre (F)", MONEY)
                .select("\ses.started_at, es.ended_at, " + person("e") + " AS operator, r.label AS role, st.short_name AS station,"
                        + " COALESCE(sc.name, 'Poste') AS shift,"
                        + " CASE es.status WHEN 'EN_POSTE' THEN 'En poste' WHEN 'PAUSE' THEN 'En pause' ELSE 'Clos' END AS status,"
                        + " es.pumps, COALESCE(sx.n, 0) AS transactions, COALESCE(sx.revenue, 0) AS revenue,"
                        + " COALESCE(es.expected_cash, sx.cash, 0) AS expected_cash, COALESCE(rm.remitted, 0) AS remitted,"
                        + " COALESCE(es.expected_cash, sx.cash, 0) - COALESCE(rm.remitted, 0) AS remaining")
                .from("""
                        \semployee_shift es
                        JOIN employee e ON e.id = es.employee_id
                        JOIN role r ON r.code = e.role_code
                        JOIN station st ON st.id = es.station_id
                        LEFT JOIN shift_config sc ON sc.id = es.config_id
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS n, sum(x.total) AS revenue,
                                   sum(x.total) FILTER (WHERE x.payment_method = 'ESPECES') AS cash
                            FROM sale x WHERE x.employee_shift_id = es.id) sx ON true
                        LEFT JOIN LATERAL (
                            SELECT sum(sr.amount) AS remitted FROM shift_remittance sr WHERE sr.shift_id = es.id) rm ON true""")
                .orderBy("es.started_at DESC, es.id DESC")
                .filter("period", "es.started_at")
                .filter("station", "es.station_id = :stationId")
                .filter("employee", "es.employee_id = :employeeId")
                .filter("shift", "es.id = :shiftId"));

        add(Extraction.of("operateurs", "OPERATEURS", "Synthèse par opérateur",
                        "Pour chaque opérateur : postes, ventes par mode de paiement, carburant servi et espèces remises sur la période.")
                .col("operator", "Opérateur", TEXT)
                .col("role", "Rôle", TEXT)
                .col("station", "Station", TEXT)
                .sum("shifts", "Postes", INT)
                .sum("transactions", "Ventes", INT)
                .sum("liters", "Carburant (L)", QTY)
                .sum("cash", "Espèces (F)", MONEY)
                .sum("mobile_money", "Mobile money (F)", MONEY)
                .sum("card_amount", "Carte (F)", MONEY)
                .sum("amount", "Total (F)", MONEY)
                .sum("remitted", "Espèces remises (F)", MONEY)
                .select("\s" + person("e") + " AS operator, ro.label AS role, est.short_name AS station,"
                        + " count(DISTINCT x.employee_shift_id) AS shifts, count(*) AS transactions,"
                        + " COALESCE(sum(fl.liters), 0) AS liters,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'ESPECES'), 0) AS cash,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'MOBILE_MONEY'), 0) AS mobile_money,"
                        + " COALESCE(sum(x.total) FILTER (WHERE x.payment_method = 'CARTE'), 0) AS card_amount,"
                        + " sum(x.total) AS amount,"
                        + " COALESCE((SELECT sum(sr.amount) FROM shift_remittance sr JOIN employee_shift es2 ON es2.id = sr.shift_id"
                        + " WHERE es2.employee_id = e.id AND (CAST(:from AS timestamptz) IS NULL OR es2.started_at >= :from)"
                        + " AND (CAST(:to AS timestamptz) IS NULL OR es2.started_at < :to)"
                        + " AND (CAST(:stationId AS bigint) IS NULL OR es2.station_id = :stationId)), 0) AS remitted")
                .from("""
                        \ssale x
                        JOIN employee e ON e.id = x.employee_id
                        JOIN role ro ON ro.code = e.role_code
                        LEFT JOIN station est ON est.id = e.station_id
                        LEFT JOIN card c ON c.id = x.card_id
                        LEFT JOIN LATERAL (
                            SELECT sum(fl.quantity) AS liters FROM sale_line fl JOIN product fp ON fp.id = fl.product_id
                            WHERE fl.sale_id = x.id AND fp.category = 'CARBURANT') fl ON true""")
                .groupBy("e.id, e.first_name, e.last_name, ro.label, est.short_name")
                .orderBy("sum(x.total) DESC")
                .filter("period", "x.at")
                .filter("station", "x.station_id = :stationId")
                .filter("pdv", "x.pdv_id = :pdvId")
                .filter("product", "EXISTS (SELECT 1 FROM sale_line l2 WHERE l2.sale_id = x.id AND l2.product_id = :productId)")
                .filter("employee", "x.employee_id = :employeeId")
                .filter("shift", "x.employee_shift_id = :shiftId"));

        add(Extraction.of("activite-operateurs", "OPERATEURS", "Activité par opérateur",
                        "Par opérateur, par jour et par poste : ventes, recharges, opérations sur les cartes et opérations journalisées.")
                .col("day", "Jour", DATE)
                .col("operator", "Opérateur", TEXT)
                .col("operator_type", "Type d'opérateur", TEXT)
                .col("station", "Station / PDV", TEXT)
                .col("shift", "Poste", TEXT)
                .sum("sales", "Ventes", INT)
                .sum("sales_amount", "Montant ventes (F)", MONEY)
                .sum("recharges", "Recharges", INT)
                .sum("recharges_amount", "Montant recharges (F)", MONEY)
                .sum("card_ops", "Opérations cartes", INT)
                .sum("journal_ops", "Opérations journalisées", INT)
                .col("first_at", "Première opération", DATETIME)
                .col("last_at", "Dernière opération", DATETIME)
                .select("\sCAST(a.at AS date) AS day, " + person("e") + " AS operator, ro.label AS operator_type,"
                        + " string_agg(DISTINCT COALESCE(st.short_name, pv.name), ', ') AS station,"
                        + " " + SHIFT_LABEL + " AS shift,"
                        + " count(*) FILTER (WHERE a.kind = 'VENTE') AS sales,"
                        + " COALESCE(sum(a.amount) FILTER (WHERE a.kind = 'VENTE'), 0) AS sales_amount,"
                        + " count(*) FILTER (WHERE a.kind = 'RECHARGE') AS recharges,"
                        + " COALESCE(sum(a.amount) FILTER (WHERE a.kind = 'RECHARGE'), 0) AS recharges_amount,"
                        + " count(*) FILTER (WHERE a.kind = 'CARTE') AS card_ops,"
                        + " count(*) FILTER (WHERE a.kind = 'JOURNAL') AS journal_ops,"
                        + " min(a.at) AS first_at, max(a.at) AS last_at")
                .from("""
                        \s(SELECT x.at, x.employee_id, x.station_id, x.pdv_id, x.employee_shift_id AS shift_id, 'VENTE' AS kind,
                                x.total AS amount
                         FROM sale x WHERE x.employee_id IS NOT NULL
                         UNION ALL
                         SELECT r.created_at, r.requested_by, r.station_id, r.pdv_id, NULL, 'RECHARGE', r.amount FROM recharge r
                         UNION ALL
                         SELECT ce.at, ce.employee_id, ce.station_id, ce.pdv_id, NULL, 'CARTE', NULL
                         FROM card_event ce WHERE ce.employee_id IS NOT NULL
                         UNION ALL
                         SELECT o.at, o.actor_id, o.station_id, o.pdv_id, NULL, 'JOURNAL', NULL
                         FROM operation_log o WHERE o.actor_id IS NOT NULL) a
                        JOIN employee e ON e.id = a.employee_id
                        JOIN role ro ON ro.code = e.role_code
                        LEFT JOIN LATERAL (
                            SELECT es0.id FROM employee_shift es0
                            WHERE a.shift_id IS NULL AND es0.employee_id = a.employee_id AND es0.started_at <= a.at
                              AND (es0.ended_at IS NULL OR es0.ended_at > a.at)
                            ORDER BY es0.started_at DESC LIMIT 1) sh ON true
                        LEFT JOIN employee_shift es ON es.id = COALESCE(a.shift_id, sh.id)
                        LEFT JOIN shift_config sc ON sc.id = es.config_id
                        LEFT JOIN station st ON st.id = a.station_id
                        LEFT JOIN pdv pv ON pv.id = a.pdv_id""")
                .groupBy("CAST(a.at AS date), e.id, e.first_name, e.last_name, ro.label, es.id, es.started_at, sc.name")
                .orderBy("CAST(a.at AS date) DESC, e.last_name, e.first_name, es.started_at")
                .filter("period", "a.at")
                .filter("station", "a.station_id = :stationId")
                .filter("pdv", "a.pdv_id = :pdvId")
                .filter("employee", "a.employee_id = :employeeId")
                .filter("shift", "COALESCE(a.shift_id, sh.id) = :shiftId"));

        // ============================================================== Ordres de travail et audit
        add(Extraction.of("ordres-travail", "OPERATIONS", "Ordres de travail (Zen Mobility)",
                        "Commandes de carburant livrées : client, carte, produit, quantités, agent, véhicule, statut et créateur.")
                .col("ref", "Référence", TEXT)
                .col("created_at", "Créée le", DATETIME)
                .col("requested_for", "Souhaitée pour", DATETIME)
                .col("holder", "Client", TEXT)
                .col("card", "Carte", TEXT)
                .col("product", "Produit", TEXT)
                .sum("quantity", "Quantité commandée (L)", QTY)
                .sum("delivered_quantity", "Quantité livrée (L)", QTY)
                .col("unit_price", "Prix unitaire (F)", MONEY)
                .sum("amount", "Montant (F)", MONEY)
                .col("payment", "Paiement", TEXT)
                .col("address", "Adresse", TEXT)
                .col("source", "Station de départ", TEXT)
                .col("agent", "Agent livreur", TEXT)
                .col("vehicle", "Véhicule", TEXT)
                .col("status", "Statut", TEXT)
                .col("created_by", "Créée par", TEXT)
                .col("departed_at", "Départ", DATETIME)
                .col("delivered_at", "Livrée le", DATETIME)
                .col("cancel_reason", "Motif d'annulation", TEXT)
                .select("\sd.ref, d.created_at, d.requested_for, h.name AS holder,"
                        + " CASE WHEN c.id IS NULL THEN NULL ELSE " + cardNo("c") + " END AS card, pr.name AS product,"
                        + " d.quantity, d.delivered_quantity, d.unit_price,"
                        + " round(COALESCE(d.delivered_quantity, d.quantity) * d.unit_price) AS amount,"
                        + " " + payment("d.payment_method") + " AS payment, d.address, st.short_name AS source,"
                        + " " + person("a") + " AS agent, d.vehicle,"
                        + " CASE d.status WHEN 'DEMANDEE' THEN 'Demandée' WHEN 'VALIDEE' THEN 'Validée' WHEN 'EN_ROUTE' THEN 'En route'"
                        + " WHEN 'LIVREE' THEN 'Livrée' ELSE 'Annulée' END AS status,"
                        + " " + person("cb") + " AS created_by, d.departed_at, d.delivered_at, d.cancel_reason")
                .from("""
                        \sdelivery_order d
                        JOIN holder h ON h.id = d.holder_id
                        JOIN product pr ON pr.id = d.product_id
                        LEFT JOIN card c ON c.id = d.card_id
                        LEFT JOIN station st ON st.id = d.source_station_id
                        LEFT JOIN employee a ON a.id = d.agent_id
                        LEFT JOIN employee cb ON cb.id = d.created_by""")
                .orderBy("d.created_at DESC, d.id DESC")
                .filter("period", "d.created_at")
                .filter("station", "d.source_station_id = :stationId")
                .filter("card", "d.card_id = :cardId")
                .filter("holder", "d.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("product", "d.product_id = :productId")
                .filter("employee", "(d.agent_id = :employeeId OR d.created_by = :employeeId)"));

        add(Extraction.of("audit", "OPERATIONS", "Journal d'audit",
                        "Toutes les opérations journalisées : qui, quand, où, quoi (ventes, recharges, validations, modifications, extractions…).")
                .col("date", "Date", DATETIME)
                .col("actor", "Auteur", TEXT)
                .col("role", "Rôle", TEXT)
                .col("category", "Catégorie", TEXT)
                .col("type", "Type", TEXT)
                .col("title", "Opération", TEXT)
                .col("detail", "Détail", TEXT)
                .sum("amount", "Montant (F)", MONEY)
                .col("place", "Station / PDV", TEXT)
                .select("\so.at AS date, COALESCE(" + person("e") + ", 'Système') AS actor, r.label AS role,"
                        + " CASE o.category WHEN 'VENTES_CARBURANT' THEN 'Ventes carburant' WHEN 'RECHARGES' THEN 'Recharges'"
                        + " WHEN 'VENTES_CARTES' THEN 'Ventes de cartes' WHEN 'VENTES_BOUTIQUE' THEN 'Boutique'"
                        + " WHEN 'VALIDATIONS' THEN 'Validations' WHEN 'MODIFICATIONS' THEN 'Modifications' WHEN 'SYSTEME' THEN 'Système'"
                        + " WHEN 'LIVRAISONS' THEN 'Livraisons' WHEN 'EXTRACTIONS' THEN 'Extractions' ELSE o.category END AS category,"
                        + " o.type, o.title, o.detail, o.amount, COALESCE(st.short_name, pv.name) AS place")
                .from("""
                        \soperation_log o
                        LEFT JOIN employee e ON e.id = o.actor_id
                        LEFT JOIN role r ON r.code = e.role_code
                        LEFT JOIN station st ON st.id = o.station_id
                        LEFT JOIN pdv pv ON pv.id = o.pdv_id""")
                .orderBy("o.at DESC, o.id DESC")
                .filter("period", "o.at")
                .filter("station", "o.station_id = :stationId")
                .filter("pdv", "o.pdv_id = :pdvId")
                .filter("employee", "o.actor_id = :employeeId"));
    }

    /** Consommation des cartes de flotte regroupée par véhicule ou par chauffeur (véhicule et chauffeur au moment du plein). */
    private void consumption(String code, String label, String description, String key, String name, String header) {
        add(Extraction.of(code, "FLOTTES", label, description)
                .col("subject", header, TEXT)
                .col("other", "x.vehicle_id".equals(key) ? "Chauffeurs" : "Véhicules", TEXT)
                .col("holder", "Entreprise", TEXT)
                .col("fleet", "Flotte", TEXT)
                .sum("fills", "Pleins", INT)
                .sum("liters", "Litres", QTY)
                .sum("amount", "Montant (F)", MONEY)
                .col("avg_fill", "Litres par plein", QTY)
                .col("first_km", "Kilométrage début", INT)
                .col("last_km", "Kilométrage fin", INT)
                .sum("distance_km", "Km parcourus", INT)
                .col("per_100km", "L / 100 km", QTY)
                .col("first_at", "Premier plein", DATETIME)
                .col("last_at", "Dernier plein", DATETIME)
                .select("\s" + name + " AS subject, string_agg(DISTINCT " + ("x.vehicle_id".equals(key) ? "d.name" : "v.plate") + ", ', ') AS other,"
                        + " h.name AS holder, g.name AS fleet, count(DISTINCT x.id) AS fills, sum(l.quantity) AS liters, sum(l.amount) AS amount, round(sum(l.quantity) / NULLIF(count(DISTINCT x.id), 0), 2) AS avg_fill,"
                        + " min(x.odometer_km) AS first_km, max(x.odometer_km) AS last_km,"
                        + " max(x.odometer_km) - min(x.odometer_km) AS distance_km,"
                        // carburant consommé entre le premier et le dernier kilométrage : pleins avec kilométrage, moins le premier
                        + " round(100 * (sum(l.quantity) FILTER (WHERE x.odometer_km IS NOT NULL)"
                        + " - (array_agg(l.quantity ORDER BY x.odometer_km) FILTER (WHERE x.odometer_km IS NOT NULL))[1])"
                        + " / NULLIF(max(x.odometer_km) - min(x.odometer_km), 0), 2) AS per_100km,"
                        + " min(x.at) AS first_at, max(x.at) AS last_at")
                .from("""
                        \ssale x
                        JOIN sale_line l ON l.sale_id = x.id
                        JOIN product pr ON pr.id = l.product_id
                        JOIN card c ON c.id = x.card_id
                        JOIN holder h ON h.id = c.holder_id
                        LEFT JOIN card_group g ON g.id = c.group_id
                        LEFT JOIN vehicle v ON v.id = x.vehicle_id
                        LEFT JOIN driver d ON d.id = x.driver_id""")
                .where(key + " IS NOT NULL AND pr.category = 'CARBURANT'")
                .groupBy(key + ", " + name + ", h.name, g.name")
                .orderBy("sum(l.amount) DESC")
                .filter("period", "x.at")
                .filter("station", "x.station_id = :stationId")
                .filter("card", "x.card_id = :cardId")
                .filter("holder", "c.holder_id = :holderId")
                .filter("group", "c.group_id = :groupId")
                .filter("vehicle", "x.vehicle_id = :vehicleId")
                .filter("driver", "x.driver_id = :driverId")
                .filter("product", "l.product_id = :productId"));
    }

    /** Relevés d'index des pistolets (POSTE, JOUR ou MOIS). */
    private void pumpReport(String kind, String code, String label, String description) {
        Extraction.Builder b = Extraction.of(code, "STOCKS", label, description)
                .col("period", "Période", "MOIS".equals(kind) ? TEXT : DATE)
                .col("started_at", "Début", DATETIME)
                .col("ended_at", "Fin", DATETIME)
                .col("station", "Station", TEXT)
                .col("pump", "Pistolet", TEXT)
                .col("product", "Produit", TEXT);
        if ("POSTE".equals(kind)) {
            b.col("operator", "Opérateur", TEXT).col("shift", "Poste", TEXT);
        }
        b.col("start_index", "Index début", QTY)
                .col("end_index", "Index fin", QTY)
                .sum("meter_volume", "Volume compteur (L)", QTY)
                .sum("sold_volume", "Volume vendu (L)", QTY)
                .sum("gap", "Écart (L)", QTY)
                .sum("transactions", "Ventes", INT)
                .sum("amount", "Montant (F)", MONEY)
                .select("\s" + ("MOIS".equals(kind) ? "to_char(r.period_start, 'MM/YYYY')" : "r.period_start") + " AS period,"
                        + " r.started_at, r.ended_at, st.short_name AS station, pu.code AS pump, pr.name AS product,"
                        + ("POSTE".equals(kind) ? " " + person("e") + " AS operator, COALESCE(sc.name, 'Poste') AS shift," : "")
                        + " r.start_index, r.end_index, r.meter_volume, r.sold_volume, r.gap, r.transactions, r.amount")
                .from("""
                        \spump_report r
                        JOIN pump pu ON pu.id = r.pump_id
                        JOIN station st ON st.id = r.station_id
                        JOIN product pr ON pr.id = r.product_id
                        LEFT JOIN employee_shift es ON es.id = r.employee_shift_id
                        LEFT JOIN employee e ON e.id = es.employee_id
                        LEFT JOIN shift_config sc ON sc.id = es.config_id""")
                .where("r.kind = '" + kind + "'")
                .orderBy("r.period_start DESC, r.started_at DESC, st.short_name, pu.code")
                .filter("period", "r.started_at")
                .filter("station", "r.station_id = :stationId")
                .filter("product", "r.product_id = :productId");
        if ("POSTE".equals(kind)) {
            b.filter("employee", "es.employee_id = :employeeId").filter("shift", "r.employee_shift_id = :shiftId");
        }
        add(b);
    }

    private void add(Extraction.Builder b) {
        Extraction e = b.build();
        all.put(e.code(), e);
    }

    /** Ajout d'une extraction par un autre module (domaines de la phase 2). */
    public void register(Extraction e) {
        all.put(e.code(), e);
    }

    public List<Extraction> list() {
        return new ArrayList<>(all.values());
    }

    public Extraction get(String code) {
        Extraction e = all.get(code);
        if (e == null) {
            throw ApiException.notFound("Extraction inconnue : " + code);
        }
        return e;
    }
}
