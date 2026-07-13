package com.example.aianalysis.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdvancedStatsAssistantService {

    private static final Set<String> STOP_WORDS = Set.of(
            "average", "avg", "mean", "median", "mode", "variance", "std", "deviation",
            "percentile", "quartile", "q1", "q3", "sum", "total", "top", "lowest",
            "minimum", "maximum", "max", "min", "weighted", "weight", "correlation",
            "regression", "forecast", "predict", "trend", "distribution", "group",
            "growth", "moving", "probability", "normalization", "normalize",
            "standardization", "standardize", "z", "score", "skewness", "kurtosis",
            "hypothesis", "significance", "of", "ka", "ki", "ke", "kya", "hai",
            "batao", "show", "tell", "what", "is", "the", "do", "for", "me", "please",
            "find", "summary"
    );

    public String answerQuestion(String question,
                                 List<Map<String, Object>> data,
                                 List<String> headers,
                                 String fileName,
                                 Integer issueCount,
                                 Integer cleanRows) {
        if (data == null || data.isEmpty()) {
            return "## Data Missing\n\nPehle file upload karo, phir main analysis kar dunga.";
        }

        String normalized = normalize(question);
        List<String> numericColumns = findNumericColumns(data, headers);
        List<String> mentionedColumns = findMentionedColumns(normalized, headers);
        String primary = numericColumns.isEmpty() ? null : numericColumns.get(0);
        String secondary = numericColumns.size() > 1 ? numericColumns.get(1) : null;
        String labelColumn = findBestLabelColumn(data, headers, primary);
        String requestedTarget = extractRequestedTarget(normalized);

        if (containsAny(normalized, "summary", "overview", "snapshot")) {
            return buildSummary(data, headers, fileName, issueCount, cleanRows, primary, secondary);
        }
        if (containsAny(normalized, "column", "headers", "fields")) {
            return "## Available Columns\n\n" + headers.stream().map(header -> "- " + header).collect(Collectors.joining("\n"));
        }
        if (containsAny(normalized, "missing", "null", "empty")) {
            return buildMissingAnswer(data, headers);
        }
        if (containsAny(normalized, "median")) {
            return simpleMetricAnswer("Median", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::median);
        }
        if (containsAny(normalized, "mode")) {
            return simpleMetricAnswer("Mode", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::mode);
        }
        if (containsAny(normalized, "variance")) {
            return simpleMetricAnswer("Variance", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::variance);
        }
        if (containsAny(normalized, "std", "standard deviation")) {
            return simpleMetricAnswer("Standard Deviation", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::stdDev);
        }
        if (containsAny(normalized, "z score", "zscore", "z-score")) {
            return buildZScoreAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "percentile", "quartile", "q1", "q3", "p90", "p95")) {
            return buildPercentileAnswer(data, headers, mentionedColumns, requestedTarget, primary, normalized);
        }
        if (containsAny(normalized, "top", "highest", "best", "maximum", "max")) {
            String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
            return buildRankedAnswer("Top 5 in " + target, sortByNumeric(data, target, true).stream().limit(5).toList(), labelColumn, target);
        }
        if (containsAny(normalized, "lowest", "minimum", "min", "smallest")) {
            String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
            return buildRankedAnswer("Lowest 5 in " + target, sortByNumeric(data, target, false).stream().limit(5).toList(), labelColumn, target);
        }
        if (containsAny(normalized, "weighted", "weight")) {
            return buildWeightedAverageAnswer(data, headers, mentionedColumns, numericColumns);
        }
        if (containsAny(normalized, "average", "avg", "mean")) {
            return simpleMetricAnswer("Average", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::mean);
        }
        if (containsAny(normalized, "sum", "total")) {
            return simpleMetricAnswer("Total", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::sum);
        }
        if (containsAny(normalized, "growth", "percentage change", "percent change")) {
            return buildGrowthAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "moving average")) {
            return buildMovingAverageAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "correlation")) {
            return buildCorrelationAnswer(data, headers, mentionedColumns, numericColumns);
        }
        if (containsAny(normalized, "regression", "slope")) {
            return buildRegressionAnswer(data, headers, mentionedColumns, numericColumns);
        }
        if (containsAny(normalized, "forecast", "predict")) {
            return buildForecastAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "probability", "chance")) {
            return buildProbabilityAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "normalization", "normalize", "standardization", "standardize")) {
            return buildNormalizationAnswer(data, headers, mentionedColumns, requestedTarget, primary);
        }
        if (containsAny(normalized, "skewness")) {
            return simpleMetricAnswer("Skewness", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::skewness);
        }
        if (containsAny(normalized, "kurtosis")) {
            return simpleMetricAnswer("Kurtosis", resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0), data, this::kurtosis);
        }
        if (containsAny(normalized, "distribution", "category", "group", "trend")) {
            return buildDistributionAnswer(data, resolveColumnTarget(mentionedColumns, headers, labelColumn, 0));
        }
        if (containsAny(normalized, "distinct", "unique")) {
            return buildDistinctAnswer(data, resolveColumnTarget(mentionedColumns, headers, labelColumn, 0));
        }
        if (containsAny(normalized, "hypothesis", "significance")) {
            return buildHypothesisAnswer(data, headers, mentionedColumns, numericColumns);
        }

        return buildFallback(data, headers, fileName, primary, secondary, labelColumn);
    }

    private String buildSummary(List<Map<String, Object>> data,
                                List<String> headers,
                                String fileName,
                                Integer issueCount,
                                Integer cleanRows,
                                String primary,
                                String secondary) {
        StringBuilder answer = new StringBuilder();
        answer.append("## Data Summary\n\n");
        answer.append("**File:** ").append(fileName == null ? "uploaded_file" : fileName).append("\n");
        answer.append("**Rows:** ").append(data.size()).append("\n");
        answer.append("**Columns:** ").append(headers.size()).append("\n");
        answer.append("**Clean Rows:** ").append(cleanRows == null ? "N/A" : cleanRows).append("\n");
        answer.append("**Issues:** ").append(issueCount == null ? "N/A" : issueCount).append("\n");
        if (primary != null) {
            List<Double> nums = numericValues(data, primary);
            answer.append("\n### ").append(primary).append(" Stats\n");
            answer.append("- Mean: **").append(format(mean(nums))).append("**\n");
            answer.append("- Median: **").append(format(median(nums))).append("**\n");
            answer.append("- Mode: **").append(format(mode(nums))).append("**\n");
            answer.append("- Variance: **").append(format(variance(nums))).append("**\n");
            answer.append("- Std Dev: **").append(format(stdDev(nums))).append("**\n");
            answer.append("- Q1 / Q3: **").append(format(percentile(nums, 25))).append(" / ").append(format(percentile(nums, 75))).append("**\n");
            answer.append("- P90: **").append(format(percentile(nums, 90))).append("**\n");
            answer.append("- Skewness: **").append(format(skewness(nums))).append("**\n");
            answer.append("- Kurtosis: **").append(format(kurtosis(nums))).append("**\n");
        }
        if (primary != null && secondary != null) {
            PairSeries series = pairedNumericSeries(data, primary, secondary);
            if (series.x.size() >= 3) {
                RegressionStats regression = regression(series.x, series.y);
                answer.append("\n### Relationship\n");
                answer.append("- Correlation: **").append(format(correlation(series.x, series.y))).append("**\n");
                answer.append("- Regression slope: **").append(format(regression.slope)).append("**\n");
                answer.append("- R²: **").append(format(regression.rSquared)).append("**\n");
            }
        }
        return answer.toString();
    }

    private String buildMissingAnswer(List<Map<String, Object>> data, List<String> headers) {
        List<String> lines = new ArrayList<>();
        for (String header : headers) {
            long missing = data.stream().map(row -> row.get(header)).filter(this::isBlank).count();
            if (missing > 0) {
                lines.add("- **" + header + "**: " + missing + " missing values");
            }
        }
        return lines.isEmpty() ? "## Missing Values\n\nMissing values nahi mili." : "## Missing Values\n\n" + String.join("\n", lines);
    }

    private String simpleMetricAnswer(String title,
                                      String target,
                                      List<Map<String, Object>> data,
                                      MetricFunction fn) {
        if (target == null) {
            return "## " + title + "\n\nKoi numeric column identify nahi hua.";
        }
        return "## " + title + "\n\n**" + target + "** ka " + title.toLowerCase(Locale.ROOT) + " **"
                + format(fn.apply(numericValues(data, target))) + "** hai.";
    }

    private String buildZScoreAnswer(List<Map<String, Object>> data,
                                     List<String> headers,
                                     List<String> mentionedColumns,
                                     String requestedTarget,
                                     String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Z-Score\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        double mean = mean(nums);
        double std = stdDev(nums);
        if (std == 0) {
            return "## Z-Score\n\n**" + target + "** me variance zero hai.";
        }
        double peak = nums.stream().mapToDouble(value -> Math.abs((value - mean) / std)).max().orElse(0);
        return "## Z-Score\n\n**" + target + "** ka max absolute z-score **" + format(peak)
                + "** hai. Z-score 3+ ho to strong outlier signal hota hai.";
    }

    private String buildPercentileAnswer(List<Map<String, Object>> data,
                                         List<String> headers,
                                         List<String> mentionedColumns,
                                         String requestedTarget,
                                         String primary,
                                         String normalizedQuestion) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Percentiles\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        StringBuilder answer = new StringBuilder("## Percentiles & Quartiles\n\n");
        answer.append("- Q1: **").append(format(percentile(nums, 25))).append("**\n");
        answer.append("- Median: **").append(format(percentile(nums, 50))).append("**\n");
        answer.append("- Q3: **").append(format(percentile(nums, 75))).append("**\n");
        answer.append("- P90: **").append(format(percentile(nums, 90))).append("**");
        if (containsAny(normalizedQuestion, "p95", "95")) {
            answer.append("\n- P95: **").append(format(percentile(nums, 95))).append("**");
        }
        return answer.toString();
    }

    private String buildWeightedAverageAnswer(List<Map<String, Object>> data,
                                              List<String> headers,
                                              List<String> mentionedColumns,
                                              List<String> numericColumns) {
        if (numericColumns.size() < 2) {
            return "## Weighted Average\n\nKam se kam 2 numeric columns chahiye.";
        }
        String valueColumn = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(0), 0);
        String weightColumn = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(1), valueColumn != null && valueColumn.equals(numericColumns.get(0)) ? 1 : 0);
        if (valueColumn == null || weightColumn == null || valueColumn.equals(weightColumn)) {
            return "## Weighted Average\n\nValue aur weight ke liye 2 alag numeric columns nahi mile.";
        }
        PairSeries series = pairedNumericSeries(data, valueColumn, weightColumn);
        return "## Weighted Average\n\nValue **" + valueColumn + "** aur weight **" + weightColumn
                + "** ke saath weighted average **" + format(weightedAverage(series.x, series.y)) + "** hai.";
    }

    private String buildGrowthAnswer(List<Map<String, Object>> data,
                                     List<String> headers,
                                     List<String> mentionedColumns,
                                     String requestedTarget,
                                     String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Growth Rate\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        if (nums.size() < 2) {
            return "## Growth Rate\n\nKam se kam 2 values chahiye.";
        }
        double first = nums.get(0);
        double last = nums.get(nums.size() - 1);
        double change = last - first;
        double growth = first == 0 ? 0 : (change / first) * 100.0;
        return "## Growth Rate\n\n- First value: **" + format(first) + "**\n- Last value: **" + format(last)
                + "**\n- Absolute change: **" + format(change) + "**\n- Growth: **" + format(growth) + "%**";
    }

    private String buildMovingAverageAnswer(List<Map<String, Object>> data,
                                            List<String> headers,
                                            List<String> mentionedColumns,
                                            String requestedTarget,
                                            String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Moving Average\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        if (nums.size() < 3) {
            return "## Moving Average\n\n3-point moving average ke liye 3 values chahiye.";
        }
        List<Double> moving = movingAverage(nums, 3);
        List<String> latest = moving.stream().skip(Math.max(0, moving.size() - 3)).map(this::format).toList();
        return "## Moving Average\n\nLatest 3 moving averages: **" + String.join(", ", latest) + "**";
    }

    private String buildCorrelationAnswer(List<Map<String, Object>> data,
                                          List<String> headers,
                                          List<String> mentionedColumns,
                                          List<String> numericColumns) {
        if (numericColumns.size() < 2) {
            return "## Correlation\n\n2 numeric columns chahiye.";
        }
        String first = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(0), 0);
        String second = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(1), first != null && first.equals(numericColumns.get(0)) ? 1 : 0);
        if (first == null || second == null || first.equals(second)) {
            return "## Correlation\n\n2 alag numeric columns identify nahi hue.";
        }
        PairSeries series = pairedNumericSeries(data, first, second);
        double r = correlation(series.x, series.y);
        String strength = Math.abs(r) >= 0.7 ? "strong" : Math.abs(r) >= 0.4 ? "moderate" : "weak";
        return "## Correlation\n\n**" + first + "** aur **" + second + "** ka correlation **" + format(r)
                + "** hai, jo **" + strength + "** relationship dikhata hai.";
    }

    private String buildRegressionAnswer(List<Map<String, Object>> data,
                                         List<String> headers,
                                         List<String> mentionedColumns,
                                         List<String> numericColumns) {
        if (numericColumns.size() < 2) {
            return "## Regression\n\n2 numeric columns chahiye.";
        }
        String first = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(0), 0);
        String second = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(1), first != null && first.equals(numericColumns.get(0)) ? 1 : 0);
        PairSeries series = pairedNumericSeries(data, first, second);
        RegressionStats regression = regression(series.x, series.y);
        return "## Regression\n\n`" + second + " = " + format(regression.intercept) + " + "
                + format(regression.slope) + " * " + first + "`\n- R²: **" + format(regression.rSquared) + "**";
    }

    private String buildForecastAnswer(List<Map<String, Object>> data,
                                       List<String> headers,
                                       List<String> mentionedColumns,
                                       String requestedTarget,
                                       String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Forecast\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        if (nums.size() < 3) {
            return "## Forecast\n\nKam se kam 3 values chahiye.";
        }
        List<Double> x = new ArrayList<>();
        for (int i = 0; i < nums.size(); i++) {
            x.add((double) (i + 1));
        }
        RegressionStats regression = regression(x, nums);
        double regressionForecast = regression.intercept + regression.slope * (nums.size() + 1.0);
        double movingForecast = mean(nums.subList(Math.max(0, nums.size() - 3), nums.size()));
        return "## Forecast\n\n- Regression forecast: **" + format(regressionForecast)
                + "**\n- Moving-average forecast: **" + format(movingForecast) + "**";
    }

    private String buildProbabilityAnswer(List<Map<String, Object>> data,
                                          List<String> headers,
                                          List<String> mentionedColumns,
                                          String requestedTarget,
                                          String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Probability\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        double avg = mean(nums);
        long aboveAverage = nums.stream().filter(value -> value > avg).count();
        double chance = nums.isEmpty() ? 0 : (aboveAverage * 100.0) / nums.size();
        return "## Probability\n\nEmpirical chance ki **" + target + "** average se upar ho: **" + format(chance) + "%**";
    }

    private String buildNormalizationAnswer(List<Map<String, Object>> data,
                                            List<String> headers,
                                            List<String> mentionedColumns,
                                            String requestedTarget,
                                            String primary) {
        String target = resolveNumericTarget(data, headers, mentionedColumns, requestedTarget, primary, 0);
        if (target == null) {
            return "## Normalization\n\nKoi numeric column identify nahi hua.";
        }
        List<Double> nums = numericValues(data, target);
        if (nums.size() < 2) {
            return "## Normalization\n\nReliable scaling ke liye aur values chahiye.";
        }
        double min = Collections.min(nums);
        double max = Collections.max(nums);
        double avg = mean(nums);
        double std = stdDev(nums);
        List<String> minMax = nums.stream().limit(3).map(value -> format((value - min) / ((max - min) == 0 ? 1 : (max - min)))).toList();
        List<String> zScores = nums.stream().limit(3).map(value -> format(std == 0 ? 0 : (value - avg) / std)).toList();
        return "## Normalization & Standardization\n\n- Min-Max preview: **" + String.join(", ", minMax)
                + "**\n- Z-score preview: **" + String.join(", ", zScores) + "**";
    }

    private String buildDistributionAnswer(List<Map<String, Object>> data, String target) {
        if (target == null) {
            return "## Distribution\n\nKoi suitable column identify nahi hua.";
        }
        Map<String, Long> grouped = data.stream()
                .map(row -> normalizeLabel(row.get(target)))
                .collect(Collectors.groupingBy(label -> label, LinkedHashMap::new, Collectors.counting()));
        return "## Distribution of " + target + "\n\n" + grouped.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .map(entry -> "- **" + entry.getKey() + "**: " + entry.getValue() + " rows")
                .collect(Collectors.joining("\n"));
    }

    private String buildDistinctAnswer(List<Map<String, Object>> data, String target) {
        if (target == null) {
            return "## Unique Values\n\nKoi target column identify nahi hua.";
        }
        List<String> unique = data.stream().map(row -> normalizeLabel(row.get(target))).distinct().limit(10).toList();
        return "## Unique Values in " + target + "\n\n- Count: **" + unique.size() + "+**\n"
                + unique.stream().map(value -> "- " + value).collect(Collectors.joining("\n"));
    }

    private String buildHypothesisAnswer(List<Map<String, Object>> data,
                                         List<String> headers,
                                         List<String> mentionedColumns,
                                         List<String> numericColumns) {
        if (numericColumns.size() < 2) {
            return "## Hypothesis Check\n\n2 numeric columns chahiye.";
        }
        String first = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(0), 0);
        String second = resolveNumericTarget(data, headers, mentionedColumns, null, numericColumns.get(1), first != null && first.equals(numericColumns.get(0)) ? 1 : 0);
        PairSeries series = pairedNumericSeries(data, first, second);
        double r = correlation(series.x, series.y);
        String verdict = Math.abs(r) >= 0.5
                ? "Null hypothesis weak lag rahi hai."
                : "Null hypothesis ko reject karne jaisa strong signal nahi mila.";
        return "## Hypothesis Check\n\n`H0: " + first + " aur " + second + " me linear relationship nahi hai.`\n"
                + "- Observed correlation: **" + format(r) + "**\n- Interpretation: " + verdict;
    }

    private String buildFallback(List<Map<String, Object>> data,
                                 List<String> headers,
                                 String fileName,
                                 String primary,
                                 String secondary,
                                 String labelColumn) {
        StringBuilder answer = new StringBuilder();
        answer.append("## Main Data Samajh Gaya\n\n");
        answer.append("Dataset **").append(fileName == null ? "uploaded_file" : fileName).append("** me **")
                .append(data.size()).append(" rows** aur **").append(headers.size()).append(" columns** hain.\n");
        if (primary != null) answer.append("- Primary numeric: **").append(primary).append("**\n");
        if (secondary != null) answer.append("- Secondary numeric: **").append(secondary).append("**\n");
        if (labelColumn != null) answer.append("- Category column: **").append(labelColumn).append("**\n");
        answer.append("\nAap poochh sakte ho:\n");
        answer.append("- `median batao`\n");
        answer.append("- `standard deviation batao`\n");
        answer.append("- `correlation batao`\n");
        answer.append("- `forecast karo`\n");
        answer.append("- `percentile batao`\n");
        answer.append("- `weighted average batao`");
        return answer.toString();
    }

    private String buildRankedAnswer(String title,
                                     List<Map<String, Object>> rows,
                                     String labelColumn,
                                     String numericColumn) {
        if (numericColumn == null) {
            return "## " + title + "\n\nKoi numeric column identify nahi hua.";
        }
        StringBuilder answer = new StringBuilder("## ").append(title).append("\n\n");
        answer.append("| # | ").append(labelColumn == null ? "Row" : labelColumn).append(" | ").append(numericColumn).append(" |\n");
        answer.append("|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String label = labelColumn == null ? "Row " + (i + 1) : normalizeLabel(row.get(labelColumn));
            answer.append("| ").append(i + 1).append(" | ").append(label).append(" | ").append(normalizeLabel(row.get(numericColumn))).append(" |\n");
        }
        return answer.toString();
    }

    private String resolveNumericTarget(List<Map<String, Object>> data,
                                        List<String> headers,
                                        List<String> mentionedColumns,
                                        String requestedTarget,
                                        String fallback,
                                        int fallbackIndex) {
        for (String column : mentionedColumns) {
            if (isNumericColumn(data, column)) {
                return column;
            }
        }
        if (requestedTarget != null && !requestedTarget.isBlank()) {
            return null;
        }
        List<String> numericColumns = findNumericColumns(data, headers);
        if (numericColumns.size() > fallbackIndex) {
            return numericColumns.get(fallbackIndex);
        }
        return fallback;
    }

    private String resolveColumnTarget(List<String> mentionedColumns,
                                       List<String> headers,
                                       String fallback,
                                       int fallbackIndex) {
        if (!mentionedColumns.isEmpty()) {
            return mentionedColumns.get(0);
        }
        if (fallback != null) {
            return fallback;
        }
        return headers.size() > fallbackIndex ? headers.get(fallbackIndex) : null;
    }

    private List<String> findNumericColumns(List<Map<String, Object>> data, List<String> headers) {
        return headers.stream()
                .sorted(Comparator.comparingInt((String header) -> numericValues(data, header).size()).reversed())
                .filter(header -> numericValues(data, header).size() > data.size() / 2)
                .toList();
    }

    private boolean isNumericColumn(List<Map<String, Object>> data, String column) {
        return numericValues(data, column).size() > data.size() / 2;
    }

    private String findBestLabelColumn(List<Map<String, Object>> data, List<String> headers, String numericColumn) {
        return headers.stream()
                .filter(header -> !Objects.equals(header, numericColumn))
                .filter(header -> !isNumericColumn(data, header))
                .findFirst()
                .orElse(headers.isEmpty() ? null : headers.get(0));
    }

    private List<String> findMentionedColumns(String normalizedQuestion, List<String> headers) {
        List<String> questionTokens = tokenize(normalizedQuestion);
        return headers.stream()
                .map(header -> Map.entry(header, scoreHeaderMatch(questionTokens, header)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private int scoreHeaderMatch(List<String> questionTokens, String header) {
        List<String> headerTokens = tokenize(header);
        int score = 0;
        for (String token : questionTokens) {
            if (headerTokens.contains(token)) {
                score += 3;
            }
        }
        return score;
    }

    private List<Double> numericValues(List<Map<String, Object>> data, String column) {
        return data.stream().map(row -> parseNumberObject(row.get(column))).filter(Objects::nonNull).toList();
    }

    private PairSeries pairedNumericSeries(List<Map<String, Object>> data, String xColumn, String yColumn) {
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Double xv = parseNumberObject(row.get(xColumn));
            Double yv = parseNumberObject(row.get(yColumn));
            if (xv != null && yv != null) {
                x.add(xv);
                y.add(yv);
            }
        }
        return new PairSeries(x, y);
    }

    private Double parseNumberObject(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String cleaned = value.toString().replaceAll("[₹,\\s]", "").trim();
            if (cleaned.isEmpty()) return null;
            return Double.parseDouble(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> sortByNumeric(List<Map<String, Object>> data, String column, boolean descending) {
        if (column == null) {
            return List.of();
        }
        Comparator<Map<String, Object>> comparator = Comparator.comparingDouble(row -> {
            Double value = parseNumberObject(row.get(column));
            return value == null ? 0 : value;
        });
        return data.stream().sorted(descending ? comparator.reversed() : comparator).toList();
    }

    private boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private List<String> tokenize(String value) {
        return Arrays.stream(normalize(value).split("\\s+")).filter(token -> !token.isBlank()).toList();
    }

    private String extractRequestedTarget(String normalizedQuestion) {
        List<String> filtered = tokenize(normalizedQuestion).stream().filter(token -> !STOP_WORDS.contains(token)).toList();
        return filtered.isEmpty() ? null : String.join(" ", filtered);
    }

    private boolean containsAny(String normalized, String... tokens) {
        for (String token : tokens) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeLabel(Object value) {
        return value == null || value.toString().trim().isEmpty() ? "Unknown" : value.toString();
    }

    private double sum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private double mean(List<Double> values) {
        return values.isEmpty() ? 0 : sum(values) / values.size();
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 0 ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0 : sorted.get(n / 2);
    }

    private double mode(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        Map<Double, Long> freq = values.stream()
                .collect(Collectors.groupingBy(value -> Math.round(value * 100.0) / 100.0, LinkedHashMap::new, Collectors.counting()));
        return freq.entrySet().stream()
                .max(Map.Entry.<Double, Long>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(0.0);
    }

    private double variance(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = mean(values);
        return values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / (values.size() - 1);
    }

    private double stdDev(List<Double> values) {
        return Math.sqrt(variance(values));
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        double position = (percentile / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = position - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }

    private List<Double> movingAverage(List<Double> values, int window) {
        List<Double> moving = new ArrayList<>();
        for (int i = window - 1; i < values.size(); i++) {
            moving.add(mean(values.subList(i - window + 1, i + 1)));
        }
        return moving;
    }

    private double correlation(List<Double> x, List<Double> y) {
        if (x.size() < 2 || x.size() != y.size()) {
            return 0;
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double numerator = 0;
        double sumSqX = 0;
        double sumSqY = 0;
        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            numerator += dx * dy;
            sumSqX += dx * dx;
            sumSqY += dy * dy;
        }
        double denominator = Math.sqrt(sumSqX * sumSqY);
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double weightedAverage(List<Double> values, List<Double> weights) {
        double totalWeight = sum(weights);
        if (totalWeight == 0) {
            return 0;
        }
        double weightedTotal = 0;
        for (int i = 0; i < Math.min(values.size(), weights.size()); i++) {
            weightedTotal += values.get(i) * weights.get(i);
        }
        return weightedTotal / totalWeight;
    }

    private double skewness(List<Double> values) {
        if (values.size() < 3) {
            return 0;
        }
        double mean = mean(values);
        double std = stdDev(values);
        if (std == 0) {
            return 0;
        }
        double n = values.size();
        double thirdMoment = values.stream().mapToDouble(value -> Math.pow((value - mean) / std, 3)).sum();
        return (n / ((n - 1) * (n - 2))) * thirdMoment;
    }

    private double kurtosis(List<Double> values) {
        if (values.size() < 4) {
            return 0;
        }
        double mean = mean(values);
        double std = stdDev(values);
        if (std == 0) {
            return 0;
        }
        double n = values.size();
        double fourthMoment = values.stream().mapToDouble(value -> Math.pow((value - mean) / std, 4)).sum();
        double numerator = (n * (n + 1) * fourthMoment) - (3 * Math.pow(n - 1, 2) * (n - 1));
        double denominator = (n - 1) * (n - 2) * (n - 3);
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private RegressionStats regression(List<Double> x, List<Double> y) {
        if (x.size() < 2 || x.size() != y.size()) {
            return new RegressionStats(0, 0, 0);
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < x.size(); i++) {
            numerator += (x.get(i) - meanX) * (y.get(i) - meanY);
            denominator += Math.pow(x.get(i) - meanX, 2);
        }
        double slope = denominator == 0 ? 0 : numerator / denominator;
        double intercept = meanY - slope * meanX;
        double ssTotal = y.stream().mapToDouble(value -> Math.pow(value - meanY, 2)).sum();
        double ssResidual = 0;
        for (int i = 0; i < x.size(); i++) {
            double predicted = intercept + slope * x.get(i);
            ssResidual += Math.pow(y.get(i) - predicted, 2);
        }
        double rSquared = ssTotal == 0 ? 0 : 1 - (ssResidual / ssTotal);
        return new RegressionStats(slope, intercept, rSquared);
    }

    private String format(double value) {
        return String.format(Locale.US, "%,.2f", value);
    }

    @FunctionalInterface
    private interface MetricFunction {
        double apply(List<Double> values);
    }

    private static class PairSeries {
        private final List<Double> x;
        private final List<Double> y;

        private PairSeries(List<Double> x, List<Double> y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class RegressionStats {
        private final double slope;
        private final double intercept;
        private final double rSquared;

        private RegressionStats(double slope, double intercept, double rSquared) {
            this.slope = slope;
            this.intercept = intercept;
            this.rSquared = rSquared;
        }
    }
}
