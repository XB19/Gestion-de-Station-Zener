package tg.zener.station.extractions;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tg.zener.station.common.ApiException;

/** Filtres communs à toutes les extractions (paramètres de la requête HTTP). */
public record ExtractionFilters(LocalDate from, LocalDate to, Long stationId, Long pdvId, Long cardId, Long holderId,
                                Long groupId, Long productId, Long employeeId, Long shiftId, Long vehicleId, Long driverId) {

    /** Noms des filtres, dans l'ordre d'affichage. */
    public static final List<String> NAMES = List.of("period", "station", "pdv", "card", "holder", "group", "vehicle", "driver",
            "product", "employee", "shift");

    /** Paramètre HTTP de chaque filtre (hors période : from / to). */
    static final Map<String, String> PARAMS = Map.ofEntries(Map.entry("station", "stationId"), Map.entry("pdv", "pdvId"),
            Map.entry("card", "cardId"), Map.entry("holder", "holderId"), Map.entry("group", "groupId"),
            Map.entry("product", "productId"), Map.entry("employee", "employeeId"), Map.entry("shift", "shiftId"),
            Map.entry("vehicle", "vehicleId"), Map.entry("driver", "driverId"));

    public static ExtractionFilters parse(Map<String, String> q) {
        LocalDate from = date(q, "from");
        LocalDate to = date(q, "to");
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException("La date de fin précède la date de début");
        }
        return new ExtractionFilters(from, to, id(q, "stationId"), id(q, "pdvId"), id(q, "cardId"), id(q, "holderId"),
                id(q, "groupId"), id(q, "productId"), id(q, "employeeId"), id(q, "shiftId"), id(q, "vehicleId"), id(q, "driverId"));
    }

    public ExtractionFilters withStation(Long station) {
        return new ExtractionFilters(from, to, station, pdvId, cardId, holderId, groupId, productId, employeeId, shiftId, vehicleId,
                driverId);
    }

    /** Valeur du filtre (nul s'il n'est pas renseigné). */
    public Object value(String name) {
        return switch (name) {
            case "period" -> from != null || to != null ? Boolean.TRUE : null;
            case "station" -> stationId;
            case "pdv" -> pdvId;
            case "card" -> cardId;
            case "holder" -> holderId;
            case "group" -> groupId;
            case "product" -> productId;
            case "employee" -> employeeId;
            case "shift" -> shiftId;
            case "vehicle" -> vehicleId;
            case "driver" -> driverId;
            default -> null;
        };
    }

    /** Paramètres liés à la requête ; :to est exclusif (lendemain de la date de fin). */
    public Map<String, Object> params() {
        Map<String, Object> p = new HashMap<>();
        p.put("from", from);
        p.put("to", to == null ? null : to.plusDays(1));
        p.put("stationId", stationId);
        p.put("pdvId", pdvId);
        p.put("cardId", cardId);
        p.put("holderId", holderId);
        p.put("groupId", groupId);
        p.put("productId", productId);
        p.put("employeeId", employeeId);
        p.put("shiftId", shiftId);
        p.put("vehicleId", vehicleId);
        p.put("driverId", driverId);
        return p;
    }

    private static LocalDate date(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException("Date invalide (" + key + ") : " + v);
        }
    }

    private static Long id(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new ApiException("Identifiant invalide (" + key + ") : " + v);
        }
    }
}
