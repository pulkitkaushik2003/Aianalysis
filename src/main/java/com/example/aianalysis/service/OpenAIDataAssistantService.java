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
public class OpenAIDataAssistantService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4.1-mini}")
    private String openAiModel;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    public OpenAIDataAssistantService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public boolean isConfigured() {
        return hasOpenAi();
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
            throw new IllegalStateException("OPENAI_API_KEY configured nahi hai.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", openAiModel);
        payload.put("max_output_tokens", 900);
        payload.put("instructions", buildSystemPrompt(data, headers, fileName, rowCount, colCount, issueCount, cleanRows));
        payload.put("input", question);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiBaseUrl + "/responses"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + openAiApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }

        return extractAnswer(response.body());
    }

    public String activeProvider() {
        return hasOpenAi() ? "openai" : "local";
    }

    private boolean hasOpenAi() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private String buildSystemPrompt(List<Map<String, Object>> data,
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
                If helpful, mention exact columns used.

                Dataset info:
                - File: %s
                - Rows: %s
                - Columns: %s
                - Clean rows: %s
                - Issues: %s
                - Headers: %s

                Sample rows JSON:
                %s
                """.formatted(
                fileName == null ? "uploaded_file" : fileName,
                rowCount == null ? data.size() : rowCount,
                colCount == null ? headers.size() : colCount,
                cleanRows == null ? "N/A" : cleanRows,
                issueCount == null ? "N/A" : issueCount,
                String.join(", ", headers),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample)
        );
    }

    private String extractAnswer(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        JsonNode outputText = root.path("output_text");
        if (outputText.isTextual() && !outputText.asText().isBlank()) {
            return outputText.asText();
        }

        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder answer = new StringBuilder();

            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }

                for (JsonNode part : content) {
                    JsonNode textNode = part.path("text");
                    if (textNode.isTextual() && !textNode.asText().isBlank()) {
                        if (!answer.isEmpty()) {
                            answer.append("\n\n");
                        }
                        answer.append(textNode.asText());
                    }
                }
            }

            if (!answer.isEmpty()) {
                return answer.toString();
            }
        }

        throw new IllegalStateException("OpenAI response se text extract nahi hua.");
    }
}
