package tg.zener.station.common;

import java.util.List;
import java.util.Map;

/** Page de résultats : éléments, total, numéro de page (à partir de 0) et taille. */
public record Page(List<Map<String, Object>> items, long total, int page, int size) {

    public static int size(Integer size) {
        return size == null ? 25 : Math.max(1, Math.min(size, 200));
    }

    public static int page(Integer page) {
        return page == null ? 0 : Math.max(0, page);
    }
}
