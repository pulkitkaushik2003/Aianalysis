package com.example.aianalysis.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataAnalysisAssistantService {

    private static final Set<String> STOP_WORDS = Set.of(
            "average", "avg", "mean", "of", "ka", "ki", "ke", "kya", "hai", "batao",
            "show", "tell", "what", "is", "the", "do", "for", "me", "please", "find",
            "summary", "total", "sum", "top", "lowest", "minimum", "maximum", "max", "min"
    );

    private static final Map<String, List<String>> COLUMN_ALIASES = new HashMap<>();

    static {
        COLUMN_ALIASES.put("salary", List.of("salary", "sal", "income", "pay", "wage", "ctc"));
        COLUMN_ALIASES.put("age", List.of("age", "umar"));
        COLUMN_ALIASES.put("id", List.of("id", "identifier"));
        COLUMN_ALIASES.put("name", List.of("name", "employee", "person", "user"));
        COLUMN_ALIASES.put("department", List.of("department", "dept", "team"));
    }

    public String answerQuestion(String question,
                                 List<Map<String, Object>> data,
                                 List<String> headers,
                                 String fileName,
                                 Integer issueCount,
                                 Integer cleanRows) {

        if (data == null || data.isEmpty()) {
            return "## Data Missing\n\nPehle file upload karo, phir main us data par analysis kar dunga.";
        }

        String normalized = normalize(question);
        String numericColumn = findBestNumericColumn(data, headers);
        String labelColumn = findBestLabelColumn(data, headers, numericColumn);
        String mentionedColumn = findMentionedColumn(normalized, headers);
        String requestedTarget = extractRequestedTarget(normalized);

        if (containsAny(normalized, "summary", "summarize", "overview", "snapshot")) {
            return buildSummary(data, headers, fileName, issueCount, cleanRows, numericColumn);
        }

        if (containsAny(normalized, "column", "headers", "fields")) {
            return buildColumnsAnswer(headers);
        }

        if (containsAny(normalized, "row", "record", "entries", "kitne")) {
            return "## Record Count\n\nIs dataset me **" + data.size() + "** rows aur **" + headers.size() + "** columns hain.";
        }

        if (containsAny(normalized, "missing", "null", "empty")) {
            return buildMissingAnswer(data, headers);
        }

        if (containsAny(normalized, "top", "highest", "best", "maximum", "max")) {
            return buildTopAnswer(data, headers, mentionedColumn, requestedTarget, numericColumn, labelColumn);
        }

        if (containsAny(normalized, "lowest", "minimum", "min", "smallest")) {
            return buildLowestAnswer(data, headers, mentionedColumn, requestedTarget, numericColumn, labelColumn);
        }

        if (containsAny(normalized, "average", "avg", "mean")) {
            return buildAverageAnswer(data, headers, mentionedColumn, requestedTarget, numericColumn);
        }

        if (containsAny(normalized, "sum", "total")) {
            return buildTotalAnswer(data, headers, mentionedColumn, requestedTarget, numericColumn);
        }

        if (containsAny(normalized, "trend", "distribution", "category", "group")) {
            return buildDistributionAnswer(data, headers, mentionedColumn, labelColumn);
        }

        if (containsAny(normalized, "distinct", "unique")) {
            return buildDistinctAnswer(data, headers, mentionedColumn, labelColumn);
        }

        if (containsAny(normalized, "report")) {
            return buildReportAnswer(data, headers, fileName, issueCount, cleanRows, numericColumn, labelColumn);
        }

        return buildSmartFallback(data, headers, fileName, numericColumn, labelColumn);
    }

    private String buildSummary(List<Map<String, Object>> data,
                                List<String> headers,
                                String fileName,
                                Integer issueCount,
                                Integer cleanRows,
                                String numericColumn) {
        StringBuilder answer = new StringBuilder();
        answer.append("## Data Summary\n\n");
        answer.append("**File:** ").append(fileName == null ? "uploaded_file" : fileName).append("\n");
        answer.append("**Rows:** ").append(data.size()).append("\n");
        answer.append("**Columns:** ").append(headers.size()).append("\n");
        answer.append("**Clean Rows:** ").append(cleanRows == null ? "N/A" : cleanRows).append("\n");
        answer.append("**Issues:** ").append(issueCount == null ? "N/A" : issueCount).append("\n\n");
        answer.append("**Columns List:** ").append(String.join(", ", headers));

        if (numericColumn != null) {
            List<Double> nums = numericValues(data, numericColumn);
            if (!nums.isEmpty()) {
                answer.append("\n\n### Numeric Highlight\n");
                answer.append("- Best numeric column: **").append(numericColumn).append("**\n");
                answer.append("- Average: **").append(format(nums.stream().mapToDouble(Double::doubleValue).average().orElse(0))).append("**\n");
                answer.append("- Min: **").append(format(nums.stream().mapToDouble(Double::doubleValue).min().orElse(0))).append("**\n");
                answer.append("- Max: **").append(format(nums.stream().mapToDouble(Double::doubleValue).max().orElse(0))).append("**");
            }
        }

        return answer.toString();
    }

    private String buildColumnsAnswer(List<String> headers) {
        return "## Available Columns\n\n" +
                headers.stream().map(header -> "- " + header).collect(Collectors.joining("\n"));
    }

    private String buildMissingAnswer(List<Map<String, Object>> data, List<String> headers) {
        List<String> lines = new ArrayList<>();

        for (String header : headers) {
            long missing = data.stream()
                    .map(row -> row.get(header))
                    .filter(this::isBlank)
                    .count();
            if (missing > 0) {
                lines.add("- **" + header + "**: " + missing + " missing values");
            }
        }

        if (lines.isEmpty()) {
            return "## Missing Values\n\nAchhi baat ye hai ki visible dataset me missing values nahi mili.";
        }

        return "## Missing Values\n\n" + String.join("\n", lines);
    }

    private String buildTopAnswer(List<Map<String, Object>> data,
                                  List<String> headers,
                                  String mentionedColumn,
                                  String requestedTarget,
                                  String fallbackNumeric,
                                  String labelColumn) {
        String targetColumn = resolveNumericTarget(data, headers, mentionedColumn, requestedTarget, fallbackNumeric);
        if (targetColumn == null) {
            return "## Top Values\n\nMujhe koi numeric column clearly nahi mila jisse top values nikal sakun.";
        }

        List<Map<String, Object>> sorted = sortByNumeric(data, targetColumn, true).stream().limit(5).toList();
        return buildRankedAnswer("Top 5 in " + targetColumn, sorted, headers, labelColumn, targetColumn);
    }

    private String buildLowestAnswer(List<Map<String, Object>> data,
                                     List<String> headers,
                                     String mentionedColumn,
                                     String requestedTarget,
                                     String fallbackNumeric,
                                     String labelColumn) {
        String targetColumn = resolveNumericTarget(data, headers, mentionedColumn, requestedTarget, fallbackNumeric);
        if (targetColumn == null) {
            return "## Lowest Values\n\nMujhe koi numeric column clearly nahi mila jisse lowest values nikal sakun.";
        }

        List<Map<String, Object>> sorted = sortByNumeric(data, targetColumn, false).stream().limit(5).toList();
        return buildRankedAnswer("Lowest 5 in " + targetColumn, sorted, headers, labelColumn, targetColumn);
    }

    private String buildAverageAnswer(List<Map<String, Object>> data,
                                      List<String> headers,
                                      String mentionedColumn,
                                      String requestedTarget,
                                      String fallbackNumeric) {
        String targetColumn = resolveNumericTarget(data, headers, mentionedColumn, requestedTarget, fallbackNumeric);
        if (targetColumn == null) {
            return requestedTarget == null || requestedTarget.isBlank()
                    ? "## Average\n\nKoi numeric column identify nahi hua, isliye average calculate nahi kar paaya."
                    : "## Average\n\nMujhe **" + requestedTarget + "** naam ka matching numeric column nahi mila. Available columns dekhne ke liye `columns batao` pucho.";
        }

        List<Double> nums = numericValues(data, targetColumn);
        if (nums.isEmpty()) {
            return "## Average\n\n**" + targetColumn + "** me numeric data nahi mila.";
        }

        double avg = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return "## Average\n\n**" + targetColumn + "** ka average **" + format(avg) + "** hai.";
    }

    private String buildTotalAnswer(List<Map<String, Object>> data,
                                    List<String> headers,
                                    String mentionedColumn,
                                    String requestedTarget,
                                    String fallbackNumeric) {
        String targetColumn = resolveNumericTarget(data, headers, mentionedColumn, requestedTarget, fallbackNumeric);
        if (targetColumn == null) {
            return "## Total\n\nKoi numeric column identify nahi hua, isliye total calculate nahi kar paaya.";
        }

        List<Double> nums = numericValues(data, targetColumn);
        double sum = nums.stream().mapToDouble(Double::doubleValue).sum();
        return "## Total\n\n**" + targetColumn + "** ka total **" + format(sum) + "** hai.";
    }

    private String buildDistributionAnswer(List<Map<String, Object>> data, List<String> headers, String mentionedColumn, String fallbackLabel) {
        String targetColumn = mentionedColumn != null ? mentionedColumn : fallbackLabel;
        if (targetColumn == null) {
            return "## Distribution\n\nMujhe koi suitable categorical column nahi mila distribution dikhane ke liye.";
        }

        Map<String, Long> grouped = data.stream()
                .map(row -> normalizeLabel(row.get(targetColumn)))
                .collect(Collectors.groupingBy(label -> label, LinkedHashMap::new, Collectors.counting()));

        List<Map.Entry<String, Long>> top = grouped.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .toList();

        StringBuilder answer = new StringBuilder("## Distribution of ").append(targetColumn).append("\n\n");
        top.forEach(entry -> answer.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue()).append(" rows\n"));
        return answer.toString();
    }

    private String buildDistinctAnswer(List<Map<String, Object>> data, List<String> headers, String mentionedColumn, String fallbackLabel) {
        String targetColumn = mentionedColumn != null ? mentionedColumn : fallbackLabel;
        if (targetColumn == null) {
            return "## Unique Values\n\nKoi target column identify nahi hua.";
        }

        List<String> uniqueValues = data.stream()
                .map(row -> normalizeLabel(row.get(targetColumn)))
                .distinct()
                .limit(10)
                .toList();

        return "## Unique Values in " + targetColumn + "\n\n" +
                "- Count: **" + uniqueValues.size() + "+**\n" +
                uniqueValues.stream().map(value -> "- " + value).collect(Collectors.joining("\n"));
    }

    private String buildReportAnswer(List<Map<String, Object>> data,
                                     List<String> headers,
                                     String fileName,
                                     Integer issueCount,
                                     Integer cleanRows,
                                     String numericColumn,
                                     String labelColumn) {
        return buildSummary(data, headers, fileName, issueCount, cleanRows, numericColumn)
                + "\n\n"
                + buildDistributionAnswer(data, headers, labelColumn, labelColumn);
    }

    private String buildSmartFallback(List<Map<String, Object>> data,
                                      List<String> headers,
                                      String fileName,
                                      String numericColumn,
                                      String labelColumn) {
        StringBuilder answer = new StringBuilder();
        answer.append("## Main Data Samajh Gaya\n\n");
        answer.append("Mere paas **").append(fileName == null ? "uploaded data" : fileName).append("** ka dataset hai jisme **")
                .append(data.size()).append(" rows** aur **").append(headers.size()).append(" columns** hain.\n\n");

        if (numericColumn != null) {
            answer.append("- Best numeric column: **").append(numericColumn).append("**\n");
        }
        if (labelColumn != null) {
            answer.append("- Good label/category column: **").append(labelColumn).append("**\n");
        }

        answer.append("\nAap ye poochh sakte ho:\n");
        answer.append("- `summary do`\n");
        answer.append("- `top 5 batao`\n");
        answer.append("- `average of ").append(numericColumn == null ? "column" : numericColumn).append("`\n");
        answer.append("- `distribution of ").append(labelColumn == null ? headers.get(0) : labelColumn).append("`\n");
        answer.append("- `missing values batao`");
        return answer.toString();
    }

    private String buildRankedAnswer(String title,
                                     List<Map<String, Object>> rows,
                                     List<String> headers,
                                     String labelColumn,
                                     String numericColumn) {
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
                                        String mentionedColumn,
                                        String requestedTarget,
                                        String fallbackNumeric) {
        if (mentionedColumn != null && isNumericColumn(data, mentionedColumn)) {
            return mentionedColumn;
        }
        if (requestedTarget != null && !requestedTarget.isBlank()) {
            return null;
        }
        if (fallbackNumeric != null) {
            return fallbackNumeric;
        }
        return headers.stream().filter(header -> isNumericColumn(data, header)).findFirst().orElse(null);
    }

    private List<Map<String, Object>> sortByNumeric(List<Map<String, Object>> data, String column, boolean descending) {
        Comparator<Map<String, Object>> comparator = Comparator.comparingDouble(row -> parseNumber(row.get(column)));
        if (descending) {
            comparator = comparator.reversed();
        }
        return data.stream().sorted(comparator).toList();
    }

    private String findMentionedColumn(String normalizedQuestion, List<String> headers) {
        String bestHeader = null;
        int bestScore = 0;

        List<String> questionTokens = tokenize(normalizedQuestion);

        for (String header : headers) {
            int score = scoreHeaderMatch(questionTokens, header);
            if (score > bestScore) {
                bestScore = score;
                bestHeader = header;
            }
        }

        return bestScore > 0 ? bestHeader : null;
    }

    private String findBestNumericColumn(List<Map<String, Object>> data, List<String> headers) {
        return headers.stream()
                .max(Comparator.comparingInt(header -> numericValues(data, header).size()))
                .filter(header -> numericValues(data, header).size() > data.size() / 2)
                .orElse(null);
    }

    private String findBestLabelColumn(List<Map<String, Object>> data, List<String> headers, String numericColumn) {
        return headers.stream()
                .filter(header -> !Objects.equals(header, numericColumn))
                .filter(header -> !isNumericColumn(data, header))
                .findFirst()
                .orElse(headers.isEmpty() ? null : headers.get(0));
    }

    private boolean isNumericColumn(List<Map<String, Object>> data, String column) {
        return numericValues(data, column).size() > data.size() / 2;
    }

    private List<Double> numericValues(List<Map<String, Object>> data, String column) {
        return data.stream()
                .map(row -> parseNumberObject(row.get(column)))
                .filter(Objects::nonNull)
                .toList();
    }

    private Double parseNumberObject(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String cleaned = value.toString().replaceAll("[₹,\\s]", "");
            if (cleaned.isBlank()) {
                return null;
            }
            return Double.parseDouble(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private double parseNumber(Object value) {
        Double parsed = parseNumberObject(value);
        return parsed == null ? 0 : parsed;
    }

    private boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private List<String> tokenize(String value) {
        return Arrays.stream(normalize(value).split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private int scoreHeaderMatch(List<String> questionTokens, String header) {
        List<String> headerTokens = tokenize(header);
        int score = 0;

        for (String questionToken : questionTokens) {
            if (headerTokens.contains(questionToken)) {
                score += 3;
            }
        }

        List<String> aliases = aliasesForHeader(header);
        for (String alias : aliases) {
            if (questionTokens.contains(alias)) {
                score += 4;
            }
        }

        return score;
    }

    private List<String> aliasesForHeader(String header) {
        String normalizedHeader = normalize(header);
        List<String> aliases = new ArrayList<>();
        aliases.add(normalizedHeader);

        for (Map.Entry<String, List<String>> entry : COLUMN_ALIASES.entrySet()) {
            if (normalizedHeader.contains(entry.getKey())) {
                aliases.addAll(entry.getValue());
            }
        }

        return aliases;
    }

    private String extractRequestedTarget(String normalizedQuestion) {
        List<String> tokens = tokenize(normalizedQuestion);
        List<String> filtered = tokens.stream()
                .filter(token -> !STOP_WORDS.contains(token))
                .toList();

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

    private String format(double value) {
        return String.format(Locale.US, "%,.2f", value);
    }
}
