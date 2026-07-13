package com.example.aianalysis.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

@Service
public class DatasetAnalysisContextService {

    private static final int CONTEXT_CHUNK_SIZE = 2000;
    private static final int MAX_NUMERIC_COLUMNS = 12;
    private static final int MAX_MISSING_COLUMNS = 8;
    private static final int MAX_TOP_ROWS = 5;

    private final FileParserService fileParserService;

    public DatasetAnalysisContextService(FileParserService fileParserService) {
        this.fileParserService = fileParserService;
    }

    public String buildFromData(List<Map<String, Object>> data, List<String> headers) {
        if (data == null || data.isEmpty() || headers == null || headers.isEmpty()) {
            return "Full dataset profile unavailable.";
        }

        DatasetProfile profile = new DatasetProfile(headers, detectNumericHeaders(headers, data));
        long rowNumber = 0;
        for (Map<String, Object> row : data) {
            rowNumber++;
            profile.accept(row, rowNumber);
        }
        return profile.format();
    }

    public String buildFromFile(String filePath,
                                List<String> headers,
                                List<Map<String, Object>> previewRows) throws IOException {
        if (filePath == null || filePath.isBlank() || headers == null || headers.isEmpty()) {
            return "Full dataset profile unavailable.";
        }

        DatasetProfile profile = new DatasetProfile(headers, detectNumericHeaders(headers, previewRows));
        long rowNumber = 0;
        int page = 0;

        while (true) {
            List<Map<String, Object>> chunk = fileParserService.getDataChunk(
                    filePath, page, CONTEXT_CHUNK_SIZE, headers
            );
            if (chunk == null || chunk.isEmpty()) {
                break;
            }

            for (Map<String, Object> row : chunk) {
                rowNumber++;
                profile.accept(row, rowNumber);
            }

            if (chunk.size() < CONTEXT_CHUNK_SIZE) {
                break;
            }
            page++;
        }

        return profile.format();
    }

    private List<String> detectNumericHeaders(List<String> headers, List<Map<String, Object>> rows) {
        if (headers == null || headers.isEmpty() || rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<String> numericHeaders = new ArrayList<>();
        for (String header : headers) {
            long nonBlankCount = 0;
            long numericCount = 0;

            for (Map<String, Object> row : rows) {
                Object value = row.get(header);
                if (isBlank(value)) {
                    continue;
                }

                nonBlankCount++;
                if (parseNumericValue(value) != null) {
                    numericCount++;
                }
            }

            if (nonBlankCount > 0 && numericCount >= Math.max(3, (long) Math.ceil(nonBlankCount * 0.6))) {
                numericHeaders.add(header);
            }

            if (numericHeaders.size() >= MAX_NUMERIC_COLUMNS) {
                break;
            }
        }

        return numericHeaders;
    }

    private Double parseNumericValue(Object value) {
        if (value == null) {
            return null;
        }

        try {
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                return Double.isFinite(numeric) ? numeric : null;
            }

            String normalized = String.valueOf(value)
                    .trim()
                    .replace(",", "")
                    .replace("₹", "")
                    .replace("Rs.", "")
                    .replace("RS.", "")
                    .replace("rs.", "")
                    .replace("rs", "")
                    .trim();

            if (normalized.isEmpty() || !normalized.matches("-?\\d+(\\.\\d+)?")) {
                return null;
            }

            double parsed = Double.parseDouble(normalized);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "N/A";
        }
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private final class DatasetProfile {
        private final List<String> headers;
        private final List<String> numericHeaders;
        private final String labelColumn;
        private final String primaryNumericColumn;
        private final Map<String, Long> missingCounts = new LinkedHashMap<>();
        private final Map<String, NumericSummary> numericSummaries = new LinkedHashMap<>();
        private final PriorityQueue<TopValueRow> topRows;
        private long processedRows;

        private DatasetProfile(List<String> headers, List<String> numericHeaders) {
            this.headers = headers;
            this.numericHeaders = numericHeaders == null ? List.of() : numericHeaders;
            this.labelColumn = resolveLabelColumn(headers, this.numericHeaders);
            this.primaryNumericColumn = this.numericHeaders.isEmpty() ? null : this.numericHeaders.get(0);
            for (String header : headers) {
                missingCounts.put(header, 0L);
            }
            for (String header : this.numericHeaders) {
                numericSummaries.put(header, new NumericSummary());
            }
            this.topRows = new PriorityQueue<>(
                    Comparator.comparingDouble(TopValueRow::value)
                            .thenComparingLong(TopValueRow::rowNumber)
            );
        }

        private void accept(Map<String, Object> row, long rowNumber) {
            if (row == null) {
                return;
            }

            processedRows = rowNumber;

            for (String header : headers) {
                Object value = row.get(header);
                if (isBlank(value)) {
                    missingCounts.put(header, missingCounts.getOrDefault(header, 0L) + 1L);
                }
            }

            for (String header : numericHeaders) {
                Double numericValue = parseNumericValue(row.get(header));
                if (numericValue == null) {
                    continue;
                }

                numericSummaries.get(header).add(numericValue);
                if (header.equals(primaryNumericColumn)) {
                    pushTopRow(rowNumber, row.get(labelColumn), numericValue);
                }
            }
        }

        private void pushTopRow(long rowNumber, Object labelValue, double value) {
            String label = isBlank(labelValue) ? "Row " + rowNumber : String.valueOf(labelValue).trim();
            TopValueRow candidate = new TopValueRow(rowNumber, label, value);
            if (topRows.size() < MAX_TOP_ROWS) {
                topRows.offer(candidate);
                return;
            }

            TopValueRow smallest = topRows.peek();
            if (smallest != null && value > smallest.value()) {
                topRows.poll();
                topRows.offer(candidate);
            }
        }

        private String format() {
            StringBuilder builder = new StringBuilder();
            builder.append("Full dataset profile:\n");
            builder.append("- Processed rows: ").append(processedRows).append('\n');

            if (numericHeaders.isEmpty()) {
                builder.append("- Numeric columns: not confidently detected\n");
            } else {
                builder.append("- Numeric column stats:\n");
                for (String header : numericHeaders) {
                    NumericSummary summary = numericSummaries.get(header);
                    if (summary == null || summary.count == 0) {
                        continue;
                    }

                    builder.append("  - ").append(header)
                            .append(": count=").append(summary.count)
                            .append(", total=").append(formatNumber(summary.sum))
                            .append(", avg=").append(formatNumber(summary.average()))
                            .append(", min=").append(formatNumber(summary.min))
                            .append(", max=").append(formatNumber(summary.max))
                            .append('\n');
                }
            }

            List<Map.Entry<String, Long>> missingSummary = missingCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_MISSING_COLUMNS)
                    .toList();

            if (!missingSummary.isEmpty()) {
                builder.append("- Missing values:\n");
                for (Map.Entry<String, Long> entry : missingSummary) {
                    builder.append("  - ").append(entry.getKey())
                            .append(": ").append(entry.getValue())
                            .append('\n');
                }
            }

            if (primaryNumericColumn != null && !topRows.isEmpty()) {
                builder.append("- Highest rows by ").append(primaryNumericColumn)
                        .append(" (label column: ").append(labelColumn).append("):\n");
                List<TopValueRow> sortedRows = topRows.stream()
                        .sorted(Comparator.comparingDouble(TopValueRow::value).reversed()
                                .thenComparingLong(TopValueRow::rowNumber))
                        .toList();
                for (TopValueRow row : sortedRows) {
                    builder.append("  - ")
                            .append(row.label())
                            .append(" [row ")
                            .append(row.rowNumber())
                            .append("] => ")
                            .append(formatNumber(row.value()))
                            .append('\n');
                }
            }

            return builder.toString().trim();
        }

        private String resolveLabelColumn(List<String> allHeaders, List<String> numericCols) {
            for (String header : allHeaders) {
                if (!numericCols.contains(header)) {
                    return header;
                }
            }
            return allHeaders.isEmpty() ? "row" : allHeaders.get(0);
        }
    }

    private static final class NumericSummary {
        private long count;
        private double sum;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        private void add(double value) {
            count++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        private double average() {
            return count == 0 ? 0 : sum / count;
        }
    }

    private record TopValueRow(long rowNumber, String label, double value) {}
}
