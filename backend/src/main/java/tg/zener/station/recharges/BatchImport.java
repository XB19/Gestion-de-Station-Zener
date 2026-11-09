package tg.zener.station.recharges;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import tg.zener.station.common.ApiException;

/**
 * Lecture d'un fichier de recharges : colonnes « numéro de carte ; montant ; libellé (facultatif) ».
 * Formats : .csv (séparateur ; ou ,) et .xlsx. Une première ligne d'en-tête est ignorée.
 */
final class BatchImport {

    record Line(int lineNo, String cardNumber, String amount, String label) {}

    private BatchImport() {}

    static List<Line> read(String fileName, InputStream in) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readExcel(in);
            }
            return readCsv(in);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Fichier illisible : " + e.getMessage());
        }
    }

    private static List<Line> readCsv(InputStream in) throws Exception {
        List<Line> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String row;
            int n = 0;
            while ((row = r.readLine()) != null) {
                n++;
                if (n == 1 && row.startsWith("﻿")) {
                    row = row.substring(1);
                }
                if (row.isBlank()) {
                    continue;
                }
                String[] cols = row.split(row.contains(";") ? ";" : ",", -1);
                add(lines, n, cols.length > 0 ? cols[0] : "", cols.length > 1 ? cols[1] : "", cols.length > 2 ? cols[2] : null);
            }
        }
        return lines;
    }

    private static List<Line> readExcel(InputStream in) throws Exception {
        List<Line> lines = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                String card = cell(row.getCell(0), fmt);
                String amount = cell(row.getCell(1), fmt);
                String label = cell(row.getCell(2), fmt);
                if (card.isBlank() && amount.isBlank()) {
                    continue;
                }
                add(lines, row.getRowNum() + 1, card, amount, label.isBlank() ? null : label);
            }
        }
        return lines;
    }

    private static String cell(Cell c, DataFormatter fmt) {
        if (c == null) {
            return "";
        }
        if (c.getCellType() == CellType.NUMERIC) {
            // numéro de carte saisi comme nombre : pas de notation scientifique
            return new java.math.BigDecimal(c.getNumericCellValue()).toPlainString();
        }
        return fmt.formatCellValue(c).trim();
    }

    private static void add(List<Line> lines, int n, String card, String amount, String label) {
        // ligne d'en-tête : pas de chiffre dans la colonne montant
        if (lines.isEmpty() && n == 1 && !amount.matches(".*[0-9].*")) {
            return;
        }
        if (lines.size() >= 5000) {
            throw new ApiException("5 000 lignes au maximum par fichier");
        }
        lines.add(new Line(n, card.trim(), amount.trim(), label == null ? null : label.trim()));
    }
}
