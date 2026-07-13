package com.example.aianalysis.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DataCleaningService {

    private static final int DEFAULT_STREAM_CHUNK_SIZE = 5000;
    private static final int STREAM_SAMPLE_LIMIT = 500;
    private static final int NUMERIC_PROFILE_SAMPLE_LIMIT = 10_000;
    private static final int EXCEL_MAX_ROWS_PER_SHEET = 1_048_576;
    private static final double OUTLIER_ABSOLUTE_LIMIT = 100_000D;

    private final FileParserService fileParserService;

    public DataCleaningService(FileParserService fileParserService) {
        this.fileParserService = fileParserService;
    }

    public CleaningResult analyze(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return new CleaningResult(data == null ? List.of() : data, 0, 0, 0, 0, 0, 0);
        }

        List<String> headers = normalizeHeaders(data.get(0).keySet().stream().toList());
        StreamingAnalysisState state = new StreamingAnalysisState(headers, 0);
        for (Map<String, Object> row : data) {
            String issue = state.accept(row);
            row.put("_issue", issue);
        }

        return new CleaningResult(
                data,
                safeInt(state.totalRows),
                safeInt(state.cleanRows),
                safeInt(state.issueCount),
                safeInt(state.missingCount),
                safeInt(state.duplicateCount),
                safeInt(state.outlierCount)
        );
    }

    public CleaningResult streamingAnalyze(String filePath,
                                           List<String> headers) throws IOException {
        return streamingAnalyze(filePath, headers, DEFAULT_STREAM_CHUNK_SIZE);
    }

    public CleaningResult streamingAnalyze(String filePath,
                                           List<String> headers,
                                           int chunkSize) throws IOException {
        List<String> safeHeaders = normalizeHeaders(headers);
        if (filePath == null || filePath.isBlank() || safeHeaders.isEmpty()) {
            return new CleaningResult(List.of(), 0, 0, 0, 0, 0, 0);
        }

        StreamingAnalysisState state = new StreamingAnalysisState(safeHeaders, STREAM_SAMPLE_LIMIT);
        forEachDataRow(filePath, safeHeaders, chunkSize, state::accept);
        return state.toCleaningResult();
    }

    public StreamingCleanResult applyUserFixesToExcel(
            String filePath,
            List<String> headers,
            Path outputPath,
            Map<String, Boolean> enabledCards,
            Map<String, String> selectedOpts,
            Map<String, String> perColumnMissingOpts,
            int chunkSize
    ) throws IOException {
        List<String> safeHeaders = normalizeHeaders(headers);
        if (filePath == null || filePath.isBlank() || safeHeaders.isEmpty()) {
            writeEmptyWorkbook(outputPath, safeHeaders);
            return new StreamingCleanResult(
                    outputPath,
                    new CleaningResult(List.of(), 0, 0, 0, 0, 0, 0)
            );
        }

        StreamingFixContext context = buildStreamingFixContext(filePath, safeHeaders, chunkSize);
        StreamingAnalysisState cleanedAnalysis = new StreamingAnalysisState(safeHeaders, STREAM_SAMPLE_LIMIT);
        Set<String> sourceSeenHashes = new HashSet<>();
        Set<String> duplicateHashes = new HashSet<>();

        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            ExcelRowWriter writer = new ExcelRowWriter(workbook, safeHeaders);

            forEachDataRow(filePath, safeHeaders, chunkSize, sourceRow -> {
                String issue = detectIssue(sourceRow, safeHeaders, sourceSeenHashes);
                Map<String, Object> fixedRow = applyFixesToRow(
                        sourceRow,
                        safeHeaders,
                        issue,
                        context,
                        enabledCards,
                        selectedOpts,
                        perColumnMissingOpts,
                        duplicateHashes
                );

                if (fixedRow == null) {
                    return;
                }

                cleanedAnalysis.accept(fixedRow);
                writer.write(fixedRow);
            });

            workbook.write(out);
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
            }
        }

        return new StreamingCleanResult(outputPath, cleanedAnalysis.toCleaningResult());
    }

    public List<Map<String, Object>> cleanData(List<Map<String, Object>> data) {
        List<Map<String, Object>> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (data == null) {
            return cleaned;
        }

        for (Map<String, Object> row : data) {
            Map<String, Object> newRow = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("_")) {
                    continue;
                }

                String val = entry.getValue() == null ? "" : entry.getValue().toString().trim();
                if (val.isEmpty() || val.equalsIgnoreCase("N/A")) {
                    val = "Unknown";
                }

                val = val.trim();
                val = val.replaceAll("[^\\w\\s@.-]", "");

                if (val.matches("\\d{2}/\\d{2}/\\d{4}")) {
                    String[] p = val.split("/");
                    val = p[2] + "-" + p[1] + "-" + p[0];
                }

                try {
                    val = val.replaceAll(",", "");
                    if (val.matches("\\d+")) {
                        val = String.valueOf(Integer.parseInt(val));
                    }
                } catch (Exception ignored) {
                }

                newRow.put(key, val);
            }

            if (!seen.add(newRow.toString())) {
                continue;
            }

            boolean isOutlier = false;
            for (Object value : newRow.values()) {
                Double num = parseNumber(value);
                if (num != null && num > 1_000_000D) {
                    isOutlier = true;
                    break;
                }
            }

            if (!isOutlier) {
                cleaned.add(newRow);
            }
        }

        return cleaned;
    }

    public List<Map<String, Object>> applyUserFixes(
            List<Map<String, Object>> data,
            Map<String, Boolean> enabledCards,
            Map<String, String> selectedOpts,
            Map<String, String> perColumnMissingOpts
    ) {
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> headers = normalizeHeaders(data.get(0).keySet().stream().toList());
        StreamingFixContext context = new StreamingFixContext(
                computeAverages(data, headers),
                computeBounds(data, headers)
        );

        List<Map<String, Object>> fixedRows = new ArrayList<>();
        Set<String> duplicateHashes = new HashSet<>();

        for (Map<String, Object> sourceRow : data) {
            String issue = sourceRow.get("_issue") == null
                    ? "clean"
                    : sourceRow.get("_issue").toString();

            Map<String, Object> fixedRow = applyFixesToRow(
                    sourceRow,
                    headers,
                    issue,
                    context,
                    enabledCards,
                    selectedOpts,
                    perColumnMissingOpts,
                    duplicateHashes
            );

            if (fixedRow != null) {
                fixedRows.add(fixedRow);
            }
        }

        return fixedRows;
    }

    private StreamingFixContext buildStreamingFixContext(String filePath,
                                                         List<String> headers,
                                                         int chunkSize) throws IOException {
        Map<String, NumericProfile> profiles = new LinkedHashMap<>();
        for (String header : headers) {
            profiles.put(header, new NumericProfile(NUMERIC_PROFILE_SAMPLE_LIMIT));
        }

        forEachDataRow(filePath, headers, chunkSize, row -> {
            for (String header : headers) {
                Double value = parseNumber(row.get(header));
                if (value != null) {
                    profiles.get(header).accept(value);
                }
            }
        });

        Map<String, Double> averages = new LinkedHashMap<>();
        Map<String, Bounds> bounds = new LinkedHashMap<>();
        for (Map.Entry<String, NumericProfile> entry : profiles.entrySet()) {
            NumericProfile profile = entry.getValue();
            if (profile.count > 0) {
                averages.put(entry.getKey(), profile.sum / profile.count);
            }
            Bounds columnBounds = profile.bounds();
            if (columnBounds != null) {
                bounds.put(entry.getKey(), columnBounds);
            }
        }

        return new StreamingFixContext(averages, bounds);
    }

    private Map<String, Object> applyFixesToRow(
            Map<String, Object> sourceRow,
            List<String> headers,
            String issue,
            StreamingFixContext context,
            Map<String, Boolean> enabledCards,
            Map<String, String> selectedOpts,
            Map<String, String> perColumnMissingOpts,
            Set<String> duplicateHashes
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String header : headers) {
            row.put(header, sourceRow.get(header));
        }

        if (isEnabled(enabledCards, "missing") && "missing".equals(issue)) {
            String missingOpt = option(selectedOpts, "missing", "avg");
            if ("delete".equals(missingOpt)) {
                return null;
            }

            for (String header : headers) {
                Object value = row.get(header);
                if (isBlank(value)) {
                    String columnOpt = columnOption(perColumnMissingOpts, header, missingOpt);
                    if ("zero".equals(columnOpt)) {
                        row.put(header, "0");
                    } else if ("unknown".equals(columnOpt)) {
                        row.put(header, "Unknown");
                    } else {
                        Double avg = context.averages.get(header);
                        row.put(header, avg == null ? "Unknown" : formatDecimal(avg));
                    }
                }
            }
        }

        if (isEnabled(enabledCards, "spaces")) {
            for (String header : headers) {
                Object value = row.get(header);
                if (value != null) {
                    row.put(header, value.toString().trim());
                }
            }
        }

        if (isEnabled(enabledCards, "wrongtype")
                && "convert".equals(option(selectedOpts, "wrongtype", "convert"))) {
            for (String header : headers) {
                Object value = row.get(header);
                if (value == null) {
                    continue;
                }

                String str = value.toString().trim().replaceAll("[\\u20B9,]", "");
                if (!str.isEmpty() && str.matches("-?\\d+(\\.\\d+)?")) {
                    row.put(header, stripTrailingZeros(str));
                }
            }
        }

        if (isEnabled(enabledCards, "outlier") && "outlier".equals(issue)) {
            String outlierOpt = option(selectedOpts, "outlier", "highlight");
            if ("remove".equals(outlierOpt)) {
                return null;
            }

            if ("cap".equals(outlierOpt)) {
                for (String header : headers) {
                    Double value = parseNumber(row.get(header));
                    Bounds limit = context.bounds.get(header);
                    if (value == null || limit == null) {
                        continue;
                    }

                    if (value < limit.lower) {
                        row.put(header, formatDecimal(limit.lower));
                    } else if (value > limit.upper) {
                        row.put(header, formatDecimal(limit.upper));
                    }
                }
            }
        }

        String hash = rowHash(row, headers);
        if (isEnabled(enabledCards, "duplicate")
                && "remove".equals(option(selectedOpts, "duplicate", "remove"))) {
            if (!duplicateHashes.add(hash)) {
                return null;
            }
        }

        return row;
    }

    private void forEachDataRow(String filePath,
                                List<String> headers,
                                int chunkSize,
                                Consumer<Map<String, Object>> consumer) throws IOException {
        List<String> safeHeaders = normalizeHeaders(headers);
        fileParserService.streamFile(filePath, row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (String header : safeHeaders) {
                normalized.put(header, row.get(header));
            }
            consumer.accept(normalized);
        });
    }

    private void writeEmptyWorkbook(Path outputPath, List<String> headers) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            ExcelRowWriter writer = new ExcelRowWriter(workbook, normalizeHeaders(headers));
            writer.ensureSheet();
            workbook.write(out);
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, Double> computeAverages(List<Map<String, Object>> data, List<String> headers) {
        Map<String, Double> averages = new LinkedHashMap<>();

        for (String header : headers) {
            List<Double> nums = data.stream()
                    .map(row -> parseNumber(row.get(header)))
                    .filter(Objects::nonNull)
                    .toList();

            if (!nums.isEmpty()) {
                double total = nums.stream().mapToDouble(Double::doubleValue).sum();
                averages.put(header, total / nums.size());
            }
        }

        return averages;
    }

    private Map<String, Bounds> computeBounds(List<Map<String, Object>> data, List<String> headers) {
        Map<String, Bounds> bounds = new LinkedHashMap<>();

        for (String header : headers) {
            List<Double> nums = data.stream()
                    .map(row -> parseNumber(row.get(header)))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();

            Bounds columnBounds = boundsFromSorted(nums);
            if (columnBounds != null) {
                bounds.put(header, columnBounds);
            }
        }

        return bounds;
    }

    private String detectIssue(Map<String, Object> row,
                               List<String> headers,
                               Set<String> seenHashes) {
        boolean isMissing = hasMissing(row, headers);
        String hash = rowHash(row, headers);
        boolean isDuplicate = !seenHashes.add(hash);
        boolean isOutlier = hasOutlier(row, headers);

        if (isMissing) {
            return "missing";
        }
        if (isDuplicate) {
            return "duplicate";
        }
        if (isOutlier) {
            return "outlier";
        }
        return "clean";
    }

    private boolean hasMissing(Map<String, Object> row, List<String> headers) {
        for (String header : headers) {
            if (isBlank(row.get(header))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOutlier(Map<String, Object> row, List<String> headers) {
        for (String header : headers) {
            Double num = parseNumber(row.get(header));
            if (num != null && num > OUTLIER_ABSOLUTE_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private String rowHash(Map<String, Object> row, List<String> headers) {
        return headers.stream()
                .map(header -> header + "=" + String.valueOf(row.get(header)))
                .collect(Collectors.joining("|"));
    }

    private List<String> normalizeHeaders(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String header : headers) {
            if (header != null && !header.startsWith("_")) {
                normalized.add(header);
            }
        }
        return new ArrayList<>(normalized);
    }

    private boolean isEnabled(Map<String, Boolean> enabledCards, String key) {
        return enabledCards == null
                || !enabledCards.containsKey(key)
                || Boolean.TRUE.equals(enabledCards.get(key));
    }

    private String option(Map<String, String> selectedOpts, String key, String fallback) {
        if (selectedOpts == null) {
            return fallback;
        }
        return selectedOpts.getOrDefault(key, fallback);
    }

    private String columnOption(Map<String, String> columnOpts, String key, String fallback) {
        if (columnOpts == null) {
            return fallback;
        }
        return columnOpts.getOrDefault(key, fallback);
    }

    private boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private Double parseNumber(Object value) {
        if (value == null) {
            return null;
        }

        try {
            String str = value.toString().trim().replaceAll("[\\u20B9,\\s]", "");
            if (str.isEmpty()) {
                return null;
            }
            return Double.parseDouble(str);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripTrailingZeros(String value) {
        try {
            double num = Double.parseDouble(value);
            return formatDecimal(num);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String formatDecimal(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private Bounds boundsFromSorted(List<Double> sortedValues) {
        if (sortedValues == null || sortedValues.size() < 4) {
            return null;
        }

        int q1Index = (int) Math.floor(sortedValues.size() * 0.25);
        int q3Index = (int) Math.floor(sortedValues.size() * 0.75);
        double q1 = sortedValues.get(q1Index);
        double q3 = sortedValues.get(q3Index);
        double iqr = q3 - q1;
        return new Bounds(q1 - 1.5 * iqr, q3 + 1.5 * iqr);
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static class StreamingFixContext {
        private final Map<String, Double> averages;
        private final Map<String, Bounds> bounds;

        private StreamingFixContext(Map<String, Double> averages, Map<String, Bounds> bounds) {
            this.averages = averages == null ? Map.of() : averages;
            this.bounds = bounds == null ? Map.of() : bounds;
        }
    }

    private class StreamingAnalysisState {
        private final List<String> headers;
        private final int sampleLimit;
        private final List<Map<String, Object>> sampleRows = new ArrayList<>();
        private final Set<String> seenHashes = new HashSet<>();

        private long totalRows;
        private long cleanRows;
        private long issueCount;
        private long missingCount;
        private long duplicateCount;
        private long outlierCount;

        private StreamingAnalysisState(List<String> headers, int sampleLimit) {
            this.headers = headers;
            this.sampleLimit = sampleLimit;
        }

        private String accept(Map<String, Object> row) {
            totalRows++;
            String issue = detectIssue(row, headers, seenHashes);

            switch (issue) {
                case "missing" -> {
                    missingCount++;
                    issueCount++;
                }
                case "duplicate" -> {
                    duplicateCount++;
                    issueCount++;
                }
                case "outlier" -> {
                    outlierCount++;
                    issueCount++;
                }
                default -> cleanRows++;
            }

            if (sampleRows.size() < sampleLimit) {
                Map<String, Object> sample = new LinkedHashMap<>();
                for (String header : headers) {
                    sample.put(header, row.get(header));
                }
                sample.put("_issue", issue);
                sampleRows.add(sample);
            }

            return issue;
        }

        private CleaningResult toCleaningResult() {
            return new CleaningResult(
                    sampleRows,
                    safeInt(totalRows),
                    safeInt(cleanRows),
                    safeInt(issueCount),
                    safeInt(missingCount),
                    safeInt(duplicateCount),
                    safeInt(outlierCount)
            );
        }
    }

    private class ExcelRowWriter {
        private final SXSSFWorkbook workbook;
        private final List<String> headers;
        private Sheet sheet;
        private int sheetIndex;
        private int rowIndex = EXCEL_MAX_ROWS_PER_SHEET;

        private ExcelRowWriter(SXSSFWorkbook workbook, List<String> headers) {
            this.workbook = workbook;
            this.headers = headers;
        }

        private void write(Map<String, Object> dataRow) {
            ensureSheet();
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                Object value = dataRow.get(headers.get(i));
                row.createCell(i).setCellValue(value == null ? "" : value.toString());
            }
        }

        private void ensureSheet() {
            if (sheet != null && rowIndex < EXCEL_MAX_ROWS_PER_SHEET) {
                return;
            }

            String sheetName = sheetIndex == 0 ? "Clean Data" : "Clean Data " + (sheetIndex + 1);
            sheet = workbook.createSheet(sheetName);
            sheetIndex++;
            rowIndex = 0;

            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
        }
    }

    private class NumericProfile {
        private final int sampleLimit;
        private final Random random = new Random(42L);
        private final List<Double> sample = new ArrayList<>();
        private long count;
        private double sum;

        private NumericProfile(int sampleLimit) {
            this.sampleLimit = sampleLimit;
        }

        private void accept(double value) {
            count++;
            sum += value;

            if (sample.size() < sampleLimit) {
                sample.add(value);
                return;
            }

            long slot = Math.floorMod(random.nextLong(), count);
            if (slot < sampleLimit) {
                sample.set((int) slot, value);
            }
        }

        private Bounds bounds() {
            if (sample.size() < 4) {
                return null;
            }

            List<Double> sorted = new ArrayList<>(sample);
            sorted.sort(Double::compareTo);
            return boundsFromSorted(sorted);
        }
    }

    private static class Bounds {
        private final double lower;
        private final double upper;

        private Bounds(double lower, double upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    public static class StreamingCleanResult {
        public final Path filePath;
        public final CleaningResult analysis;

        public StreamingCleanResult(Path filePath, CleaningResult analysis) {
            this.filePath = filePath;
            this.analysis = analysis;
        }
    }

    public static class CleaningResult {
        public final List<Map<String, Object>> data;
        public final int totalRows;
        public final int cleanRows;
        public final int issueCount;
        public final int missingCount;
        public final int duplicateCount;
        public final int outlierCount;

        public CleaningResult(List<Map<String, Object>> data,
                              int totalRows,
                              int cleanRows,
                              int issueCount,
                              int missingCount,
                              int duplicateCount,
                              int outlierCount) {
            this.data = data;
            this.totalRows = totalRows;
            this.cleanRows = cleanRows;
            this.issueCount = issueCount;
            this.missingCount = missingCount;
            this.duplicateCount = duplicateCount;
            this.outlierCount = outlierCount;
        }
    }
}
