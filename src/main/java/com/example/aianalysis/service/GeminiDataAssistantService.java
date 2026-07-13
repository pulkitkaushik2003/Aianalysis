package com.example.aianalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiDataAssistantService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${gemini.api.key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}")
    private String geminiApiKey;

    @Value("${gemini.chat.model:${gemini.model:gemini-2.5-flash-lite}}")
    private String geminiModel;

    @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl;

    public GeminiDataAssistantService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public boolean isConfigured() {
        return hasGemini();
    }

    public String askQuestion(String question,
                              List<Map<String, Object>> data,
                              List<String> headers,
                              String fileName,
                              Integer rowCount,
                              Integer colCount,
                              Integer issueCount,
                              Integer cleanRows) throws Exception {

        if (!isConfigured()) {
            throw new IllegalStateException("GEMINI_API_KEY configured nahi hai.");
        }

        String apiKey = cleanConfigValue(geminiApiKey);
        String model = cleanConfigValue(geminiModel);
        String baseUrl = cleanConfigValue(geminiBaseUrl);
        if (model.isBlank()) {
            throw new IllegalStateException("Gemini model configured nahi hai.");
        }
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("Gemini base URL configured nahi hai.");
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 900);
        if (model.startsWith("gemini-2.5")) {
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(Map.of(
                "parts", List.of(Map.of(
                        "text", buildPrompt(question, data, headers, fileName, rowCount, colCount, issueCount, cleanRows)
                ))
        )));
        requestBody.put("generationConfig", generationConfig);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gemini API error: " + extractApiErrorMessage(response.body()));
        }

        return extractAnswer(response.body());
    }

    public String activeProvider() {
        return "gemini";
    }

    private boolean hasGemini() {
        return geminiApiKey != null && !cleanConfigValue(geminiApiKey).isBlank();
    }

    private String cleanConfigValue(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String buildPrompt(String question,
                               List<Map<String, Object>> data,
                               List<String> headers,
                               String fileName,
                               Integer rowCount,
                               Integer colCount,
                               Integer issueCount,
                               Integer cleanRows) throws Exception {
        List<Map<String, Object>> sample = data.stream().limit(40).toList();

        return """
                You are an expert data analyst inside a web app called InsightAnalytics.
                Follow the user's latest question exactly. Do not answer a different question.

                Critical response rules:
                - If the user only greets you, replies casually, or says something like hi/hello/ok/thanks,
                  respond conversationally in 1-2 short Hinglish lines. Do not summarize the dataset.
                - Only summarize the dataset when the user explicitly asks for summary, overview, report,
                  insights, trends, anomalies, top performers, prediction, or a specific analysis.
                - If the user asks a specific metric/question, answer only that metric/question.
                - If the provided data is not enough to answer, say exactly what is missing.
                - Use Hinglish and keep answers practical and crisp.
                - Prefer markdown with short sections or bullets when helpful.
                - Mention exact columns used whenever useful.

                Dataset info:
                - File: %s
                - Rows: %s
                - Columns: %s
                - Clean rows: %s
                - Issues: %s
                - Headers: %s

                Sample rows JSON:
                %s

                User question:
                %s
                """.formatted(
                fileName == null ? "uploaded_file" : fileName,
                rowCount == null ? data.size() : rowCount,
                colCount == null ? headers.size() : colCount,
                cleanRows == null ? "N/A" : cleanRows,
                issueCount == null ? "N/A" : issueCount,
                String.join(", ", headers),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample),
                question == null || question.isBlank() ? "Dataset ka useful summary do." : question
        );
    }

    private String extractAnswer(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray()) {
            StringBuilder answer = new StringBuilder();

            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (!parts.isArray()) {
                    continue;
                }

                for (JsonNode part : parts) {
                    JsonNode textNode = part.path("text");
                    if (textNode.isTextual() && !textNode.asText().isBlank()) {
                        if (!answer.isEmpty()) {
                            answer.append("\n\n");
                        }
                        answer.append(textNode.asText().trim());
                    }
                }
            }

            if (!answer.isEmpty()) {
                return answer.toString();
            }
        }

        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
            throw new IllegalStateException("Gemini request blocked hui: " + blockReason.asText());
        }

        throw new IllegalStateException("Gemini response se text extract nahi hua.");
    }

    private String extractApiErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Unknown Gemini API error.";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Fall through to raw response below.
        }

        return responseBody;
    }
}
