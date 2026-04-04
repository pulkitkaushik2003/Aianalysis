package com.example.aianalysis.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataCleaningService {

    // ================== ANALYZE (NO CHANGE) ==================
    public CleaningResult analyze(List<Map<String, Object>> data) {

        if (data == null || data.isEmpty()) {
            return new CleaningResult(data, 0, 0, 0, 0, 0, 0);
        }

        Set<String> seen = new HashSet<>();

        int totalRows = data.size();
        int cleanRows = 0;
        int issueCount = 0;
        int missingCount = 0;
        int duplicateCount = 0;
        int outlierCount = 0;

        for (Map<String, Object> row : data) {

            boolean isMissing = false;
            boolean isDuplicate = false;
            boolean isOutlier = false;

            // 🔴 Missing
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (!e.getKey().startsWith("_")) {
                    Object val = e.getValue();
                    if (val == null || val.toString().trim().isEmpty()) {
                        isMissing = true;
                        break;
                    }
                }
            }

            // 🟠 Duplicate
            String key = row.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("_"))
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("|"));

            if (!seen.add(key)) {
                isDuplicate = true;
            }

            // 🟣 Outlier
            for (Object val : row.values()) {
                try {
                    double num = Double.parseDouble(val.toString().replaceAll("[₹,\\s]", ""));
                    if (num > 100000) {
                        isOutlier = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            // 🔥 Issue tagging
            if (isMissing) {
                row.put("_issue", "missing");
                missingCount++;
                issueCount++;
            } else if (isDuplicate) {
                row.put("_issue", "duplicate");
                duplicateCount++;
                issueCount++;
            } else if (isOutlier) {
                row.put("_issue", "outlier");
                outlierCount++;
                issueCount++;
            } else {
                row.put("_issue", "clean");
                cleanRows++;
            }
        }

        return new CleaningResult(
                data,
                totalRows,
                cleanRows,
                issueCount,
                missingCount,
                duplicateCount,
                outlierCount
        );
    }

    // ================== 🔥 NEW: CLEAN DATA ==================
    public List<Map<String, Object>> cleanData(List<Map<String, Object>> data) {

        List<Map<String, Object>> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Map<String, Object> row : data) {

            Map<String, Object> newRow = new LinkedHashMap<>();

            for (Map.Entry<String, Object> e : row.entrySet()) {

                String key = e.getKey();
                if (key.startsWith("_")) continue;

                String val = e.getValue() == null ? "" : e.getValue().toString().trim();

                // 🔴 1. Missing fix
                if (val.isEmpty() || val.equalsIgnoreCase("N/A")) {
                    val = "Unknown";
                }

                // 🔵 2. Trim spaces
                val = val.trim();

                // 🟡 3. Remove special characters
                val = val.replaceAll("[^\\w\\s@.-]", "");

                // 🟣 4. Fix date format
                if (val.matches("\\d{2}/\\d{2}/\\d{4}")) {
                    String[] p = val.split("/");
                    val = p[2] + "-" + p[1] + "-" + p[0];
                }

                // 🟠 5. Fix numeric format
                try {
                    val = val.replaceAll(",", "");
                    if (val.matches("\\d+")) {
                        val = String.valueOf(Integer.parseInt(val));
                    }
                } catch (Exception ignored) {}

                newRow.put(key, val);
            }

            // 🔁 6. Remove duplicates
            String hash = newRow.toString();
            if (!seen.add(hash)) continue;

            // 🔴 7. Remove outliers
            boolean isOutlier = false;
            for (Object v : newRow.values()) {
                try {
                    double num = Double.parseDouble(v.toString());
                    if (num > 1000000) {
                        isOutlier = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (isOutlier) continue;

            cleaned.add(newRow);
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

        List<String> headers = data.get(0).keySet().stream()
                .filter(key -> !key.startsWith("_"))
                .toList();

        Map<String, Double> averages = computeAverages(data, headers);
        Map<String, Bounds> bounds = computeBounds(data, headers);

        List<Map<String, Object>> fixedRows = new ArrayList<>();
        Set<String> duplicateHashes = new HashSet<>();

        for (Map<String, Object> sourceRow : data) {
            String issue = sourceRow.get("_issue") == null ? "clean" : sourceRow.get("_issue").toString();
            Map<String, Object> row = new LinkedHashMap<>();

            for (String header : headers) {
                row.put(header, sourceRow.get(header));
            }

            if (isEnabled(enabledCards, "missing") && "missing".equals(issue)) {
                String missingOpt = option(selectedOpts, "missing", "avg");
                if ("delete".equals(missingOpt)) {
                    continue;
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
                            Double avg = averages.get(header);
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

                    String str = value.toString().trim().replaceAll("[₹,]", "");
                    if (!str.isEmpty() && str.matches("-?\\d+(\\.\\d+)?")) {
                        row.put(header, stripTrailingZeros(str));
                    }
                }
            }

            if (isEnabled(enabledCards, "outlier") && "outlier".equals(issue)) {
                String outlierOpt = option(selectedOpts, "outlier", "highlight");
                if ("remove".equals(outlierOpt)) {
                    continue;
                }

                if ("cap".equals(outlierOpt)) {
                    for (String header : headers) {
                        Double value = parseNumber(row.get(header));
                        Bounds limit = bounds.get(header);
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

            String hash = headers.stream()
                    .map(header -> String.valueOf(row.get(header)))
                    .collect(Collectors.joining("|"));

            if (isEnabled(enabledCards, "duplicate")
                    && "remove".equals(option(selectedOpts, "duplicate", "remove"))) {
                if (!duplicateHashes.add(hash)) {
                    continue;
                }
            }

            fixedRows.add(row);
        }

        return fixedRows;
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

            if (nums.size() < 4) {
                continue;
            }

            double q1 = nums.get((int) Math.floor(nums.size() * 0.25));
            double q3 = nums.get((int) Math.floor(nums.size() * 0.75));
            double iqr = q3 - q1;
            bounds.put(header, new Bounds(q1 - 1.5 * iqr, q3 + 1.5 * iqr));
        }

        return bounds;
    }

    private boolean isEnabled(Map<String, Boolean> enabledCards, String key) {
        return enabledCards == null || !enabledCards.containsKey(key) || Boolean.TRUE.equals(enabledCards.get(key));
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
            String str = value.toString().trim().replaceAll("[₹,\\s]", "");
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

    private static class Bounds {
        private final double lower;
        private final double upper;

        private Bounds(double lower, double upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    // ================== RESULT CLASS ==================
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
