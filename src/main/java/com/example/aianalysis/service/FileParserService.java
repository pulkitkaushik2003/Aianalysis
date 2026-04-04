package com.example.aianalysis.service;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FileParserService {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + File.separator + "aianalysis";
    private static final int SAMPLE_SIZE = 400000;
    private static final DateTimeFormatter MONTH_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    static {
        new File(TEMP_DIR).mkdirs();
    }

    // ── Main entry point ─────────────────────────────────────
    public List<Map<String, Object>> parse(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();

        if (fileName == null) {
            throw new IOException("File name is null");
        }

        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        return switch (ext) {
            case "csv"        -> parseCsv(file);
            case "xlsx"       -> parseExcel(file, false);
            case "xls"        -> parseExcel(file, true);
            default           -> throw new IOException("Unsupported file type: " + ext);
        };
    }

    public ParseMetadata parseMetadata(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.contains(".")) {
            throw new IOException("File name is invalid");
        }

        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        String filePath = saveToTemp(file);

        return switch (ext) {
            case "csv" -> parseCsvMetadata(filePath);
            case "xlsx" -> parseExcelMetadata(filePath, false);
            case "xls" -> parseExcelMetadata(filePath, true);
            default -> throw new IOException("Unsupported file type: " + ext);
        };
    }

    public List<Map<String, Object>> getDataChunk(String filePath,
                                                  int page,
                                                  int size,
                                                  List<String> headers) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            return List.of();
        }

        String ext = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int startRow = safePage * safeSize;

        return switch (ext) {
            case "csv" -> parseCsvChunk(filePath, headers, startRow, safeSize);
            case "xlsx" -> parseExcelChunk(filePath, false, headers, startRow, safeSize);
            case "xls" -> parseExcelChunk(filePath, true, headers, startRow, safeSize);
            default -> List.of();
        };
    }

    // ── CSV Parser ───────────────────────────────────────────
    private List<Map<String, Object>> parseCsv(MultipartFile file) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream()))) {

            List<String[]> allRows = reader.readAll();

            if (allRows.isEmpty()) return result;

            // First row = headers
            String[] headers = allRows.get(0);

            // Trim headers
            for (int i = 0; i < headers.length; i++) {
                headers[i] = headers[i].trim();
            }

            // Data rows
            for (int i = 1; i < allRows.size(); i++) {
                String[] row = allRows.get(i);
                Map<String, Object> rowMap = new LinkedHashMap<>();

                for (int j = 0; j < headers.length; j++) {
                    String value = (j < row.length) ? row[j].trim() : null;

                    // Empty string → null
                    rowMap.put(headers[j], (value != null && !value.isEmpty()) ? value : null);
                }

                result.add(rowMap);
            }

        } catch (CsvException e) {
            throw new IOException("CSV parse error: " + e.getMessage());
        }

        return result;
    }

    private ParseMetadata parseCsvMetadata(String filePath) throws IOException {
        List<Map<String, Object>> sample = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        long totalRows = 0;

        try (CSVReader reader = new CSVReader(new FileReader(filePath))) {
            String[] row;
            boolean headerRead = false;

            while ((row = reader.readNext()) != null) {
                if (!headerRead) {
                    headers = Arrays.stream(row).map(String::trim).toList();
                    headerRead = true;
                    continue;
                }

                totalRows++;
                if (sample.size() >= SAMPLE_SIZE) {
                    continue;
                }

                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String value = i < row.length ? row[i].trim() : null;
                    rowMap.put(headers.get(i), (value != null && !value.isEmpty()) ? value : null);
                }
                sample.add(rowMap);
            }
        } catch (CsvValidationException e) {
            throw new IOException("CSV parse error: " + e.getMessage(), e);
        }

        return new ParseMetadata(filePath, headers, sample, totalRows, Files.size(Path.of(filePath)));
    }

    private List<Map<String, Object>> parseCsvChunk(String filePath,
                                                    List<String> headers,
                                                    int startRow,
                                                    int size) throws IOException {
        List<Map<String, Object>> chunk = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new FileReader(filePath))) {
            reader.readNext();

            int skipped = 0;
            while (skipped < startRow && reader.readNext() != null) {
                skipped++;
            }

            String[] row;
            while (chunk.size() < size && (row = reader.readNext()) != null) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String value = i < row.length ? row[i].trim() : null;
                    rowMap.put(headers.get(i), (value != null && !value.isEmpty()) ? value : null);
                }
                chunk.add(rowMap);
            }
        } catch (CsvValidationException e) {
            throw new IOException("CSV chunk read failed: " + e.getMessage(), e);
        }

        return chunk;
    }

    // ── Excel Parser (.xlsx and .xls) ────────────────────────
    private List<Map<String, Object>> parseExcel(MultipartFile file, boolean isOld)
            throws IOException {

        List<Map<String, Object>> result = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = isOld ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {

            // First sheet lena
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            if (sheet == null) return result;

            // First row = headers
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return result;

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValue(cell, null, formatter, evaluator).trim());
            }

            // Data rows
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, Object> rowMap = new LinkedHashMap<>();

                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j);
                    String value = (cell != null)
                            ? getCellValue(cell, headers.get(j), formatter, evaluator).trim()
                            : null;
                    rowMap.put(headers.get(j), (value != null && !value.isEmpty()) ? value : null);
                }

                result.add(rowMap);
            }
        }

        return result;
    }

    private ParseMetadata parseExcelMetadata(String filePath, boolean isOld) throws IOException {
        List<Map<String, Object>> sample = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        long totalRows = 0;

        try (InputStream is = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = isOld ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            if (sheet == null) {
                return new ParseMetadata(filePath, headers, sample, 0, Files.size(Path.of(filePath)));
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    headers.add(getCellValue(cell, null, formatter, evaluator).trim());
                }
            }

            totalRows = Math.max(sheet.getLastRowNum(), 0);
            for (int i = 1; i <= sheet.getLastRowNum() && sample.size() < SAMPLE_SIZE; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j);
                    String value = cell == null ? null : getCellValue(cell, headers.get(j), formatter, evaluator).trim();
                    rowMap.put(headers.get(j), (value != null && !value.isEmpty()) ? value : null);
                }
                sample.add(rowMap);
            }
        }

        return new ParseMetadata(filePath, headers, sample, totalRows, Files.size(Path.of(filePath)));
    }

    private List<Map<String, Object>> parseExcelChunk(String filePath,
                                                      boolean isOld,
                                                      List<String> headers,
                                                      int startRow,
                                                      int size) throws IOException {
        List<Map<String, Object>> chunk = new ArrayList<>();

        try (InputStream is = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = isOld ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            if (sheet == null) {
                return chunk;
            }

            int endRow = Math.min(sheet.getLastRowNum(), startRow + size);
            for (int i = startRow + 1; i <= endRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = row.getCell(j);
                    String value = cell == null ? null : getCellValue(cell, headers.get(j), formatter, evaluator).trim();
                    rowMap.put(headers.get(j), (value != null && !value.isEmpty()) ? value : null);
                }
                chunk.add(rowMap);
            }
        }

        return chunk;
    }

    // ── Cell value extract karna ─────────────────────────────
    private String getCellValue(Cell cell,
                                String header,
                                DataFormatter formatter,
                                FormulaEvaluator evaluator) {
        if (cell == null) return "";

        String lowerHeader = header == null ? "" : header.toLowerCase(Locale.ROOT);

        return switch (cell.getCellType()) {
            case STRING -> normalizeTextValue(cell.getStringCellValue(), lowerHeader);
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield normalizeDateValue(
                            cell.getLocalDateTimeCellValue().toLocalDate(),
                            lowerHeader,
                            formatter.formatCellValue(cell, evaluator)
                    );
                }

                double val = cell.getNumericCellValue();
                yield normalizeNumericValue(val, lowerHeader);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                String formatted = formatter.formatCellValue(cell, evaluator);
                if (formatted != null && !formatted.isBlank()) {
                    yield normalizeTextValue(formatted, lowerHeader);
                }
                yield cell.getCellFormula();
            }
            case BLANK -> "";
            default -> "";
        };
    }

    private String normalizeTextValue(String value, String lowerHeader) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        if (isMonthLikeHeader(lowerHeader) && trimmed.matches("\\d{1,2}")) {
            int monthNumber = Integer.parseInt(trimmed);
            if (monthNumber >= 1 && monthNumber <= 12) {
                return monthLabel(monthNumber);
            }
        }

        if (isQuarterLikeHeader(lowerHeader) && trimmed.matches("\\d")) {
            int quarter = Integer.parseInt(trimmed);
            if (quarter >= 1 && quarter <= 4) {
                return "Q" + quarter;
            }
        }

        return trimmed;
    }

    private String normalizeDateValue(LocalDate date, String lowerHeader, String displayValue) {
        if (date == null) {
            return displayValue == null ? "" : displayValue.trim();
        }

        if (isMonthLikeHeader(lowerHeader)) {
            return date.format(MONTH_LABEL_FORMAT).toUpperCase(Locale.ROOT);
        }
        if (isYearLikeHeader(lowerHeader)) {
            return String.valueOf(date.getYear());
        }
        if (isQuarterLikeHeader(lowerHeader)) {
            int quarter = ((date.getMonthValue() - 1) / 3) + 1;
            return "Q" + quarter + "-" + date.getYear();
        }

        String formatted = displayValue == null ? "" : displayValue.trim();
        return formatted.isEmpty() ? date.toString() : formatted;
    }

    private String normalizeNumericValue(double value, String lowerHeader) {
        if (isMonthLikeHeader(lowerHeader) && value == Math.floor(value)) {
            int monthNumber = (int) value;
            if (monthNumber >= 1 && monthNumber <= 12) {
                return monthLabel(monthNumber);
            }
        }

        if (isQuarterLikeHeader(lowerHeader) && value == Math.floor(value)) {
            int quarter = (int) value;
            if (quarter >= 1 && quarter <= 4) {
                return "Q" + quarter;
            }
        }

        if (isYearLikeHeader(lowerHeader) && value == Math.floor(value)) {
            int year = (int) value;
            if (year >= 1900 && year <= 2100) {
                return String.valueOf(year);
            }
        }

        return (value == Math.floor(value))
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private boolean isMonthLikeHeader(String lowerHeader) {
        return lowerHeader.contains("month") || lowerHeader.contains("mnth");
    }

    private boolean isQuarterLikeHeader(String lowerHeader) {
        return lowerHeader.contains("quarter") || lowerHeader.contains("qtr");
    }

    private boolean isYearLikeHeader(String lowerHeader) {
        return lowerHeader.contains("year") || lowerHeader.endsWith("yr");
    }

    private String monthLabel(int monthNumber) {
        return LocalDate.of(2000, monthNumber, 1)
                .format(MONTH_LABEL_FORMAT)
                .toUpperCase(Locale.ROOT);
    }

    // ── Headers extract karna (dashboard ke liye) ────────────
    public List<String> extractHeaders(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return new ArrayList<>();

        return data.get(0).entrySet().stream()
                .map(Map.Entry::getKey)
                .filter(key -> !key.startsWith("_")) // internal keys skip
                .toList();
    }

    private String saveToTemp(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "data.csv" : file.getOriginalFilename();
        String safeName = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path tempPath = Paths.get(TEMP_DIR, safeName);
        file.transferTo(tempPath.toFile());
        return tempPath.toString();
    }

    public record ParseMetadata(String filePath,
                                List<String> headers,
                                List<Map<String, Object>> sampleData,
                                long totalRows,
                                long fileSize) {}
}

