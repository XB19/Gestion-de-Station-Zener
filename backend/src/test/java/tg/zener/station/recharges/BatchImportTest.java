package tg.zener.station.recharges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tg.zener.station.common.ApiException;

class BatchImportTest {

    static List<BatchImport.Line> csv(String content) {
        return BatchImport.read("recharges.csv", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void csvPointVirguleAvecEnTete() {
        List<BatchImport.Line> lines = csv("numero;montant;libelle\n6011 2847 0093;150000;Camion 12\n601144170028;80 000;\n");
        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).lineNo());
        assertEquals("6011 2847 0093", lines.get(0).cardNumber());
        assertEquals("150000", lines.get(0).amount());
        assertEquals("Camion 12", lines.get(0).label());
        assertEquals("80 000", lines.get(1).amount());
        assertEquals("", lines.get(1).label());
    }

    @Test
    void csvVirguleSansEnTeteAvecBomEtLignesVides() {
        List<BatchImport.Line> lines = csv("﻿601128470093,25000\n\n601133027782,1000\n");
        assertEquals(2, lines.size());
        assertEquals("601128470093", lines.get(0).cardNumber());
        assertNull(lines.get(0).label());
        assertEquals(3, lines.get(1).lineNo());
    }

    @Test
    void excelAvecNumeroDeCarteNumerique() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet();
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("Carte");
            h.createCell(1).setCellValue("Montant");
            Row r = sh.createRow(1);
            r.createCell(0).setCellValue(601128470093d); // saisi comme nombre
            r.createCell(1).setCellValue(45000);
            r.createCell(2).setCellValue("Navette 3");
            wb.write(out);
        }
        List<BatchImport.Line> lines = BatchImport.read("lot.xlsx", new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1, lines.size());
        assertEquals("601128470093", lines.get(0).cardNumber());
        assertEquals("45000", lines.get(0).amount());
        assertEquals("Navette 3", lines.get(0).label());
        assertEquals(2, lines.get(0).lineNo());
    }

    @Test
    void auPlusCinqMilleLignes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            sb.append("601128470093;1000\n");
        }
        assertThrows(ApiException.class, () -> csv(sb.toString()));
    }

    @Test
    void fichierIllisible() {
        assertThrows(ApiException.class,
                () -> BatchImport.read("lot.xlsx", new ByteArrayInputStream("pas un classeur".getBytes(StandardCharsets.UTF_8))));
    }
}
