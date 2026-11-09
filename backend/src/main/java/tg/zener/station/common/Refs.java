package tg.zener.station.common;

import java.time.Year;
import org.springframework.stereotype.Component;

/** Références lisibles : VAL-2026-1184, REC-2026-1001, ACH-2026-1001, LIV-2026-1001, PDV-101. */
@Component
public class Refs {

    private final Db db;

    public Refs(Db db) {
        this.db = db;
    }

    private long next(String sequence) {
        return db.sum("SELECT nextval('" + sequence + "')", java.util.Map.of());
    }

    public String validation() {
        return "VAL-" + Year.now() + "-" + next("ref_validation_seq");
    }

    public String recharge() {
        return "REC-" + Year.now() + "-" + next("ref_recharge_seq");
    }

    public String purchase() {
        return "ACH-" + Year.now() + "-" + next("ref_purchase_seq");
    }

    public String delivery() {
        return "LIV-" + Year.now() + "-" + next("ref_delivery_seq");
    }

    public String pdv() {
        return "PDV-" + next("ref_pdv_seq");
    }
}
