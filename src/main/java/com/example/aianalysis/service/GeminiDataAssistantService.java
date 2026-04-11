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

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 900);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(Map.of(
                "parts", List.of(Map.of(
                        "text", buildPrompt(question, data, headers, fileName, rowCount, colCount, issueCount, cleanRows)
                ))
        )));
        requestBody.put("generationConfig", generationConfig);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(geminiBaseUrl + "/models/" + geminiModel + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("x-goog-api-key", geminiApiKey)
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
        return hasGemini() ? "gemini" : "local";
    }

    private boolean hasGemini() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
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
                Answer only from the provided dataset context.
                If the answer is not supported by the data, clearly say that.
                Use Hinglish (Hindi + English mix) and keep answers practical and crisp.
                Prefer markdown with short sections or bullets when helpful.
                Mention exact columns used whenever useful.

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
