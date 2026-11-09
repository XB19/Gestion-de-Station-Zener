package tg.zener.station.employees;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tg.zener.station.common.ApiException;

/**
 * Photos des employés, enregistrées dans app.upload-dir/photos sous un nom aléatoire
 * et servies sur /files/photos/… (voir WebConfig).
 */
@Component
public class PhotoStore {

    private static final Logger log = LoggerFactory.getLogger(PhotoStore.class);
    private static final Map<String, String> TYPES = Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Path root;

    public PhotoStore(@Value("${app.upload-dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** Enregistre l'image et renvoie son chemin relatif (photos/xxx.jpg). */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("Aucun fichier reçu");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException("Photo trop volumineuse (5 Mo maximum)");
        }
        String ext = TYPES.get(String.valueOf(file.getContentType()).toLowerCase());
        if (ext == null) {
            throw new ApiException("Format accepté : JPEG, PNG ou WebP");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (!looksLikeImage(head)) {
                throw new ApiException("Le fichier n'est pas une image valide");
            }
            Path dir = root.resolve("photos");
            Files.createDirectories(dir);
            String name = UUID.randomUUID().toString().replace("-", "") + ext;
            try (InputStream all = file.getInputStream()) {
                Files.copy(all, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            return "photos/" + name;
        } catch (IOException e) {
            throw new ApiException("Enregistrement de la photo impossible : " + e.getMessage());
        }
    }

    public void delete(String relative) {
        if (relative == null || relative.isBlank()) {
            return;
        }
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Suppression de {} impossible : {}", p, e.getMessage());
        }
    }

    public Path root() {
        return root;
    }

    /** Signatures JPEG, PNG et WebP. */
    static boolean looksLikeImage(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return true;
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return true;
        }
        return h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }
}
