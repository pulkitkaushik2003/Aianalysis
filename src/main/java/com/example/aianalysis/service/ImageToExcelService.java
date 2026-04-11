package com.example.aianalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ImageToExcelService {

    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp");
    private static final String DEFAULT_FILE_NAME = "extracted_data.xlsx";
    private static final String EXTRACTION_PROMPT = """
            Extract the main table from this image.
            Return JSON with exactly two keys:
            - headers: array of column names in left-to-right order.
            - rows: array of rows, where each row is an array of cell strings matching the header order.
            Rules:
            - Preserve every visible row and column exactly as shown.
            - Keep values as strings and preserve dates, currency, percentages, and text as visible.
            - If no headers are visible, create Col1, Col2, Col3 and so on.
            - If multiple tables exist, use the most prominent table.
            - If no readable table exists, return {"headers":[],"rows":[]}.
            - Return valid JSON only, with no markdown or explanation.
            """;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${gemini.api.key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}")
    private String geminiApiKey;

    @Value("${gemini.image.model:${gemini.model:gemini-2.5-flash-lite}}")
    private String geminiModel;

    @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl;

    public ImageToExcelService(ObjectMapper objectMapper,
                               RestTemplateBuilder restTemplateBuilder) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(20))
                .setReadTimeout(Duration.ofSeconds(90))
                .build();
    }

    public ExtractionResult extractTableData(MultipartFile image) throws IOException {
        validateImage(image);

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new ImageExtractionException("GEMINI_API_KEY configure nahi hai.");
        }

        String mediaType = resolveMediaType(image);
        String base64Image = Base64.getEncoder().encodeToString(image.getBytes());
        GeminiTableResponse extractedTable = callGeminiVision(base64Image, mediaType);

        if (extractedTable.rows().isEmpty()) {
            throw new ImageExtractionException(
                    "Image mein readable table data nahi mila. Clear table ya spreadsheet image upload karo.");
        }

        List<String> headers = normalizeHeaders(extractedTable.headers(), extractedTable.rows());
        if (headers.isEmpty()) {
            throw new ImageExtractionException(
                    "Table headers identify nahi ho paaye. Thoda clearer image try karo.");
        }

        List<Map<String, Object>> normalizedRows = normalizeStructuredRows(extractedTable.rows(), headers);
        return new ExtractionResult(headers, normalizedRows);
    }

    public byte[] generateExcel(List<String> headers,
                                List<Map<String, Object>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Extracted Data");

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFillForegroundColor(
                    new XSSFColor(new Color(56, 189, 248), new DefaultIndexedColorMap()));

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor((short) 0);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Map<String, Object> sourceRow = rows.get(rowIndex);
                Row excelRow = sheet.createRow(rowIndex + 1);
                for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                    String header = headers.get(columnIndex);
                    Object value = sourceRow.get(header);
                    excelRow.createCell(columnIndex)
                            .setCellValue(value == null ? "" : String.valueOf(value));
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public String excelFileName() {
        return DEFAULT_FILE_NAME;
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new ImageExtractionException("Pehle image select karo.");
        }

        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new ImageExtractionException("Image 10MB se badi hai. Smaller file upload karo.");
        }

        String extension = fileExtension(image.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ImageExtractionException("Sirf JPG, JPEG, PNG, ya WEBP image allowed hai.");
        }
    }

    private GeminiTableResponse callGeminiVision(String base64Image, String mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiApiKey);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("inline_data", Map.of(
                "mime_type", mediaType,
                "data", base64Image
        ));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", EXTRACTION_PROMPT);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseJsonSchema", buildTableSchema());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(Map.of(
                "parts", List.of(imagePart, textPart)
        )));
        requestBody.put("generationConfig", generationConfig);

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    geminiBaseUrl + "/models/" + geminiModel + ":generateContent",
                    HttpMethod.POST, entity, String.class);
            return parseGeminiTableResponse(response.getBody());
        } catch (RestClientResponseException ex) {
            throw new ImageExtractionException(
                    "Gemini API error: " + extractApiErrorMessage(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new ImageExtractionException(
                    "Gemini API se response nahi aaya. Thodi der baad dobara try karo.", ex);
        } catch (IOException ex) {
            throw new ImageExtractionException(
                    "Gemini response parse nahi ho paaya. Thoda clearer image try karo.", ex);
        }
    }

    private GeminiTableResponse parseGeminiTableResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String rawText = extractTextFromGeminiResponse(root);
        String normalized = rawText == null ? "" : rawText.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```json\\s*", "")
                    .replaceFirst("^```\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        if (normalized.isBlank()) {
            return new GeminiTableResponse(List.of(), List.of());
        }

        try {
            return objectMapper.readValue(normalized, new TypeReference<GeminiTableResponse>() {});
        } catch (Exception ex) {
            throw new ImageExtractionException(
                    "Gemini structured table response parse nahi ho paaya. Thoda clearer image try karo.", ex);
        }
    }

    private String extractTextFromGeminiResponse(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray()) {
            StringBuilder builder = new StringBuilder();

            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (!parts.isArray()) {
                    continue;
                }

                for (JsonNode part : parts) {
                    JsonNode textNode = part.path("text");
                    if (textNode.isTextual() && !textNode.asText().isBlank()) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(textNode.asText().trim());
                    }
                }
            }

            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }

        JsonNode errorMessage = root.path("error").path("message");
        if (errorMessage.isTextual() && !errorMessage.asText().isBlank()) {
            throw new ImageExtractionException("Gemini API error: " + errorMessage.asText());
        }

        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
            throw new ImageExtractionException("Gemini request blocked hui: " + blockReason.asText());
        }

        throw new ImageExtractionException("Gemini response se table text nahi mila.");
    }

    private List<LinkedHashMap<String, Object>> parseExtractedJson(String rawText) throws IOException {
        String normalized = rawText == null ? "" : rawText.trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```json\\s*", "")
                    .replaceFirst("^```\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }

        if (!normalized.startsWith("[")) {
            int arrayStart = normalized.indexOf('[');
            int arrayEnd = normalized.lastIndexOf(']');
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                normalized = normalized.substring(arrayStart, arrayEnd + 1);
            }
        }

        if ("[]".equals(normalized)) {
            return List.of();
        }

        try {
            return objectMapper.readValue(
                    normalized,
                    new TypeReference<List<LinkedHashMap<String, Object>>>() {});
        } catch (Exception ex) {
            if (looksUnreadableResponse(normalized)) {
                throw new ImageExtractionException(
                        "Image blurry ya unreadable lag rahi hai. Better lighting ya sharper image try karo.", ex);
            }

            try {
                JsonNode root = objectMapper.readTree(normalized);
                JsonNode rowsNode = root.path("rows");
                if (rowsNode.isArray()) {
                    return objectMapper.convertValue(
                            rowsNode,
                            new TypeReference<List<LinkedHashMap<String, Object>>>() {});
                }
            } catch (Exception ignored) {
                // Response was not valid JSON at all. Fall through to the user-facing message below.
            }

            if (normalized.contains("[") && normalized.contains("]")) {
                throw new ImageExtractionException(
                        "Extracted response valid table JSON mein convert nahi ho paaya.", ex);
            }

            throw new ImageExtractionException(
                    "Image mein table detect nahi hua. Clear table ya spreadsheet image try karo.", ex);
        }
    }

    private boolean looksUnreadableResponse(String rawText) {
        String lower = rawText.toLowerCase(Locale.ROOT);
        return lower.contains("blurry")
                || lower.contains("unclear")
                || lower.contains("unreadable")
                || lower.contains("not legible")
                || lower.contains("cannot read");
    }

    private List<String> collectHeaders(List<LinkedHashMap<String, Object>> rows) {
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                String normalizedKey = key == null ? "" : key.trim();
                if (!normalizedKey.isBlank()) {
                    headers.add(normalizedKey);
                }
            }
        }
        return new ArrayList<>(headers);
    }

    private List<Map<String, Object>> normalizeRows(List<LinkedHashMap<String, Object>> rows,
                                                    List<String> headers) {
        List<Map<String, Object>> normalizedRows = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            LinkedHashMap<String, Object> normalizedRow = new LinkedHashMap<>();
            for (String header : headers) {
                Object value = findValueForHeader(row, header);
                normalizedRow.put(header, value == null ? "" : String.valueOf(value).trim());
            }
            normalizedRows.add(normalizedRow);
        }

        return normalizedRows;
    }

    private Object findValueForHeader(Map<String, Object> row, String header) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.equals(header)) {
                return entry.getValue();
            }
        }
        return row.get(header);
    }

    private Map<String, Object> buildTableSchema() {
        Map<String, Object> rowSchema = new LinkedHashMap<>();
        rowSchema.put("type", "array");
        rowSchema.put("items", Map.of("type", "string"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "headers", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string")
                ),
                "rows", Map.of(
                        "type", "array",
                        "items", rowSchema
                )
        ));
        schema.put("required", List.of("headers", "rows"));
        return schema;
    }

    private List<String> normalizeHeaders(List<String> rawHeaders,
                                          List<List<String>> rawRows) {
        int widestRow = rawRows.stream()
                .mapToInt(row -> row == null ? 0 : row.size())
                .max()
                .orElse(0);

        List<String> seedHeaders = rawHeaders == null ? List.of() : rawHeaders;
        int columnCount = Math.max(seedHeaders.size(), widestRow);
        if (columnCount == 0) {
            return List.of();
        }

        List<String> prepared = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            String header = i < seedHeaders.size() ? normalizeCell(seedHeaders.get(i)) : "";
            prepared.add(header.isBlank() ? "Col" + (i + 1) : header);
        }

        return deduplicateHeaders(prepared);
    }

    private List<Map<String, Object>> normalizeStructuredRows(List<List<String>> rows,
                                                              List<String> headers) {
        List<Map<String, Object>> normalizedRows = new ArrayList<>();

        for (List<String> row : rows) {
            LinkedHashMap<String, Object> normalizedRow = new LinkedHashMap<>();
            List<String> safeRow = row == null ? List.of() : row;
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                String header = headers.get(columnIndex);
                String value = columnIndex < safeRow.size() ? normalizeCell(safeRow.get(columnIndex)) : "";
                normalizedRow.put(header, value);
            }
            normalizedRows.add(normalizedRow);
        }

        return normalizedRows;
    }

    private List<String> deduplicateHeaders(List<String> rawHeaders) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> headers = new ArrayList<>();

        for (int i = 0; i < rawHeaders.size(); i++) {
            String base = normalizeCell(rawHeaders.get(i));
            if (base.isBlank()) {
                base = "Col" + (i + 1);
            }

            String candidate = base;
            int suffix = 2;
            while (seen.contains(candidate.toLowerCase(Locale.ROOT))) {
                candidate = base + "_" + suffix++;
            }
            seen.add(candidate.toLowerCase(Locale.ROOT));
            headers.add(candidate);
        }

        return headers;
    }

    private String normalizeCell(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String resolveMediaType(MultipartFile image) {
        String extension = fileExtension(image.getOriginalFilename());
        return switch (extension) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "webp" -> "image/webp";
            default -> {
                String contentType = image.getContentType();
                yield (contentType == null || contentType.isBlank())
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                        : contentType;
            }
        };
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
            // Return raw response below if it is not JSON.
        }

        return responseBody;
    }

    private String fileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
    }

    public record ExtractionResult(List<String> headers, List<Map<String, Object>> rows) {}

    private record GeminiTableResponse(List<String> headers, List<List<String>> rows) {}

    public static class ImageExtractionException extends RuntimeException {
        public ImageExtractionException(String message) {
            super(message);
        }

        public ImageExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
