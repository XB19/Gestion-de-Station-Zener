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
        SECTIONS.put("VENTES", "Ventes");
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
                        + " CASE m.kind WHEN 'VENTE' THEN 'Achat' WHEN 'RECHARGE' THEN 'Recharge' ELSE 'Ajustement' END AS operation,"
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
                        + " CASE m.kind WHEN 'VENTE' THEN 'Achat' WHEN 'RECHARGE' THEN 'Recharge' ELSE 'Ajustement' END AS nature,"
                        + " CASE WHEN m.amount > 0 THEN m.amount END AS credit, CASE WHEN m.amount < 0 THEN -m.amount END AS debit,"
                        + " m.balance_after,"
                        + " CASE WHEN r.id IS NOT NULL THEN 'Recharge ' || r.ref WHEN x.id IS NOT NULL THEN 'Vente n° ' || x.id"
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
