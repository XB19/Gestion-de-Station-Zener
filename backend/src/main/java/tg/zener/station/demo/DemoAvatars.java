package tg.zener.station.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.employees.PhotoStore;

/**
 * Photos de profil de démonstration : un portrait simple en SVG pour chaque compte (teint, coiffure
 * et fond variés selon l'identifiant, tenue selon le rôle). Les fichiers sont écrits dans le dossier
 * des photos (app.upload-dir/photos) et servis comme une photo envoyée. Un employé qui a déjà une
 * photo n'est pas modifié. À retirer avec le reste du paquet demo.
 */
@Component
public class DemoAvatars {

    private static final Logger log = LoggerFactory.getLogger(DemoAvatars.class);
    static final String PREFIX = "photos/demo-avatar-";

    private static final String[] SKIN = {"#8d5524", "#7a4524", "#6b3a1e", "#5c3317", "#a26a3a", "#4b2a14", "#94603a"};
    private static final String[] HAIR = {"#1a1110", "#241712", "#2e1d14", "#3b2416"};
    private static final String[] BACKGROUND = {"#dbeafe", "#dcfce7", "#fef3c7", "#ede9fe", "#fce7f3", "#e0f2fe", "#ecfccb", "#ffedd5"};
    private static final String[] WRAP = {"#1b66b8", "#5aa935", "#f29e1f", "#c2185b", "#6d3fd6", "#13917d"};

    /** Tenue : couleur principale, couleur du col / de la cravate, type (POLO, COSTUME, GILET). */
    private record Outfit(String main, String accent, String kind) {}

    private static Outfit outfit(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> new Outfit("#1e293b", "#1b66b8", "COSTUME");
            case "DAF" -> new Outfit("#334155", "#5aa935", "COSTUME");
            case "COMPTABLE" -> new Outfit("#475569", "#f8fafc", "COSTUME");
            case "GERANT" -> new Outfit("#1b66b8", "#ffffff", "POLO");
            case "CHEF_POSTE" -> new Outfit("#13306b", "#8cc63f", "POLO");
            case "AGENT_PDV" -> new Outfit("#6d3fd6", "#ffffff", "POLO");
            case "LIVREUR" -> new Outfit("#f97316", "#fde68a", "GILET");
            default -> new Outfit("#5aa935", "#1b66b8", "POLO"); // pompiste : polo vert ZENER
        };
    }

    private final Db db;
    private final PhotoStore photos;

    public DemoAvatars(Db db, PhotoStore photos) {
        this.db = db;
        this.photos = photos;
    }

    /** Donne un portrait à chaque employé sans photo (et refait les fichiers de démonstration disparus). */
    public int ensureAll() {
        int n = 0;
        for (Map<String, Object> e : db.list("""
                \sSELECT id, role_code, photo_path FROM employee
                WHERE photo_path IS NULL OR photo_path LIKE 'photos/demo-avatar-%' ORDER BY id""")) {
            long id = ((Number) e.get("id")).longValue();
            String role = (String) e.get("roleCode");
            String path = PREFIX + id + ".svg";
            Path file = photos.root().resolve(path);
            boolean missing = e.get("photoPath") == null || !Files.exists(file);
            if (!missing) {
                continue;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, svg(id, role), StandardCharsets.UTF_8);
                db.update("UPDATE employee SET photo_path = :p WHERE id = :id", Db.p("id", id, "p", path));
                n++;
            } catch (IOException ex) {
                log.warn("Portrait de démonstration impossible pour l'employé {} : {}", id, ex.getMessage());
            }
        }
        if (n > 0) {
            log.info("Données de démonstration : {} portrait(s) de profil créés", n);
        }
        return n;
    }

    /** Portrait 160 × 160 en SVG. */
    static String svg(long id, String role) {
        int h = (int) ((id * 2654435761L) >>> 8 & 0x7fffffff);
        String skin = SKIN[(int) (id % SKIN.length)];
        String hair = HAIR[h % HAIR.length];
        String bg = BACKGROUND[(int) ((id * 7) % BACKGROUND.length)];
        int style = (h / 7) % 6;
        boolean glasses = (h / 3) % 5 == 0;
        Outfit o = outfit(role);
        StringBuilder s = new StringBuilder(2048);
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 160 160\" width=\"160\" height=\"160\">");
        s.append("<rect width=\"160\" height=\"160\" fill=\"").append(bg).append("\"/>");
        // cheveux longs / tresses derrière la tête
        if (style == 4) {
            s.append("<path d=\"M48 70 C46 40 114 40 112 70 L116 118 C100 124 60 124 44 118 Z\" fill=\"").append(hair).append("\"/>");
        }
        if (style == 1) { // coiffure afro
            s.append("<circle cx=\"80\" cy=\"56\" r=\"38\" fill=\"").append(hair).append("\"/>");
        }
        // épaules et tenue
        s.append("<path d=\"M14 160 C16 120 46 104 80 104 C114 104 144 120 146 160 Z\" fill=\"").append(o.main()).append("\"/>");
        switch (o.kind()) {
            case "COSTUME" -> {
                s.append("<path d=\"M64 104 L80 136 L96 104 Z\" fill=\"#f8fafc\"/>");
                s.append("<path d=\"M76 110 L84 110 L86 140 L80 148 L74 140 Z\" fill=\"").append(o.accent()).append("\"/>");
                s.append("<path d=\"M58 106 L74 150 L66 160 L50 112 Z M102 106 L86 150 L94 160 L110 112 Z\" fill=\"#0f172a\" opacity=\"0.25\"/>");
            }
            case "GILET" -> {
                s.append("<rect x=\"30\" y=\"128\" width=\"100\" height=\"7\" fill=\"").append(o.accent()).append("\"/>");
                s.append("<rect x=\"26\" y=\"144\" width=\"108\" height=\"7\" fill=\"").append(o.accent()).append("\"/>");
                s.append("<path d=\"M68 104 L80 124 L92 104 Z\" fill=\"#1e293b\"/>");
            }
            default -> { // polo à col
                s.append("<path d=\"M64 104 L80 122 L72 128 L58 110 Z M96 104 L80 122 L88 128 L102 110 Z\" fill=\"").append(o.accent()).append("\"/>");
                s.append("<circle cx=\"80\" cy=\"132\" r=\"2.2\" fill=\"").append(o.accent()).append("\"/>");
                s.append("<circle cx=\"80\" cy=\"142\" r=\"2.2\" fill=\"").append(o.accent()).append("\"/>");
            }
        }
        // cou, oreilles, visage
        s.append("<rect x=\"68\" y=\"86\" width=\"24\" height=\"24\" rx=\"9\" fill=\"").append(skin).append("\"/>");
        s.append("<rect x=\"68\" y=\"96\" width=\"24\" height=\"8\" fill=\"#000\" opacity=\"0.12\"/>");
        s.append("<circle cx=\"52\" cy=\"70\" r=\"7\" fill=\"").append(skin).append("\"/>");
        s.append("<circle cx=\"108\" cy=\"70\" r=\"7\" fill=\"").append(skin).append("\"/>");
        s.append("<ellipse cx=\"80\" cy=\"66\" rx=\"28\" ry=\"32\" fill=\"").append(skin).append("\"/>");
        // coiffures
        switch (style) {
            case 0 -> s.append("<path d=\"M52 62 C50 30 110 30 108 62 C104 48 92 42 80 42 C68 42 56 48 52 62 Z\" fill=\"").append(hair).append("\"/>");
            case 1 -> s.append("<path d=\"M52 58 C54 40 106 40 108 58 C100 46 60 46 52 58 Z\" fill=\"").append(hair).append("\"/>");
            case 2 -> { // foulard noué
                String wrap = WRAP[(int) (id % WRAP.length)];
                s.append("<path d=\"M46 60 C44 20 116 20 114 60 C104 44 56 44 46 60 Z\" fill=\"").append(wrap).append("\"/>");
                s.append("<path d=\"M96 26 C112 14 124 26 112 36 Z\" fill=\"").append(wrap).append("\"/>");
                s.append("<path d=\"M50 54 C70 40 92 40 110 54\" stroke=\"#000\" stroke-opacity=\"0.15\" stroke-width=\"3\" fill=\"none\"/>");
            }
            case 3 -> s.append("<path d=\"M53 58 C54 36 106 36 107 58 C100 46 60 46 53 58 Z\" fill=\"").append(hair).append("\" opacity=\"0.55\"/>");
            case 4 -> s.append("<path d=\"M52 64 C48 30 112 30 108 64 C100 44 60 44 52 64 Z\" fill=\"").append(hair).append("\"/>");
            default -> s.append("<path d=\"M52 62 C50 30 110 30 108 62 C98 50 86 40 66 48 C60 50 55 55 52 62 Z\" fill=\"").append(hair).append("\"/>");
        }
        // yeux, sourcils, sourire
        s.append("<circle cx=\"69\" cy=\"68\" r=\"3.4\" fill=\"#1c120c\"/><circle cx=\"91\" cy=\"68\" r=\"3.4\" fill=\"#1c120c\"/>");
        s.append("<path d=\"M62 60 Q69 56 75 59 M85 59 Q91 56 98 60\" stroke=\"#1c120c\" stroke-width=\"2.4\" fill=\"none\" stroke-linecap=\"round\"/>");
        s.append("<path d=\"M70 82 Q80 90 90 82\" stroke=\"#3b1d10\" stroke-width=\"3\" fill=\"none\" stroke-linecap=\"round\"/>");
        if (glasses) {
            s.append("<g stroke=\"#111827\" stroke-width=\"2.2\" fill=\"#ffffff\" fill-opacity=\"0.15\">")
                    .append("<rect x=\"60\" y=\"61\" width=\"17\" height=\"13\" rx=\"5\"/><rect x=\"83\" y=\"61\" width=\"17\" height=\"13\" rx=\"5\"/>")
                    .append("<path d=\"M77 66 L83 66\" fill=\"none\"/></g>");
        }
        s.append("</svg>");
        return s.toString();
    }
}
