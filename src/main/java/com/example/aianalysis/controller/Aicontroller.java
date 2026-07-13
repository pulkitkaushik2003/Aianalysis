package com.example.aianalysis.controller;

import com.example.aianalysis.Model.Contact;
import com.example.aianalysis.Model.UploadedDataset;
import com.example.aianalysis.Model.UserData;
import com.example.aianalysis.Repo.AiContactUs;
import com.example.aianalysis.Repo.AianalysisRepo;
import com.example.aianalysis.service.AiUserDataService;
import com.example.aianalysis.service.DataCleaningService;
import com.example.aianalysis.service.DataCleaningService.CleaningResult;
import com.example.aianalysis.service.DataCleaningService.StreamingCleanResult;
import com.example.aianalysis.service.FileParserService;
import com.example.aianalysis.service.FileParserService.ParseMetadata;
import com.example.aianalysis.service.GeminiDataAssistantService;
import com.example.aianalysis.service.ImageToExcelService;
import com.example.aianalysis.service.ReportService;
import com.example.aianalysis.service.UploadedDatasetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

@Controller
@RequestMapping
public class Aicontroller {

    private static final long LARGE_FILE_THRESHOLD_BYTES = 5L * 1024 * 1024;
    private static final int  DASHBOARD_CHUNK_SIZE       = 100;
    private static final int  AI_SAMPLE_LIMIT            = 120;
    private static final int  CHART_PREVIEW_LIMIT        = 200;
    private static final int  CHART_MAX_DISPLAY_CATEGORIES = 250;
    private static final int  SCATTER_POINT_LIMIT        = 1500;
    private static final int  PIE_MAX_SLICES             = 10;
    private static final String IMAGE_EXTRACTED_DATA_SESSION_KEY = "extractedImageData";
    private static final String IMAGE_EXTRACTED_HEADERS_SESSION_KEY = "extractedHeaders";
    private static final String IMAGE_EXTRACTED_FILE_NAME_SESSION_KEY = "extractedImageFileName";
    private static final DateTimeFormatter MONTH_YEAR_LABEL_FORMATTER =
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("MMM-")
                    .appendValueReduced(ChronoField.YEAR, 2, 4, 2000)
                    .toFormatter(Locale.ENGLISH);

    // Large file chart ke liye kitni rows ek baar padhni hain
    private static final int  CHART_STREAM_CHUNK         = 5000;

    @Autowired private AiUserDataService              userService;
    @Autowired private AianalysisRepo                 userRepo;
    @Autowired private com.example.aianalysis.service.PasswordResetService passwordResetService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private AiContactUs                   contactRepo;
    @Autowired private FileParserService             fileParserService;
    @Autowired private DataCleaningService           dataCleaningService;
    @Autowired private GeminiDataAssistantService    geminiDataAssistantService;
    @Autowired private ImageToExcelService           imageToExcelService;
    @Autowired private ReportService                 reportService;
    @Autowired private UploadedDatasetService        uploadedDatasetService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 1. BASIC PAGES ───────────────────────────────────────

    @GetMapping("/")
    public String home() { return "index"; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/forgot-password")
    public String forgotPassword(Model model) {
        model.addAttribute("email", "");
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String handleForgotPassword(@RequestParam("email") String email,
                                       RedirectAttributes redirectAttributes,
                                       jakarta.servlet.http.HttpServletRequest request) {

        if (email == null || email.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please enter your email.");
            return "redirect:/forgot-password";
        }

        var result = passwordResetService.createAndSendToken(email, request);

        if (result.mailSent()) {
    redirectAttributes.addFlashAttribute("successMessage", "Reset link sent to your email.");
    return "redirect:/login";
} else {
    // Security ke liye same message (email exist kare ya nahi)
    redirectAttributes.addFlashAttribute("successMessage",
        "If the email exists, a reset link has been sent.");
    return "redirect:/forgot-password";
}
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(value = "token", required = false) String token,
                                    Model model,
                                    RedirectAttributes redirectAttributes) {
        if (token == null || token.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid or expired token.");
            return "redirect:/login";
        }

        var userOpt = passwordResetService.validateToken(token);
        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid or expired token.");
            return "redirect:/login";
        }

        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String handleResetPassword(@RequestParam("token") String token,
                                      @RequestParam("password") String password,
                                      RedirectAttributes redirectAttributes) {

        var userOpt = passwordResetService.validateToken(token);
        if (userOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid or expired token.");
            return "redirect:/login";
        }

        UserData user = userOpt.get();
        user.setPassword(passwordEncoder.encode(password));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepo.save(user);

        redirectAttributes.addFlashAttribute("successMessage", "Password reset successfully. Please login.");
        return "redirect:/login";
    }

    @GetMapping("/about")
    public String about() { return "about"; }

    @GetMapping({"/services", "/service"})
    public String services() { return "service"; }

    @GetMapping("/signup")
    public String signup(Model model) {
        model.addAttribute("user", new UserData());
        return "signup";
    }

    @PostMapping("/signup")
    public String saveUser(@ModelAttribute("user") UserData userData, Model model) {
        try {
            userService.registerUser(userData);
            return "redirect:/login";
        } catch (RuntimeException ex) {
            model.addAttribute("error", userFacingError(ex,
                    "Account create nahi ho paaya. Thodi der baad dobara try karo."));
            model.addAttribute("user", userData);
            return "signup";
        }
    }

    @GetMapping("/contact")
    public String contact(Model model) {
        model.addAttribute("contact", new Contact());
        return "Contact";
    }

    @PostMapping("/ContactUs")
    public String saveContact(@ModelAttribute Contact contact,
                              RedirectAttributes redirectAttributes) {
        try {
            contactRepo.save(contact);
            redirectAttributes.addFlashAttribute("successMessage", "Message save ho gaya.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    userFacingError(ex, "Contact save nahi hua. Please thodi der baad dobara try karo."));
        }
        return "redirect:/contact";
    }

    // ── 2. UPLOAD ────────────────────────────────────────────

    @GetMapping("/upload")
    public String uploadPage() { return "Upload"; }

    @PostMapping("/dataupload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "mode", defaultValue = "private") String mode,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "File empty hai. Pehle file select karo.");
            return "redirect:/upload";
        }

        clearUploadState(session);

        try {
            boolean largeMode = shouldUseLargeFileMode(file);

            if (largeMode) {
                ParseMetadata metadata = fileParserService.parseMetadata(file);
                List<Map<String, Object>> sampleData = metadata.sampleData();
                CleaningResult sampleAnalysis = dataCleaningService.analyze(new ArrayList<>(sampleData));
                storeLargeUploadState(session, file.getOriginalFilename(), mode, metadata, sampleAnalysis);

                if ("normal".equalsIgnoreCase(mode)) {
                    UploadedDataset dataset = uploadedDatasetService.saveMetadataOnly(
                            file.getOriginalFilename(),
                            metadata.filePath(), mode,
                            Math.toIntExact(metadata.totalRows()),
                            metadata.headers().size(),
                            sampleAnalysis.cleanRows,
                            sampleAnalysis.issueCount,
                            sampleAnalysis.missingCount,
                            sampleAnalysis.duplicateCount,
                            sampleAnalysis.outlierCount,
                            metadata.headers());
                    session.setAttribute("datasetId", dataset.getId());
                }
            } else {
                List<Map<String, Object>> rawData = fileParserService.parse(file);
                if (rawData == null || rawData.isEmpty()) {
                    redirectAttributes.addFlashAttribute("errorMessage",
                            "File mein koi data nahi mila.");
                    return "redirect:/upload";
                }

                CleaningResult result = dataCleaningService.analyze(rawData);
                List<String> headers = fileParserService.extractHeaders(result.data);

                session.setAttribute("largeFileMode",  false);
                session.setAttribute("uploadedData",   result.data);
                session.setAttribute("headers",        headers);
                session.setAttribute("fileName",       file.getOriginalFilename());
                session.setAttribute("rowCount",       result.totalRows);
                session.setAttribute("colCount",       headers.size());
                session.setAttribute("cleanRows",      result.cleanRows);
                session.setAttribute("issueCount",     result.issueCount);
                session.setAttribute("missingCount",   result.missingCount);
                session.setAttribute("duplicateCount", result.duplicateCount);
                session.setAttribute("outlierCount",   result.outlierCount);
                session.setAttribute("cleaningStatsFull", true);

                if ("normal".equalsIgnoreCase(mode)) {
                    UploadedDataset dataset = uploadedDatasetService.saveUploadedData(
                            file.getOriginalFilename(), mode,
                            result.totalRows, headers.size(),
                            result.cleanRows, result.issueCount,
                            result.missingCount, result.duplicateCount,
                            result.outlierCount, headers, result.data);
                    session.setAttribute("datasetId", dataset.getId());
                }
            }

            session.setAttribute("uploadMode", mode);
            return "redirect:/dashboard";

        } catch (Exception ex) {
            ex.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage",
                    userFacingError(ex, "Upload failed. Please file ya database settings check karo."));
            return "redirect:/upload";
        }
    }

    // ── 3. DASHBOARD ─────────────────────────────────────────

    @GetMapping("/image-to-excel")
    public String imageToExcelPage(HttpSession session, Model model) {
        List<Map<String, Object>> previewRows = getImageExtractedData(session);
        List<String> headers = getImageExtractedHeaders(session, previewRows);

        model.addAttribute("previewRows", previewRows);
        model.addAttribute("previewHeaders", headers);
        model.addAttribute("previewRowCount", previewRows.size());
        model.addAttribute("previewFileName",
                session.getAttribute(IMAGE_EXTRACTED_FILE_NAME_SESSION_KEY));
        model.addAttribute("hasPreview", !previewRows.isEmpty());

        try {
            model.addAttribute("previewRowsJson", objectMapper.writeValueAsString(previewRows));
            model.addAttribute("previewHeadersJson", objectMapper.writeValueAsString(headers));
        } catch (Exception ex) {
            model.addAttribute("previewRowsJson", "[]");
            model.addAttribute("previewHeadersJson", "[]");
        }

        return "image-to-excel";
    }

    @PostMapping({"/image-to-excel/preview", "/image-to-excel/extract"})
    @ResponseBody
    public Map<String, Object> previewImageToExcel(@RequestParam("image") MultipartFile image,
                                                   HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        clearImageExtractionState(session);

        try {
            ImageToExcelService.ExtractionResult result = imageToExcelService.extractTableData(image);

            session.setAttribute(IMAGE_EXTRACTED_DATA_SESSION_KEY, result.rows());
            session.setAttribute(IMAGE_EXTRACTED_HEADERS_SESSION_KEY, result.headers());
            session.setAttribute(IMAGE_EXTRACTED_FILE_NAME_SESSION_KEY, image.getOriginalFilename());

            response.put("success", true);
            response.put("message", "Image se table data extract ho gaya.");
            response.put("headers", result.headers());
            response.put("data", result.rows());
            response.put("rowCount", result.rows().size());
            response.put("fileName", image.getOriginalFilename());
            response.put("downloadUrl", "/image-to-excel/download");
            return response;
        } catch (ImageToExcelService.ImageExtractionException ex) {
            response.put("success", false);
            response.put("message", ex.getMessage());
            return response;
        } catch (Exception ex) {
            ex.printStackTrace();
            response.put("success", false);
            response.put("message",
                    userFacingError(ex, "Image process nahi ho paayi. Thodi der baad dobara try karo."));
            return response;
        }
    }

    @GetMapping("/image-to-excel/download")
    public ResponseEntity<byte[]> downloadImageToExcel(HttpSession session) {
        List<Map<String, Object>> rows = getImageExtractedData(session);
        List<String> headers = getImageExtractedHeaders(session, rows);

        if (rows.isEmpty() || headers.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            byte[] excelBytes = imageToExcelService.generateExcel(headers, rows);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + imageToExcelService.excelFileName() + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(defaultValue = "1")  int page,
                            @RequestParam(defaultValue = "50") int size,
                            HttpSession session,
                            Model model) {

        List<Map<String, Object>> previewData = getSessionData(session);
        List<String> headers = getHeaders(session, previewData);
        int safeSize = size > 0 ? size : DASHBOARD_CHUNK_SIZE;
        String filePath = resolveStreamFilePath(session);
        boolean streamAvailable = filePath != null
                && !filePath.isBlank()
                && !headers.isEmpty();

        if (!streamAvailable && previewData.isEmpty()) {
            model.addAttribute("rowCount", 0);
            return "dashboard";
        }

        if (streamAvailable) {
            int totalRows = Math.max(getRowCount(session), previewData.size());
            int totalPages  = Math.max(1, (int) Math.ceil((double) Math.max(totalRows, 1) / safeSize));
            int currentPage = Math.max(1, Math.min(page, totalPages));

            List<Map<String, Object>> chunkData = List.of();
            try {
                chunkData = fileParserService.getDataChunk(
                        filePath, currentPage - 1, safeSize, headers);
            } catch (Exception ex) {
                ex.printStackTrace();
                chunkData = previewData;
            }

            if (totalRows == 0 && !chunkData.isEmpty()) {
                totalRows = chunkData.size();
                session.setAttribute("rowCount", totalRows);
            }

            populateDashboardModel(model, session, chunkData, headers,
                    currentPage, totalPages, safeSize);
            model.addAttribute("supportsChunking", true);
            model.addAttribute("taskId", getChunkRef(session));
            return "dashboard";
        }

        int totalRows   = previewData.size();
        int totalPages  = Math.max(1, (int) Math.ceil((double) totalRows / safeSize));
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int fromIndex   = (currentPage - 1) * safeSize;
        int toIndex     = Math.min(fromIndex + safeSize, totalRows);

        populateDashboardModel(model, session,
                previewData.subList(fromIndex, toIndex),
                headers, currentPage, totalPages, safeSize);
        model.addAttribute("supportsChunking", false);
        model.addAttribute("taskId", null);
        return "dashboard";
    }

    private void populateDashboardModel(Model model, HttpSession session,
                                        List<Map<String, Object>> pageData,
                                        List<String> headers,
                                        int currentPage, int totalPages, int pageSize) {
        model.addAttribute("data",           pageData);
        model.addAttribute("headers",        headers);
        model.addAttribute("fileName",       session.getAttribute("fileName"));
        model.addAttribute("rowCount",       session.getAttribute("rowCount"));
        model.addAttribute("colCount",       session.getAttribute("colCount"));
        model.addAttribute("cleanRows",      session.getAttribute("cleanRows"));
        model.addAttribute("issueCount",     session.getAttribute("issueCount"));
        model.addAttribute("missingCount",   session.getAttribute("missingCount"));
        model.addAttribute("duplicateCount", session.getAttribute("duplicateCount"));
        model.addAttribute("outlierCount",   session.getAttribute("outlierCount"));
        model.addAttribute("currentPage",    currentPage);
        model.addAttribute("totalPages",     totalPages);
        model.addAttribute("pageSize",       pageSize);
    }

    // ── 4. DATA CHUNK API ────────────────────────────────────

    @GetMapping("/data/chunk")
    @ResponseBody
    public List<Map<String, Object>> getDataChunk(
            @RequestParam(required = false) String taskId,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "100") int size,
            HttpSession session) {
        try {
            List<String> headers = getHeaders(session, List.of());
            String filePath = resolveStreamFilePath(session);

            if ((filePath == null || filePath.isBlank())
                    && taskId != null && !taskId.isBlank()) {
                filePath = uploadedDatasetService.findById(taskId)
                        .map(UploadedDataset::getFilePath)
                        .orElse(null);
            }

            if (filePath != null && !filePath.isBlank() && !headers.isEmpty()) {
                return fileParserService.getDataChunk(filePath, page, size, headers);
            }

            List<Map<String, Object>> allData = getSessionData(session);
            int start = Math.max(0, page) * Math.max(1, size);
            if (start >= allData.size()) return List.of();
            int end = Math.min(start + Math.max(1, size), allData.size());
            return allData.subList(start, end);

        } catch (Exception ex) {
            ex.printStackTrace();
            return List.of();
        }
    }

    // ── 5. CHARTS ─────────────────────────────────────────────
    // ✅ FIX: Large file ke liye poori file stream karke aggregate karo

    @GetMapping("/charts")
    public String chartsPage(HttpSession session, Model model) {

        List<String> headers = getHeaders(session, List.of());
        List<Map<String, Object>> previewData = getChartPreviewData(session, headers);
        List<String> numericHeaders = detectNumericHeaders(headers, previewData);
        List<Map<String, Object>> schemaProfiles = buildSchemaProfiles(headers, previewData);
        List<Map<String, Object>> chartRecommendations = buildSmartChartRecommendations(schemaProfiles);
        String chartScope = isLargeFileMode(session)
                ? "server-aggregated"
                : (getSessionData(session).size() > CHART_PREVIEW_LIMIT ? "preview" : "full");
        int totalRows = getRowCount(session);

        if (headers.isEmpty()) {
            model.addAttribute("headers",   null);
            model.addAttribute("numericHeaders", List.of());
            model.addAttribute("schemaProfiles", "[]");
            model.addAttribute("chartRecommendations", "[]");
            model.addAttribute("chartData", "[]");
            model.addAttribute("fileName",  null);
            model.addAttribute("chartScope", "empty");
            model.addAttribute("totalRows",  0);
            model.addAttribute("chartRows",  0);
            return "charts";
        }

        String chartDataJson = "[]";
        String schemaProfilesJson = "[]";
        String chartRecommendationsJson = "[]";
        try {
            chartDataJson = objectMapper.writeValueAsString(previewData);
            schemaProfilesJson = objectMapper.writeValueAsString(schemaProfiles);
            chartRecommendationsJson = objectMapper.writeValueAsString(chartRecommendations);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        model.addAttribute("headers",       headers.isEmpty() ? null : headers);
        model.addAttribute("numericHeaders", numericHeaders);
        model.addAttribute("schemaProfiles", schemaProfilesJson);
        model.addAttribute("chartRecommendations", chartRecommendationsJson);
        model.addAttribute("chartData",     chartDataJson);
        model.addAttribute("fileName",      session.getAttribute("fileName"));
        model.addAttribute("largeFileMode", isLargeFileMode(session));
        model.addAttribute("chartScope",    chartScope);
        model.addAttribute("totalRows",     totalRows);
        model.addAttribute("chartRows",     previewData.size());

        return "charts";
    }

    // ✅ Stream + aggregate karo — poori file read karo chunk by chunk
    @GetMapping("/charts/data")
    @ResponseBody
    public Map<String, Object> getChartData(
            @RequestParam String xAxis,
            @RequestParam(required = false) String yAxis,
            @RequestParam(defaultValue = "bar") String chartType,
            @RequestParam(defaultValue = "sum") String aggregation,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "auto") String sort,
            HttpSession session) {
        try {
            List<String> headers = getHeaders(session, List.of());
            if (headers.isEmpty()) {
                return chartError("Pehle data upload karo, tab chart generate hoga.");
            }

            List<Map<String, Object>> previewData = getChartPreviewData(session, headers);
            List<String> numericHeaders = detectNumericHeaders(headers, previewData);
            List<String> temporalHeaders = detectTemporalHeaders(headers, previewData);
            if (!headers.contains(xAxis)) {
                return chartError("Selected X-axis column available nahi hai.");
            }

            String safeChartType = chartType == null ? "bar" : chartType.toLowerCase(Locale.ROOT);
            String safeAggregation = aggregation == null ? "sum" : aggregation.toLowerCase(Locale.ROOT);
            String safeYAxis = yAxis;

            if (!"count".equals(safeAggregation)) {
                if (safeYAxis == null || safeYAxis.isBlank() || !numericHeaders.contains(safeYAxis)) {
                    safeYAxis = numericHeaders.isEmpty() ? null : numericHeaders.get(0);
                }
                if (safeYAxis == null) {
                    return chartError("Numeric Y-axis column nahi mila.");
                }
            }

            if ("scatter".equals(safeChartType)) {
                return buildScatterChartData(session, xAxis, safeYAxis, numericHeaders);
            }

            return buildGroupedChartData(
                    session, xAxis, safeYAxis, safeChartType,
                    safeAggregation, limit, sort, numericHeaders, temporalHeaders
            );
        } catch (Exception ex) {
            ex.printStackTrace();
            return chartError(userFacingError(ex, "Chart data load nahi hui."));
        }
    }

    @GetMapping("/charts/pie-data")
    @ResponseBody
    public Map<String, Object> getPieChartData(
            @RequestParam List<String> columns,
            @RequestParam(defaultValue = "separate") String mode,
            @RequestParam(defaultValue = "12") int limit,
            HttpSession session) {
        try {
            List<String> headers = getHeaders(session, List.of());
            if (headers.isEmpty()) {
                return chartError("Pehle data upload karo, tab pie chart generate hoga.");
            }

            List<String> validColumns = columns == null
                    ? List.of()
                    : columns.stream().filter(headers::contains).distinct().toList();
            if (validColumns.isEmpty()) {
                return chartError("Pie chart ke liye kam se kam ek valid column select karo.");
            }

            return buildPieChartData(session, validColumns, mode, limit);
        } catch (Exception ex) {
            ex.printStackTrace();
            return chartError(userFacingError(ex, "Pie chart data load nahi hui."));
        }
    }

    @PostMapping("/charts/save")
    @ResponseBody
    public Map<String, Object> saveChartForReport(@RequestBody Map<String, Object> payload,
                                                  HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            response.put("success", false);
            response.put("message", "Chart payload missing hai.");
            return response;
        }

        String imageData = payloadText(payload, "imageData");
        String title = payloadText(payload, "title");
        String chartType = firstNonBlank(payloadText(payload, "chartType"), payloadText(payload, "type"));
        String id = payloadText(payload, "id");

        if (imageData.isBlank() && title.isBlank()) {
            response.put("success", false);
            response.put("message", "Save karne ke liye chart data nahi mila.");
            return response;
        }

        String configJson = "";
        try {
            Map<String, Object> compactConfig = new LinkedHashMap<>(payload);
            compactConfig.remove("imageData");
            configJson = objectMapper.writeValueAsString(compactConfig);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        boolean saved = reportService.saveChart(session.getId(),
                new ReportService.ChartEntry(id, title, chartType, configJson, imageData, System.currentTimeMillis()));

        ReportService.Report report = reportService.getReport(session.getId()).orElse(new ReportService.Report());
        response.put("success", true);
        response.put("saved", saved);
        response.put("chartCount", report.getCharts().size());
        response.put("chatCount", report.getChats().size());
        response.put("message", saved ? "Chart report me save ho gaya." : "Chart pehle se report me saved hai.");
        return response;
    }

    private List<Map<String, Object>> streamAndAggregate(
            String filePath,
            List<String> headers,
            int totalRows) {

        if (filePath == null || filePath.isBlank()) return List.of();

        // Group key = pehla column
        String groupKey = headers.get(0);

        // Numeric columns find karo
        List<String> numericCols = new ArrayList<>();
        try {
            List<Map<String, Object>> sample =
                    fileParserService.getDataChunk(filePath, 0, 200, headers);
            numericCols = headers.stream()
                    .filter(h -> !h.equals(groupKey))
                    .filter(h -> isNumericCol(sample, h))
                    .toList();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Aggregation map
        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();
        List<String> finalNumericCols = numericCols;

        try {
            fileParserService.streamFile(filePath, row -> {
                String key = String.valueOf(
                        row.getOrDefault(groupKey, "Unknown")).trim();
                if (key.isEmpty() || "null".equals(key)) key = "Unknown";

                grouped.computeIfAbsent(key, k -> new LinkedHashMap<>());
                Map<String, Double> sums = grouped.get(key);

                for (String col : finalNumericCols) {
                    Object val = row.get(col);
                    if (val == null) continue;
                    try {
                        double num = Double.parseDouble(
                                String.valueOf(val).replaceAll("[^\\d.-]", ""));
                        if (!Double.isNaN(num) && !Double.isInfinite(num)) {
                            sums.merge(col, num, Double::sum);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Result list
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(groupKey, entry.getKey());
            entry.getValue().forEach(row::put);
            result.add(row);
        }

        return result;
    }

    // ── Aggregate session data ────────────────────────────────
    private List<Map<String, Object>> aggregateForChart(
            List<Map<String, Object>> data,
            List<String> headers) {

        if (data.isEmpty() || headers.isEmpty()) return List.of();

        String groupKey = headers.get(0);
        List<String> numericCols = headers.stream()
                .filter(h -> !h.equals(groupKey))
                .filter(h -> isNumericCol(data, h))
                .toList();

        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();

        for (Map<String, Object> row : data) {
            String issue = String.valueOf(row.getOrDefault("_issue", ""));
            if ("duplicate".equals(issue)) continue;

            String key = String.valueOf(row.getOrDefault(groupKey, "Unknown")).trim();
            if (key.isEmpty() || "null".equals(key)) key = "Unknown";

            grouped.computeIfAbsent(key, k -> new LinkedHashMap<>());
            Map<String, Double> sums = grouped.get(key);

            for (String col : numericCols) {
                Object val = row.get(col);
                if (val == null) continue;
                try {
                    double num = Double.parseDouble(
                            String.valueOf(val).replaceAll("[^\\d.-]", ""));
                    if (!Double.isNaN(num) && !Double.isInfinite(num)) {
                        sums.merge(col, num, Double::sum);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(groupKey, entry.getKey());
            entry.getValue().forEach(row::put);
            result.add(row);
        }
        return result;
    }

    // ── Numeric column check ─────────────────────────────────
    private boolean isNumericCol(List<Map<String, Object>> data, String col) {
        long numCount = data.stream()
                .limit(100)
                .map(r -> r.get(col))
                .map(this::parseNumericValue)
                .filter(v -> v != null)
                .count();
        return numCount > 50 || (data.size() < 100 && numCount >= Math.max(1, data.size() / 2));
    }

    // ── 6. AI ANALYSIS ───────────────────────────────────────

    private List<Map<String, Object>> getChartPreviewData(HttpSession session, List<String> headers) {
        List<Map<String, Object>> data = getSessionData(session);
        if (!data.isEmpty()) {
            return limitWithoutIssue(data, Math.min(CHART_PREVIEW_LIMIT, data.size()));
        }

        if (!isLargeFileMode(session)) {
            return List.of();
        }

        String filePath = resolveStreamFilePath(session);
        if (filePath == null || filePath.isBlank()) {
            return List.of();
        }

        try {
            return fileParserService.getDataChunk(filePath, 0, CHART_PREVIEW_LIMIT, headers);
        } catch (Exception ex) {
            ex.printStackTrace();
            return List.of();
        }
    }

    private List<String> detectNumericHeaders(List<String> headers, List<Map<String, Object>> sampleData) {
        if (headers == null || headers.isEmpty() || sampleData == null || sampleData.isEmpty()) {
            return List.of();
        }

        return headers.stream()
                .filter(header -> isNumericCol(sampleData, header))
                .toList();
    }

    private List<String> detectTemporalHeaders(List<String> headers, List<Map<String, Object>> sampleData) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }

        return buildSchemaProfiles(headers, sampleData == null ? List.of() : sampleData).stream()
                .filter(profile -> "temporal".equals(profileRole(profile)))
                .map(this::profileName)
                .toList();
    }

    private List<Map<String, Object>> buildSchemaProfiles(List<String> headers,
                                                          List<Map<String, Object>> sampleData) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }

        return headers.stream()
                .map(header -> buildSchemaProfile(header, sampleData == null ? List.of() : sampleData))
                .toList();
    }

    private Map<String, Object> buildSchemaProfile(String header,
                                                   List<Map<String, Object>> sampleData) {
        String lowerHeader = header == null ? "" : header.toLowerCase(Locale.ROOT);
        int nonBlankCount = 0;
        int numericCount = 0;
        int temporalCount = 0;
        int totalLength = 0;
        boolean hasDecimal = false;
        Set<String> distinctValues = new LinkedHashSet<>();
        List<String> sampleValues = new ArrayList<>();

        for (Map<String, Object> row : sampleData) {
            Object rawValue = row.get(header);
            String textValue = rawValue == null ? "" : String.valueOf(rawValue).trim();
            if (textValue.isBlank() || "null".equalsIgnoreCase(textValue)) {
                continue;
            }

            nonBlankCount++;
            totalLength += textValue.length();
            if (distinctValues.add(textValue) && sampleValues.size() < 4) {
                sampleValues.add(textValue);
            }

            Double numericValue = parseNumericValue(rawValue);
            if (numericValue != null) {
                numericCount++;
                if (Math.abs(numericValue - Math.rint(numericValue)) > 0.000001D) {
                    hasDecimal = true;
                }
            }

            if (looksTemporalValue(lowerHeader, textValue)) {
                temporalCount++;
            }
        }

        int distinctCount = distinctValues.size();
        double numericRatio = ratio(numericCount, nonBlankCount);
        double temporalRatio = ratio(temporalCount, nonBlankCount);
        double distinctRatio = ratio(distinctCount, nonBlankCount);
        double avgLength = nonBlankCount == 0 ? 0D : ((double) totalLength) / nonBlankCount;

        String semanticType = detectSemanticType(
                lowerHeader, nonBlankCount, numericRatio, temporalRatio,
                distinctCount, distinctRatio, avgLength, hasDecimal
        );
        String role = resolveSchemaRole(semanticType);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", header);
        profile.put("role", role);
        profile.put("semanticType", semanticType);
        profile.put("displayType", humanizeSchemaType(semanticType));
        profile.put("chartable", !"identifier".equals(semanticType)
                && !"text".equals(semanticType)
                && !"unknown".equals(semanticType));
        profile.put("sampleDistinctCount", distinctCount);
        profile.put("distinctRatio", distinctRatio);
        profile.put("numericRatio", numericRatio);
        profile.put("temporalRatio", temporalRatio);
        profile.put("sampleValues", sampleValues);
        profile.put("sampleNonBlank", nonBlankCount);
        profile.put("avgLength", avgLength);
        return profile;
    }

    private String detectSemanticType(String lowerHeader,
                                      int nonBlankCount,
                                      double numericRatio,
                                      double temporalRatio,
                                      int distinctCount,
                                      double distinctRatio,
                                      double avgLength,
                                      boolean hasDecimal) {
        if (nonBlankCount == 0) {
            return "unknown";
        }

        if ((looksTemporalHeader(lowerHeader) && (temporalRatio >= 0.35D || (numericRatio >= 0.8D && distinctCount <= 53)))
                || temporalRatio >= 0.7D) {
            return "temporal";
        }

        if (numericRatio >= 0.8D) {
            if (looksIdHeader(lowerHeader) && distinctRatio >= 0.6D) {
                return "identifier";
            }
            if (looksGeoHeader(lowerHeader)) {
                return "geo";
            }
            if (distinctCount <= 12 && !hasDecimal && distinctRatio <= 0.35D) {
                return looksTemporalHeader(lowerHeader) ? "temporal" : "category";
            }
            return "measure";
        }

        if (looksGeoHeader(lowerHeader)) {
            return "geo";
        }
        if (looksIdHeader(lowerHeader) && distinctRatio >= 0.75D && nonBlankCount >= 10) {
            return "identifier";
        }
        if (looksTextHeader(lowerHeader) || avgLength >= 35D) {
            return "text";
        }
        return "category";
    }

    private String resolveSchemaRole(String semanticType) {
        return switch (semanticType) {
            case "measure" -> "measure";
            case "temporal" -> "temporal";
            case "geo", "category" -> "dimension";
            case "identifier" -> "identifier";
            case "text" -> "text";
            default -> "dimension";
        };
    }

    private String humanizeSchemaType(String semanticType) {
        return switch (semanticType) {
            case "measure" -> "Numeric Measure";
            case "temporal" -> "Time / Date";
            case "geo" -> "Location / Geo";
            case "identifier" -> "Identifier / Code";
            case "text" -> "Long Text";
            case "unknown" -> "Unknown";
            default -> "Category";
        };
    }

    private boolean looksTemporalValue(String lowerHeader, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }

        Double numericValue = parseNumericValue(rawValue);
        if (numericValue != null && Math.abs(numericValue - Math.rint(numericValue)) < 0.000001D) {
            int integerValue = (int) Math.round(numericValue);
            if ((lowerHeader.contains("month") || lowerHeader.contains("mnth")) && integerValue >= 1 && integerValue <= 12) {
                return true;
            }
            if ((lowerHeader.contains("quarter") || lowerHeader.contains("qtr")) && integerValue >= 1 && integerValue <= 4) {
                return true;
            }
            if ((lowerHeader.contains("year") || lowerHeader.endsWith("yr")) && integerValue >= 1900 && integerValue <= 2100) {
                return true;
            }
            if (lowerHeader.contains("week") && integerValue >= 1 && integerValue <= 53) {
                return true;
            }
            if (lowerHeader.contains("day") && integerValue >= 1 && integerValue <= 31) {
                return true;
            }
        }

        String lowerValue = rawValue.toLowerCase(Locale.ROOT);
        if (lowerValue.matches(".*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*")) {
            return true;
        }

        String normalizedValue = lowerValue.replace('/', '-').replace('.', '-');
        if (normalizedValue.matches("\\d{4}-\\d{1,2}-\\d{1,2}")
                || normalizedValue.matches("\\d{1,2}-\\d{1,2}-\\d{4}")
                || normalizedValue.matches("\\d{4}-\\d{1,2}")
                || normalizedValue.matches("\\d{1,2}-\\d{4}")) {
            return true;
        }

        return looksTemporalHeader(lowerHeader) && normalizedValue.matches("\\d{4}");
    }

    private boolean looksTemporalHeader(String lowerHeader) {
        return containsAny(lowerHeader, "date", "time", "day", "week", "month", "mnth", "year", "yr", "quarter", "qtr", "period");
    }

    private boolean looksIdHeader(String lowerHeader) {
        return containsAny(lowerHeader, "id", "code", "number", "no", "roll", "reg", "registration", "invoice", "token", "sku");
    }

    private boolean looksGeoHeader(String lowerHeader) {
        return containsAny(lowerHeader, "state", "district", "city", "country", "region", "zone", "division",
                "dvsn", "station", "sttn", "location", "pin", "zip", "address");
    }

    private boolean looksTextHeader(String lowerHeader) {
        return containsAny(lowerHeader, "description", "remarks", "remark", "comment", "comments",
                "message", "summary", "note", "notes", "details");
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank()) {
            return false;
        }

        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return (double) numerator / denominator;
    }

    private List<Map<String, Object>> buildSmartChartRecommendations(List<Map<String, Object>> schemaProfiles) {
        if (schemaProfiles == null || schemaProfiles.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> measures = schemaProfiles.stream()
                .filter(profile -> "measure".equals(profileRole(profile)))
                .sorted(measureProfileComparator())
                .toList();
        List<Map<String, Object>> temporal = schemaProfiles.stream()
                .filter(profile -> "temporal".equals(profileRole(profile)))
                .sorted(temporalProfileComparator())
                .toList();
        List<Map<String, Object>> dimensions = schemaProfiles.stream()
                .filter(profile -> "dimension".equals(profileRole(profile)))
                .filter(profile -> profileDistinctCount(profile) > 1)
                .sorted(dimensionProfileComparator())
                .toList();
        List<Map<String, Object>> lowCardinalityDimensions = dimensions.stream()
                .filter(profile -> profileDistinctCount(profile) <= 7)
                .toList();

        List<Map<String, Object>> recommendations = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < Math.min(2, temporal.size()); index++) {
            Map<String, Object> timeProfile = temporal.get(index);
            String timeColumn = profileName(timeProfile);
            if (!measures.isEmpty()) {
                Map<String, Object> measureProfile = measures.get(Math.min(index, measures.size() - 1));
                String measureColumn = profileName(measureProfile);
                addRecommendation(recommendations, seen, buildRecommendation(
                        "line", "Trend", timeColumn, measureColumn, "sum", "label-asc", 25,
                        List.of(), "separate",
                        "Trend of " + measureColumn + " by " + timeColumn,
                        "Time-based data ko line chart me dekhna sabse readable hota hai."
                ));
            } else {
                addRecommendation(recommendations, seen, buildRecommendation(
                        "line", "Trend", timeColumn, null, "count", "label-asc", 25,
                        List.of(), "separate",
                        "Record count by " + timeColumn,
                        "Agar measure available na ho to time-wise record volume useful hota hai."
                ));
            }
        }

        for (int index = 0; index < Math.min(3, dimensions.size()); index++) {
            Map<String, Object> dimensionProfile = dimensions.get(index);
            String dimensionColumn = profileName(dimensionProfile);
            int limit = profileDistinctCount(dimensionProfile) <= 10 ? 10 : 25;

            if (!measures.isEmpty()) {
                String measureColumn = profileName(measures.get(0));
                addRecommendation(recommendations, seen, buildRecommendation(
                        "bar", "Compare", dimensionColumn, measureColumn, "sum", "value-desc", limit,
                        List.of(), "separate",
                        measureColumn + " by " + dimensionColumn,
                        "Category vs measure comparison ke liye sorted bar chart best default hota hai."
                ));
            } else {
                addRecommendation(recommendations, seen, buildRecommendation(
                        "bar", "Compare", dimensionColumn, null, "count", "value-desc", limit,
                        List.of(), "separate",
                        "Record count by " + dimensionColumn,
                        "Count-based comparison sab domain datasets me safe starting view hota hai."
                ));
            }
        }

        for (int index = 0; index < Math.min(2, lowCardinalityDimensions.size()); index++) {
            Map<String, Object> dimensionProfile = lowCardinalityDimensions.get(index);
            String dimensionColumn = profileName(dimensionProfile);
            addRecommendation(recommendations, seen, buildRecommendation(
                    "pie", "Share", null, null, "count", "value-desc", 10,
                    List.of(dimensionColumn), "separate",
                    "Category share of " + dimensionColumn,
                    "Low-cardinality categories ko donut chart me share-of-whole form me dekhna useful hota hai."
            ));
        }

        if (measures.size() >= 2) {
            String xMeasure = profileName(measures.get(0));
            String yMeasure = profileName(measures.get(1));
            addRecommendation(recommendations, seen, buildRecommendation(
                    "scatter", "Relationship", xMeasure, yMeasure, "sum", "auto", 25,
                    List.of(), "separate",
                    xMeasure + " vs " + yMeasure,
                    "Do numeric measures ke beech relationship ya correlation dekhne ke liye scatter best hota hai."
            ));
        }

        return recommendations.stream().limit(8).toList();
    }

    private Map<String, Object> buildRecommendation(String chartType,
                                                    String badge,
                                                    String xAxis,
                                                    String yAxis,
                                                    String aggregation,
                                                    String sort,
                                                    int limit,
                                                    List<String> pieColumns,
                                                    String pieMode,
                                                    String title,
                                                    String description) {
        Map<String, Object> recommendation = new LinkedHashMap<>();
        String pieKey = pieColumns == null ? "" : String.join(",", pieColumns);
        recommendation.put("id", chartType + "|" + String.valueOf(xAxis) + "|" + String.valueOf(yAxis) + "|" + aggregation + "|" + pieKey);
        recommendation.put("chartType", chartType);
        recommendation.put("badge", badge);
        recommendation.put("title", title);
        recommendation.put("description", description);
        recommendation.put("icon", switch (chartType) {
            case "line" -> "📈";
            case "pie" -> "🥧";
            case "scatter" -> "🔵";
            default -> "📊";
        });
        recommendation.put("limit", limit);
        recommendation.put("aggregation", aggregation);
        recommendation.put("sort", sort);
        recommendation.put("xAxis", xAxis);
        recommendation.put("yAxis", yAxis);
        recommendation.put("pieColumns", pieColumns == null ? List.of() : pieColumns);
        recommendation.put("pieMode", pieMode == null ? "separate" : pieMode);
        return recommendation;
    }

    private void addRecommendation(List<Map<String, Object>> recommendations,
                                   Set<String> seen,
                                   Map<String, Object> recommendation) {
        String key = String.valueOf(recommendation.get("id"));
        if (seen.add(key)) {
            recommendations.add(recommendation);
        }
    }

    private Comparator<Map<String, Object>> measureProfileComparator() {
        return Comparator
                .comparingInt((Map<String, Object> profile) -> measurePriority(profileName(profile)))
                .thenComparing(profile -> profileName(profile).toLowerCase(Locale.ROOT));
    }

    private Comparator<Map<String, Object>> temporalProfileComparator() {
        return Comparator
                .comparingInt((Map<String, Object> profile) -> temporalPriority(profileName(profile)))
                .thenComparing(profile -> profileName(profile).toLowerCase(Locale.ROOT));
    }

    private Comparator<Map<String, Object>> dimensionProfileComparator() {
        return Comparator
                .comparingInt((Map<String, Object> profile) -> "geo".equals(profileType(profile)) ? 0 : 1)
                .thenComparingInt(this::profileDistinctCount)
                .thenComparing(profile -> profileName(profile).toLowerCase(Locale.ROOT));
    }

    private int measurePriority(String header) {
        String lowerHeader = header == null ? "" : header.toLowerCase(Locale.ROOT);
        return containsAny(lowerHeader, "sales", "revenue", "amount", "total", "profit", "cost",
                "score", "marks", "price", "weight", "qty", "quantity", "frgt", "wght")
                ? 0 : 1;
    }

    private int temporalPriority(String header) {
        String lowerHeader = header == null ? "" : header.toLowerCase(Locale.ROOT);
        return containsAny(lowerHeader, "date", "month", "mnth", "year", "time", "week", "quarter")
                ? 0 : 1;
    }

    private String profileName(Map<String, Object> profile) {
        return String.valueOf(profile.getOrDefault("name", ""));
    }

    private String profileRole(Map<String, Object> profile) {
        return String.valueOf(profile.getOrDefault("role", ""));
    }

    private String profileType(Map<String, Object> profile) {
        return String.valueOf(profile.getOrDefault("semanticType", ""));
    }

    private int profileDistinctCount(Map<String, Object> profile) {
        Object value = profile.get("sampleDistinctCount");
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private Map<String, Object> buildGroupedChartData(HttpSession session,
                                                      String xAxis,
                                                      String yAxis,
                                                      String chartType,
                                                      String aggregation,
                                                      int limit,
                                                      String sort,
                                                      List<String> numericHeaders,
                                                      List<String> temporalHeaders) throws Exception {
        boolean numericXAxis = numericHeaders.contains(xAxis);
        boolean temporalXAxis = temporalHeaders.contains(xAxis);
        int safeLimit = Math.max(1, Math.min(limit, CHART_MAX_DISPLAY_CATEGORIES));
        Map<String, DoubleSummaryStatistics> grouped = new LinkedHashMap<>();
        AtomicLong sourceRows = new AtomicLong();

        forEachChartRow(session, row -> {
            sourceRows.incrementAndGet();
            String label = normalizeCategoryValue(row.get(xAxis), xAxis);

            if ("count".equals(aggregation)) {
                grouped.computeIfAbsent(label, key -> new DoubleSummaryStatistics()).accept(1D);
                return;
            }

            Double value = parseNumericValue(row.get(yAxis));
            if (value == null) {
                return;
            }

            grouped.computeIfAbsent(label, key -> new DoubleSummaryStatistics()).accept(value);
        });

        if (grouped.isEmpty()) {
            return chartError("Selected columns ke liye chartable data nahi mila.");
        }

        List<ChartBucket> buckets = grouped.entrySet().stream()
                .map(entry -> new ChartBucket(
                        entry.getKey(),
                        resolveAggregationValue(entry.getValue(), aggregation)
                ))
                .toList();

        String resolvedSort = resolveSortMode(sort, chartType, numericXAxis, temporalXAxis);
        List<ChartBucket> sortedBuckets = sortBuckets(buckets, resolvedSort, numericXAxis, temporalXAxis);
        boolean truncated = sortedBuckets.size() > safeLimit;
        List<ChartBucket> displayedBuckets = sortedBuckets.subList(0, Math.min(safeLimit, sortedBuckets.size()));

        List<String> labels = displayedBuckets.stream().map(ChartBucket::label).toList();
        List<Double> values = displayedBuckets.stream().map(ChartBucket::value).toList();
        List<Double> xValues = numericXAxis
                ? displayedBuckets.stream()
                .map(bucket -> parseNumericValue(bucket.label()))
                .filter(value -> value != null)
                .toList()
                : List.of();

        Map<String, Object> response = chartSuccessBase();
        response.put("chartType", chartType);
        response.put("xAxis", xAxis);
        response.put("yAxis", yAxis);
        response.put("aggregation", aggregation);
        response.put("labels", labels);
        response.put("values", values);
        response.put("xValues", xValues.size() == values.size() ? xValues : List.of());
        response.put("numericXAxis", numericXAxis);
        response.put("temporalXAxis", temporalXAxis);
        response.put("sourceRows", sourceRows.get());
        response.put("groupCount", sortedBuckets.size());
        response.put("displayedCount", displayedBuckets.size());
        response.put("truncated", truncated);
        response.put("sort", resolvedSort);
        response.put("note", buildGroupedNote(xAxis, yAxis, aggregation, displayedBuckets.size(), sortedBuckets.size(), truncated));
        return response;
    }

    private Map<String, Object> buildScatterChartData(HttpSession session,
                                                      String xAxis,
                                                      String yAxis,
                                                      List<String> numericHeaders) throws Exception {
        if (!numericHeaders.contains(xAxis) || !numericHeaders.contains(yAxis)) {
            return chartError("Scatter chart ke liye X aur Y dono numeric columns hone chahiye.");
        }

        int totalRows = Math.max(1, getRowCount(session));
        int stride = Math.max(1, totalRows / SCATTER_POINT_LIMIT);
        AtomicLong rowIndex = new AtomicLong();
        List<Map<String, Double>> points = new ArrayList<>();

        forEachChartRow(session, row -> {
            long currentIndex = rowIndex.getAndIncrement();
            if (currentIndex % stride != 0 || points.size() >= SCATTER_POINT_LIMIT) {
                return;
            }

            Double xValue = parseNumericValue(row.get(xAxis));
            Double yValue = parseNumericValue(row.get(yAxis));
            if (xValue == null || yValue == null) {
                return;
            }

            Map<String, Double> point = new LinkedHashMap<>();
            point.put("x", xValue);
            point.put("y", yValue);
            points.add(point);
        });

        if (points.isEmpty()) {
            return chartError("Scatter chart ke liye numeric data points nahi mile.");
        }

        Map<String, Object> response = chartSuccessBase();
        response.put("chartType", "scatter");
        response.put("xAxis", xAxis);
        response.put("yAxis", yAxis);
        response.put("points", points);
        response.put("xValues", points.stream().map(point -> point.get("x")).toList());
        response.put("values", points.stream().map(point -> point.get("y")).toList());
        response.put("sourceRows", totalRows);
        response.put("displayedCount", points.size());
        response.put("truncated", totalRows > points.size());
        response.put("note", totalRows > points.size()
                ? "Scatter chart ko readable rakhne ke liye " + points.size() + " sampled points dikhaye ja rahe hain."
                : "Scatter chart raw numeric points dikhata hai.");
        return response;
    }

    private Map<String, Object> buildPieChartData(HttpSession session,
                                                  List<String> columns,
                                                  String mode,
                                                  int limit) throws Exception {
        int safeLimit = Math.max(1, Math.min(limit, Math.min(CHART_MAX_DISPLAY_CATEGORIES, PIE_MAX_SLICES)));
        Map<String, Map<String, Long>> perColumnCounts = new LinkedHashMap<>();
        Map<String, Long> combinedCounts = new LinkedHashMap<>();
        columns.forEach(column -> perColumnCounts.put(column, new LinkedHashMap<>()));

        AtomicLong sourceRows = new AtomicLong();
        forEachChartRow(session, row -> {
            sourceRows.incrementAndGet();
            for (String column : columns) {
                String label = normalizeCategoryValue(row.get(column), column);
                perColumnCounts.get(column).merge(label, 1L, Long::sum);
                combinedCounts.merge(column + ": " + label, 1L, Long::sum);
            }
        });

        List<Map<String, Object>> series = new ArrayList<>();
        for (String column : columns) {
            series.add(buildPieSeries(column, perColumnCounts.get(column), safeLimit));
        }

        Map<String, Object> response = chartSuccessBase();
        response.put("chartType", "pie");
        response.put("mode", mode == null ? "separate" : mode.toLowerCase(Locale.ROOT));
        response.put("columns", columns);
        response.put("series", series);
        response.put("sourceRows", sourceRows.get());
        response.put("sliceLimit", safeLimit);
        response.put("note", columns.size() == 1
                ? "Pie chart category frequency dikha raha hai. Readability ke liye extra categories ko Others me merge kiya ja sakta hai."
                : "Large category sets ke liye top slices show ki ja rahi hain aur remaining categories Others me merge hoti hain.");

        if ("combined".equalsIgnoreCase(mode)) {
            response.put("combined", buildPieSeries("Combined", combinedCounts, safeLimit));
        }

        return response;
    }

    private Map<String, Object> buildPieSeries(String label, Map<String, Long> counts, int limit) {
        List<PieBucket> sortedBuckets = counts.entrySet().stream()
                .map(entry -> new PieBucket(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(PieBucket::value).reversed()
                        .thenComparing(PieBucket::label))
                .toList();
        long totalValue = sortedBuckets.stream().mapToLong(PieBucket::value).sum();
        int displayLimit = Math.max(1, Math.min(limit, PIE_MAX_SLICES));
        boolean truncated = sortedBuckets.size() > displayLimit;

        List<PieBucket> displayedBuckets = new ArrayList<>(
                sortedBuckets.subList(0, Math.min(displayLimit, sortedBuckets.size()))
        );
        int collapsedCount = 0;
        if (sortedBuckets.size() > displayLimit) {
            List<PieBucket> remainder = sortedBuckets.subList(displayLimit, sortedBuckets.size());
            long othersValue = remainder.stream().mapToLong(PieBucket::value).sum();
            collapsedCount = remainder.size();
            if (othersValue > 0) {
                displayedBuckets.add(new PieBucket("Others", othersValue));
            }
        }

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("column", label);
        series.put("labels", displayedBuckets.stream().map(PieBucket::label).toList());
        series.put("values", displayedBuckets.stream().map(PieBucket::value).toList());
        series.put("percentages", displayedBuckets.stream()
                .map(bucket -> totalValue == 0 ? 0D : ((double) bucket.value() * 100D) / totalValue)
                .toList());
        series.put("totalValue", totalValue);
        series.put("groupCount", sortedBuckets.size());
        series.put("displayedCount", displayedBuckets.size());
        series.put("collapsedCount", collapsedCount);
        series.put("truncated", truncated);
        series.put("dominantLabel", displayedBuckets.isEmpty() ? null : displayedBuckets.get(0).label());
        series.put("dominantValue", displayedBuckets.isEmpty() ? 0L : displayedBuckets.get(0).value());
        series.put("dominantShare", displayedBuckets.isEmpty() || totalValue == 0
                ? 0D
                : ((double) displayedBuckets.get(0).value() * 100D) / totalValue);
        return series;
    }

    private void forEachChartRow(HttpSession session, Consumer<Map<String, Object>> consumer) throws Exception {
        String filePath = resolveStreamFilePath(session);
        List<String> headers = getHeaders(session, List.of());
        if (filePath != null && !filePath.isBlank() && !headers.isEmpty()) {
            fileParserService.streamFile(filePath, row -> {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (String header : headers) {
                    normalized.put(header, row.get(header));
                }
                consumer.accept(normalized);
            });
            return;
        }

        getSessionData(session).forEach(consumer);
    }

    private String resolveStreamFilePath(HttpSession session) {
        Object filePath = session.getAttribute("streamFilePath");
        if (filePath != null && !filePath.toString().isBlank()) {
            return filePath.toString();
        }

        Object datasetId = session.getAttribute("datasetId");
        if (datasetId == null) {
            return null;
        }

        return uploadedDatasetService.findById(datasetId.toString())
                .map(UploadedDataset::getFilePath)
                .orElse(null);
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

            String raw = String.valueOf(value).trim();
            if (raw.isBlank()) {
                return null;
            }

            String normalized = raw
                    .replace(",", "")
                    .replace("₹", "")
                    .replace("Rs.", "")
                    .replace("RS.", "")
                    .replace("rs.", "")
                    .replace("rs", "")
                    .trim();

            if (!normalized.matches("-?\\d+(\\.\\d+)?")) {
                return null;
            }

            return Double.parseDouble(normalized);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeCategoryValue(Object value) {
        String label = value == null ? "Unknown" : String.valueOf(value).trim();
        if (label.isEmpty() || "null".equalsIgnoreCase(label)) {
            return "Unknown";
        }
        return label;
    }

    private String normalizeCategoryValue(Object value, String header) {
        String label = normalizeCategoryValue(value);
        if ("Unknown".equals(label) || header == null || header.isBlank()) {
            return label;
        }

        String lowerHeader = header.toLowerCase(Locale.ROOT);
        if (lowerHeader.contains("mnth") || lowerHeader.contains("month")) {
            return normalizeMonthCategoryLabel(label);
        }
        if (lowerHeader.contains("quarter") || lowerHeader.contains("qtr")) {
            return normalizeQuarterCategoryLabel(label);
        }
        if (lowerHeader.contains("year") || lowerHeader.endsWith("yr")) {
            return normalizeYearCategoryLabel(label);
        }
        return label;
    }

    private String normalizeMonthCategoryLabel(String label) {
        Double numericValue = parseNumericValue(label);
        if (numericValue != null && Math.abs(numericValue - Math.rint(numericValue)) < 0.000001D) {
            int monthNumber = (int) Math.round(numericValue);
            if (monthNumber >= 1 && monthNumber <= 12) {
                return LocalDate.of(2000, monthNumber, 1)
                        .format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
                        .toUpperCase(Locale.ROOT);
            }
        }

        String upper = label.trim().toUpperCase(Locale.ROOT);
        if (upper.matches("[A-Z]{3}")) {
            return upper;
        }
        if (upper.matches("[A-Z]{3}-\\d{2,4}")) {
            return upper.substring(0, 3);
        }

        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH)
        );

        for (DateTimeFormatter formatter : formatters) {
            try {
                LocalDate date = LocalDate.parse(label.trim(), formatter);
                return date.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
                        .toUpperCase(Locale.ROOT);
            } catch (DateTimeParseException ignored) {}
        }

        return label;
    }

    private String normalizeQuarterCategoryLabel(String label) {
        Double numericValue = parseNumericValue(label);
        if (numericValue != null && Math.abs(numericValue - Math.rint(numericValue)) < 0.000001D) {
            int quarter = (int) Math.round(numericValue);
            if (quarter >= 1 && quarter <= 4) {
                return "Q" + quarter;
            }
        }
        return label.toUpperCase(Locale.ROOT);
    }

    private String normalizeYearCategoryLabel(String label) {
        Double numericValue = parseNumericValue(label);
        if (numericValue != null && Math.abs(numericValue - Math.rint(numericValue)) < 0.000001D) {
            int year = (int) Math.round(numericValue);
            if (year >= 1900 && year <= 2100) {
                return String.valueOf(year);
            }
        }

        try {
            LocalDate date = LocalDate.parse(label.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            return String.valueOf(date.getYear());
        } catch (DateTimeParseException ignored) {
            return label;
        }
    }

    private double resolveAggregationValue(DoubleSummaryStatistics statistics, String aggregation) {
        return switch (aggregation) {
            case "avg" -> statistics.getAverage();
            case "min" -> statistics.getMin();
            case "max" -> statistics.getMax();
            case "count" -> statistics.getCount();
            default -> statistics.getSum();
        };
    }

    private String resolveSortMode(String requestedSort,
                                   String chartType,
                                   boolean numericXAxis,
                                   boolean temporalXAxis) {
        if (requestedSort != null && !"auto".equalsIgnoreCase(requestedSort)) {
            return requestedSort.toLowerCase(Locale.ROOT);
        }

        if (numericXAxis || temporalXAxis) {
            return "label-asc";
        }
        if ("line".equalsIgnoreCase(chartType)) {
            return "label-asc";
        }
        return "value-desc";
    }

    private List<ChartBucket> sortBuckets(List<ChartBucket> buckets,
                                          String sortMode,
                                          boolean numericXAxis,
                                          boolean temporalXAxis) {
        Comparator<ChartBucket> comparator;
        switch (sortMode) {
            case "value-asc" -> comparator = Comparator.comparingDouble(ChartBucket::value)
                    .thenComparing(ChartBucket::label);
            case "label-desc" -> comparator = bucketLabelComparator(numericXAxis, temporalXAxis).reversed();
            case "value-desc" -> comparator = Comparator.comparingDouble(ChartBucket::value)
                    .reversed()
                    .thenComparing(ChartBucket::label);
            default -> comparator = bucketLabelComparator(numericXAxis, temporalXAxis);
        }

        return buckets.stream().sorted(comparator).toList();
    }

    private Comparator<ChartBucket> bucketLabelComparator(boolean numericXAxis, boolean temporalXAxis) {
        if (temporalXAxis) {
            return Comparator
                    .comparingLong((ChartBucket bucket) -> temporalSortKey(bucket.label()))
                    .thenComparing(ChartBucket::label);
        }

        if (!numericXAxis) {
            return Comparator.comparing(bucket -> bucket.label().toLowerCase(Locale.ROOT));
        }

        return Comparator.comparingDouble(bucket -> {
            Double value = parseNumericValue(bucket.label());
            return value == null ? 0D : value;
        });
    }

    private long temporalSortKey(String label) {
        if (label == null || label.isBlank()) {
            return Long.MIN_VALUE;
        }

        String trimmed = label.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        if (upper.matches("Q[1-4]-\\d{4}")) {
            int quarter = Character.getNumericValue(upper.charAt(1));
            int year = Integer.parseInt(upper.substring(3));
            return LocalDate.of(year, ((quarter - 1) * 3) + 1, 1).toEpochDay();
        }

        if (upper.matches("\\d{4}")) {
            return LocalDate.of(Integer.parseInt(upper), 1, 1).toEpochDay();
        }

        if (upper.matches("[A-Z]{3}")) {
            try {
                return LocalDate.of(
                        2000,
                        java.time.Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH).parse(upper)).getValue(),
                        1
                ).toEpochDay();
            } catch (Exception ignored) {}
        }

        if (upper.matches("[A-Z]{3}-\\d{2,4}")) {
            try {
                return YearMonth.parse(upper, MONTH_YEAR_LABEL_FORMATTER).atDay(1).toEpochDay();
            } catch (DateTimeParseException ignored) {}
        }

        Double numericValue = parseNumericValue(trimmed);
        if (numericValue != null && Math.abs(numericValue - Math.rint(numericValue)) < 0.000001D) {
            int integerValue = (int) Math.round(numericValue);
            if (integerValue >= 1 && integerValue <= 12) {
                return LocalDate.of(2000, integerValue, 1).toEpochDay();
            }
            if (integerValue >= 1900 && integerValue <= 2100) {
                return LocalDate.of(integerValue, 1, 1).toEpochDay();
            }
        }

        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH)
        );

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter).toEpochDay();
            } catch (DateTimeParseException ignored) {}
        }

        return trimmed.toLowerCase(Locale.ROOT).hashCode();
    }

    private String buildGroupedNote(String xAxis,
                                    String yAxis,
                                    String aggregation,
                                    int displayedCount,
                                    int totalGroups,
                                    boolean truncated) {
        String metric = "count".equals(aggregation)
                ? "record count"
                : humanizeAggregation(aggregation) + " of " + yAxis;

        if (truncated) {
            return "Grouped by " + xAxis + " • " + metric + " • Showing "
                    + displayedCount + " of " + totalGroups
                    + " categories. Limit badhao to aur labels dikhenge.";
        }

        return "Grouped by " + xAxis + " • " + metric + " • "
                + displayedCount + " categories visible.";
    }

    private String humanizeAggregation(String aggregation) {
        return switch (aggregation) {
            case "avg" -> "average";
            case "min" -> "minimum";
            case "max" -> "maximum";
            case "count" -> "count";
            default -> "sum";
        };
    }

    private Map<String, Object> chartSuccessBase() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    private Map<String, Object> chartError(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    private record ChartBucket(String label, double value) {}

    private record PieBucket(String label, long value) {}

    @GetMapping("/ai-analysis")
    public String aiAnalysis(HttpSession session, Model model) {
        List<Map<String, Object>> data = limitWithoutIssue(
                getSessionData(session), AI_SAMPLE_LIMIT);
        List<String> headers = getHeaders(session, data);

        String chartDataJson = "[]";
        try { chartDataJson = objectMapper.writeValueAsString(data); }
        catch (Exception ex) { ex.printStackTrace(); }

        ReportService.Report report = reportService.getReport(session.getId()).orElse(new ReportService.Report());

        model.addAttribute("chartData",     chartDataJson);
        model.addAttribute("headers",       headers);
        model.addAttribute("fileName",      session.getAttribute("fileName"));
        model.addAttribute("rowCount",      session.getAttribute("rowCount"));
        model.addAttribute("colCount",      session.getAttribute("colCount"));
        model.addAttribute("issueCount",    session.getAttribute("issueCount"));
        model.addAttribute("cleanRows",     session.getAttribute("cleanRows"));
        model.addAttribute("savedChatCount", report.getChats().size());
        model.addAttribute("savedChartCount", report.getCharts().size());
        model.addAttribute("largeFileMode", isLargeFileMode(session));
        return "ai-analysis";
    }

    @PostMapping("/ai-analysis/ask")
    @ResponseBody
    public Map<String, Object> askAiAnalysis(@RequestBody Map<String, Object> payload,
                                             HttpSession session) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> data = limitWithoutIssue(
                getSessionData(session), AI_SAMPLE_LIMIT);
        List<String> headers = getHeaders(session, data);

        if (data.isEmpty()) {
            response.put("success", false);
            response.put("answer", "Pehle data upload karo.");
            response.put("mode", "error");
            return response;
        }

        String question = payload == null ? ""
                : String.valueOf(payload.getOrDefault("question", ""));

        try {
            String answer = geminiDataAssistantService.askQuestion(
                    question, data, headers,
                    (String) session.getAttribute("fileName"),
                    getRowCount(session), getColCount(session),
                    getIntAttr(session, "issueCount"),
                    getIntAttr(session, "cleanRows"));
            response.put("success", true);
            response.put("answer",  answer);
            response.put("mode",    geminiDataAssistantService.activeProvider());
        } catch (Exception ex) {
            String geminiError = geminiFacingError(ex);
            response.put("success", false);
            response.put("answer",  "Gemini reply nahi aa paayi.\n\n> " + geminiError);
            response.put("mode",    "error");
            response.put("error",   geminiError);
        }
        return response;
    }

    // ── 7. DATA CLEANING ─────────────────────────────────────

    @PostMapping("/ai-analysis/save-chat")
    @ResponseBody
    public Map<String, Object> saveAiChat(@RequestBody Map<String, Object> payload,
                                          HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        String question = payloadText(payload, "question");
        String answer = payloadText(payload, "answer");
        String mode = payloadText(payload, "mode");

        if (question.isBlank() || answer.isBlank()) {
            response.put("success", false);
            response.put("message", "Save ke liye question aur AI answer dono chahiye.");
            return response;
        }

        boolean saved = reportService.saveChat(session.getId(),
                new ReportService.ChatEntry(question, answer, mode, System.currentTimeMillis()));
        ReportService.Report report = reportService.getReport(session.getId()).orElse(new ReportService.Report());

        response.put("success", true);
        response.put("saved", saved);
        response.put("chatCount", report.getChats().size());
        response.put("chartCount", report.getCharts().size());
        response.put("message", saved ? "Chat report me save ho gayi." : "Ye chat pehle se report me saved hai.");
        return response;
    }

    @GetMapping("/report")
    public String fullAiAnalysisReport(HttpSession session, Model model) {
        List<Map<String, Object>> sessionData = getSessionData(session);
        List<String> headers = getHeaders(session, sessionData);
        List<Map<String, Object>> previewSource = getChartPreviewData(session, headers);
        if (previewSource.isEmpty()) {
            previewSource = sessionData;
        }
        List<Map<String, Object>> previewRows = limitWithoutIssue(previewSource, 20);

        ReportService.Report report = reportService.getReport(session.getId()).orElse(new ReportService.Report());
        List<Map<String, Object>> savedChats = report.getChats().stream()
                .map(this::chatEntryToReportMap)
                .toList();
        List<Map<String, Object>> savedCharts = report.getCharts().stream()
                .map(this::chartEntryToReportMap)
                .toList();
        Map<String, Object> metrics = buildReportMetrics(session, headers, previewSource);

        String savedChartsJson = "[]";
        try {
            savedChartsJson = objectMapper.writeValueAsString(savedCharts);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        model.addAttribute("fileName", defaultIfBlank(String.valueOf(session.getAttribute("fileName")), "Uploaded dataset"));
        model.addAttribute("generated", java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)));
        model.addAttribute("totalRows", Math.max(getRowCount(session), previewSource.size()));
        model.addAttribute("cleanRows", getIntAttr(session, "cleanRows"));
        model.addAttribute("issueCount", getIntAttr(session, "issueCount"));
        model.addAttribute("previewHeaders", headers);
        model.addAttribute("previewRows", previewRows);
        model.addAttribute("columns", String.join(", ", headers));
        model.addAttribute("metrics", metrics);
        model.addAttribute("savedChats", savedChats);
        model.addAttribute("savedCharts", savedCharts);
        model.addAttribute("savedChartsJson", savedChartsJson);
        model.addAttribute("savedChatCount", savedChats.size());
        model.addAttribute("savedChartCount", savedCharts.size());
        return "report";
    }

    @GetMapping("/fix")
    public String clean(Model model, HttpSession session) {
        List<Map<String, Object>> data = getSessionData(session);
        List<String> headers = getHeaders(session, data);
        String filePath = resolveStreamFilePath(session);
        boolean streamAvailable = filePath != null
                && !filePath.isBlank()
                && !headers.isEmpty();

        if (streamAvailable && !Boolean.TRUE.equals(session.getAttribute("cleaningStatsFull"))) {
            try {
                CleaningResult fullStats =
                        dataCleaningService.streamingAnalyze(filePath, headers, CHART_STREAM_CHUNK);
                data = fullStats.data;

                session.setAttribute("uploadedData",   fullStats.data);
                session.setAttribute("sampleData",     fullStats.data);
                session.setAttribute("rowCount",       fullStats.totalRows);
                session.setAttribute("colCount",       headers.size());
                session.setAttribute("cleanRows",      fullStats.cleanRows);
                session.setAttribute("issueCount",     fullStats.issueCount);
                session.setAttribute("missingCount",   fullStats.missingCount);
                session.setAttribute("duplicateCount", fullStats.duplicateCount);
                session.setAttribute("outlierCount",   fullStats.outlierCount);
                session.setAttribute("cleaningStatsFull", true);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        if (data.isEmpty() && streamAvailable) {
            data = getSessionData(session);
        }
        if (data.isEmpty()) return "redirect:/dashboard";

        model.addAttribute("data",           data);
        model.addAttribute("headers",        headers);
        model.addAttribute("missingCount",   session.getAttribute("missingCount"));
        model.addAttribute("duplicateCount", session.getAttribute("duplicateCount"));
        model.addAttribute("outlierCount",   session.getAttribute("outlierCount"));
        return "Clean";
    }

    @PostMapping("/fix/apply")
    @ResponseBody
    public Map<String, Object> applyFixes(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpSession session) {

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> src = getSessionData(session);
        List<String> headers = getHeaders(session, src);
        String sourceFilePath = resolveStreamFilePath(session);
        boolean streamAvailable = sourceFilePath != null
                && !sourceFilePath.isBlank()
                && !headers.isEmpty();

        if (!streamAvailable && src.isEmpty()) {
            response.put("success", false);
            response.put("message", "Pehle data upload karo.");
            return response;
        }

        Map<String, Boolean> enabledCards    = parseEnabledCards(payload == null ? null : payload.get("enabledCards"));
        Map<String, String>  selectedOpts    = parseSelectedOpts(payload == null ? null : payload.get("selectedOpts"));
        Map<String, String>  perColMissing   = parseSelectedOpts(payload == null ? null : payload.get("perColumnMissingOpts"));

        try {
            String cleanedDisplayName = buildCleanFileName((String) session.getAttribute("fileName"));
            if (streamAvailable) {
                Path cleanedPath = Files.createTempFile("aianalysis-cleaned-", ".xlsx");
                StreamingCleanResult cleaned =
                        dataCleaningService.applyUserFixesToExcel(
                                sourceFilePath,
                                headers,
                                cleanedPath,
                                enabledCards,
                                selectedOpts,
                                perColMissing,
                                CHART_STREAM_CHUNK
                        );

                CleaningResult result = cleaned.analysis;

                session.setAttribute("cleanedFilePath", cleaned.filePath.toString());
                session.setAttribute("streamFilePath",  cleaned.filePath.toString());
                session.setAttribute("fileName",        cleanedDisplayName);
                session.setAttribute("cleanedData",     result.data);
                session.setAttribute("uploadedData",    result.data);
                session.setAttribute("sampleData",      result.data);
                session.setAttribute("headers",         headers);
                session.setAttribute("rowCount",        result.totalRows);
                session.setAttribute("colCount",        headers.size());
                session.setAttribute("cleanRows",       result.cleanRows);
                session.setAttribute("issueCount",      result.issueCount);
                session.setAttribute("missingCount",    result.missingCount);
                session.setAttribute("duplicateCount",  result.duplicateCount);
                session.setAttribute("outlierCount",    result.outlierCount);
                session.setAttribute("largeFileMode",   true);
                session.setAttribute("cleaningStatsFull", true);

                response.put("success",     true);
                response.put("message",     "Fixes poori file par apply ho gayi.");
                response.put("rowCount",    result.totalRows);
                response.put("issueCount",  result.issueCount);
                response.put("fileName",    cleanedDisplayName);
                response.put("redirectUrl", "/dashboard");
                response.put("downloadUrl", "/fix/download-cleaned");
                return response;
            }

            List<Map<String, Object>> cleanedData =
                    dataCleaningService.applyUserFixes(src, enabledCards, selectedOpts, perColMissing);
            CleaningResult result  = dataCleaningService.analyze(cleanedData);
            headers = fileParserService.extractHeaders(result.data);

            session.setAttribute("cleanedData",    result.data);
            session.setAttribute("uploadedData",   result.data);
            session.setAttribute("fileName",       cleanedDisplayName);
            session.setAttribute("headers",        headers);
            session.setAttribute("rowCount",       result.totalRows);
            session.setAttribute("colCount",       headers.size());
            session.setAttribute("cleanRows",      result.cleanRows);
            session.setAttribute("issueCount",     result.issueCount);
            session.setAttribute("missingCount",   result.missingCount);
            session.setAttribute("duplicateCount", result.duplicateCount);
            session.setAttribute("outlierCount",   result.outlierCount);
            session.setAttribute("largeFileMode",  false);
            session.setAttribute("cleaningStatsFull", true);
            session.removeAttribute("streamFilePath");
            session.removeAttribute("cleanedFilePath");

            response.put("success",     true);
            response.put("message",     "Fixes apply ho gayi.");
            response.put("rowCount",    result.totalRows);
            response.put("issueCount",  result.issueCount);
            response.put("fileName",    cleanedDisplayName);
            response.put("redirectUrl", "/dashboard");
            response.put("downloadUrl", "/fix/download-cleaned");
        } catch (Exception ex) {
            ex.printStackTrace();
            response.put("success", false);
            response.put("message",
                    userFacingError(ex, "Fix apply nahi hui. File stream ya Excel export check karo."));
        }
        return response;
    }

    @GetMapping("/fix/download-cleaned")
    public ResponseEntity<Resource> downloadCleanedData(HttpSession session) {
        String fileName = buildCleanFileName((String) session.getAttribute("fileName"));
        Path cleanedPath = getReadableSessionPath(session, "cleanedFilePath");

        if (cleanedPath != null) {
            try {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + fileName + "\"")
                        .contentLength(Files.size(cleanedPath))
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .body(new InputStreamResource(Files.newInputStream(cleanedPath)));
            } catch (Exception ex) {
                ex.printStackTrace();
                return ResponseEntity.internalServerError().build();
            }
        }

        List<Map<String, Object>> cleanedData = getSessionData(session);
        if (cleanedData.isEmpty()) return ResponseEntity.badRequest().build();

        List<String> headers  = getHeaders(session, cleanedData);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet     = workbook.createSheet("Clean Data");
            Row   headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }

            for (int i = 0; i < cleanedData.size(); i++) {
                Map<String, Object> dataRow = cleanedData.get(i);
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < headers.size(); j++) {
                    Object val = dataRow.get(headers.get(j));
                    row.createCell(j).setCellValue(val == null ? "" : val.toString());
                }
            }

            workbook.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(new ByteArrayResource(out.toByteArray()));

        } catch (Exception ex) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── HELPERS ──────────────────────────────────────────────

    private boolean shouldUseLargeFileMode(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name != null
                && file.getSize() >= LARGE_FILE_THRESHOLD_BYTES
                && (name.toLowerCase().endsWith(".csv")
                || name.toLowerCase().endsWith(".xlsx")
                || name.toLowerCase().endsWith(".xls"));
    }

    private void storeLargeUploadState(HttpSession session,
                                       String fileName, String mode,
                                       ParseMetadata metadata,
                                       CleaningResult sampleAnalysis) {
        session.setAttribute("largeFileMode",  true);
        session.setAttribute("fileName",       fileName);
        session.setAttribute("uploadMode",     mode);
        session.setAttribute("streamFilePath", metadata.filePath());
        session.setAttribute("headers",        metadata.headers());
        session.setAttribute("uploadedData",   sampleAnalysis.data);
        session.setAttribute("sampleData",     sampleAnalysis.data);
        session.setAttribute("rowCount",       Math.toIntExact(metadata.totalRows()));
        session.setAttribute("colCount",       metadata.headers().size());
        session.setAttribute("cleanRows",      sampleAnalysis.cleanRows);
        session.setAttribute("issueCount",     sampleAnalysis.issueCount);
        session.setAttribute("missingCount",   sampleAnalysis.missingCount);
        session.setAttribute("duplicateCount", sampleAnalysis.duplicateCount);
        session.setAttribute("outlierCount",   sampleAnalysis.outlierCount);
        session.setAttribute("cleaningStatsFull", false);
    }

    private String payloadText(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value;
    }

    private Map<String, Object> chatEntryToReportMap(ReportService.ChatEntry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", entry.getId());
        item.put("question", entry.getQuestion());
        item.put("answer", entry.getAnswer());
        item.put("mode", defaultIfBlank(entry.getMode(), "ai"));
        item.put("timestamp", entry.getTimestamp());
        item.put("timestampLabel", formatReportTimestamp(entry.getTimestamp()));
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chartEntryToReportMap(ReportService.ChartEntry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        if (entry.getConfigJson() != null && !entry.getConfigJson().isBlank()) {
            try {
                config = objectMapper.readValue(entry.getConfigJson(), Map.class);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        String chartType = defaultIfBlank(entry.getChartType(), String.valueOf(config.getOrDefault("type", "chart")));
        item.put("id", entry.getId());
        item.put("title", defaultIfBlank(entry.getTitle(), "Saved Chart"));
        item.put("chartType", chartType);
        item.put("imageData", entry.getImageData());
        item.put("timestamp", entry.getTimestamp());
        item.put("timestampLabel", formatReportTimestamp(entry.getTimestamp()));
        item.put("meta", config.getOrDefault("meta", Map.of()));
        item.put("stats", config.getOrDefault("stats", Map.of()));
        item.put("theme", config.getOrDefault("theme", ""));
        item.put("savedAt", config.getOrDefault("savedAt", ""));
        return item;
    }

    private Map<String, Object> buildReportMetrics(HttpSession session,
                                                   List<String> headers,
                                                   List<Map<String, Object>> previewSource) {
        Map<String, Object> metrics = emptyReportMetrics();
        if (headers == null || headers.isEmpty()) {
            return metrics;
        }

        List<Map<String, Object>> sample = previewSource == null ? List.of() : previewSource;
        if (sample.isEmpty()) {
            sample = getSessionData(session);
        }

        List<String> numericHeaders = detectNumericHeaders(headers, sample);
        metrics.put("numericColumnCount", numericHeaders.size());
        if (numericHeaders.isEmpty()) {
            return metrics;
        }

        String primaryColumn = numericHeaders.get(0);
        String secondaryColumn = numericHeaders.size() > 1 ? numericHeaders.get(1) : "";
        List<Double> values = new ArrayList<>();
        List<Double> correlationX = new ArrayList<>();
        List<Double> correlationY = new ArrayList<>();

        Consumer<Map<String, Object>> collector = row -> {
            Double primary = parseNumericValue(row.get(primaryColumn));
            if (primary != null) {
                values.add(primary);
            }
            if (!secondaryColumn.isBlank()) {
                Double secondary = parseNumericValue(row.get(secondaryColumn));
                if (primary != null && secondary != null) {
                    correlationX.add(secondary);
                    correlationY.add(primary);
                }
            }
        };

        try {
            forEachChartRow(session, collector);
        } catch (Exception ex) {
            sample.forEach(collector);
        }

        if (values.isEmpty()) {
            metrics.put("primaryColumn", primaryColumn);
            metrics.put("secondaryColumn", defaultIfBlank(secondaryColumn, "--"));
            return metrics;
        }

        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double avg = sum / values.size();
        Double variance = reportVariance(values, avg);
        Double stdDev = variance == null ? null : Math.sqrt(variance);
        Double growth = values.size() < 2 || values.get(0) == 0D
                ? null
                : ((values.get(values.size() - 1) - values.get(0)) / values.get(0)) * 100D;
        Double correlation = correlationX.size() >= 2 ? reportCorrelation(correlationX, correlationY) : null;

        metrics.put("hasNumeric", true);
        metrics.put("primaryColumn", primaryColumn);
        metrics.put("secondaryColumn", defaultIfBlank(secondaryColumn, "--"));
        metrics.put("count", formatReportInteger(values.size()));
        metrics.put("sum", formatReportNumber(sum));
        metrics.put("avg", formatReportNumber(avg));
        metrics.put("min", formatReportNumber(sorted.get(0)));
        metrics.put("max", formatReportNumber(sorted.get(sorted.size() - 1)));
        metrics.put("median", formatReportNumber(reportPercentile(sorted, 50)));
        metrics.put("mode", formatReportNumber(reportMode(values)));
        metrics.put("variance", formatReportNumber(variance));
        metrics.put("stdDev", formatReportNumber(stdDev));
        metrics.put("q1", formatReportNumber(reportPercentile(sorted, 25)));
        metrics.put("q3", formatReportNumber(reportPercentile(sorted, 75)));
        metrics.put("p90", formatReportNumber(reportPercentile(sorted, 90)));
        metrics.put("growth", growth == null ? "--" : formatReportNumber(growth) + "%");
        metrics.put("skewness", formatReportNumber(reportSkewness(values, avg, stdDev)));
        metrics.put("kurtosis", formatReportNumber(reportKurtosis(values, avg, stdDev)));
        metrics.put("correlation", formatReportNumber(correlation));
        metrics.put("forecast", formatReportNumber(reportForecast(values)));
        return metrics;
    }

    private Map<String, Object> emptyReportMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("hasNumeric", false);
        metrics.put("primaryColumn", "No numeric column");
        metrics.put("secondaryColumn", "--");
        metrics.put("numericColumnCount", 0);
        for (String key : List.of("count", "sum", "avg", "min", "max", "median", "mode",
                "variance", "stdDev", "q1", "q3", "p90", "growth", "skewness",
                "kurtosis", "correlation", "forecast")) {
            metrics.put(key, "--");
        }
        return metrics;
    }

    private Double reportPercentile(List<Double> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return null;
        }
        double position = (percentile / 100D) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double weight = position - lower;
        return sortedValues.get(lower) * (1D - weight) + sortedValues.get(upper) * weight;
    }

    private Double reportMode(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Map<Double, Integer> frequency = new LinkedHashMap<>();
        for (Double value : values) {
            double rounded = Math.round(value * 100D) / 100D;
            frequency.merge(rounded, 1, Integer::sum);
        }

        Double mode = null;
        int maxCount = 1;
        for (Map.Entry<Double, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mode = entry.getKey();
            }
        }
        return mode;
    }

    private Double reportVariance(List<Double> values, double avg) {
        if (values == null || values.size() < 2) {
            return null;
        }
        double squaredDiff = values.stream()
                .mapToDouble(value -> Math.pow(value - avg, 2))
                .sum();
        return squaredDiff / (values.size() - 1);
    }

    private Double reportSkewness(List<Double> values, double avg, Double stdDev) {
        if (values == null || values.size() < 3 || stdDev == null || stdDev == 0D) {
            return null;
        }
        int n = values.size();
        double thirdMoment = values.stream()
                .mapToDouble(value -> Math.pow((value - avg) / stdDev, 3))
                .sum();
        return (n / ((double) (n - 1) * (n - 2))) * thirdMoment;
    }

    private Double reportKurtosis(List<Double> values, double avg, Double stdDev) {
        if (values == null || values.size() < 4 || stdDev == null || stdDev == 0D) {
            return null;
        }
        int n = values.size();
        double fourthMoment = values.stream()
                .mapToDouble(value -> Math.pow((value - avg) / stdDev, 4))
                .sum();
        double numerator = (n * (n + 1D) * fourthMoment) - (3D * Math.pow(n - 1D, 3));
        double denominator = (n - 1D) * (n - 2D) * (n - 3D);
        return denominator == 0D ? null : numerator / denominator;
    }

    private Double reportCorrelation(List<Double> xValues, List<Double> yValues) {
        if (xValues == null || yValues == null || xValues.size() < 2 || xValues.size() != yValues.size()) {
            return null;
        }
        double avgX = xValues.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double avgY = yValues.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double numerator = 0D;
        double sumSqX = 0D;
        double sumSqY = 0D;

        for (int index = 0; index < xValues.size(); index++) {
            double dx = xValues.get(index) - avgX;
            double dy = yValues.get(index) - avgY;
            numerator += dx * dy;
            sumSqX += dx * dx;
            sumSqY += dy * dy;
        }

        double denominator = Math.sqrt(sumSqX * sumSqY);
        return denominator == 0D ? null : numerator / denominator;
    }

    private Double reportForecast(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int start = Math.max(0, values.size() - 3);
        return values.subList(start, values.size()).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(values.get(values.size() - 1));
    }

    private String formatReportNumber(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "--";
        }
        return new java.text.DecimalFormat("#,##0.##").format(value);
    }

    private String formatReportNumber(double value) {
        return formatReportNumber(Double.valueOf(value));
    }

    private String formatReportInteger(long value) {
        return new java.text.DecimalFormat("#,##0").format(value);
    }

    private String formatReportTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "--";
        }
        return java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getSessionData(HttpSession session) {
        for (String key : new String[]{"cleanedData", "uploadedData", "sampleData"}) {
            Object val = session.getAttribute(key);
            if (val instanceof List<?> list && !list.isEmpty()) {
                return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> getHeaders(HttpSession session,
                                    List<Map<String, Object>> fallback) {
        Object h = session.getAttribute("headers");
        if (h instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list;
        }
        return (fallback == null || fallback.isEmpty())
                ? List.of()
                : fileParserService.extractHeaders(fallback);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getImageExtractedData(HttpSession session) {
        Object data = session.getAttribute(IMAGE_EXTRACTED_DATA_SESSION_KEY);
        if (data instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> getImageExtractedHeaders(HttpSession session,
                                                  List<Map<String, Object>> fallback) {
        Object headers = session.getAttribute(IMAGE_EXTRACTED_HEADERS_SESSION_KEY);
        if (headers instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list;
        }
        return collectHeadersFromRows(fallback);
    }

    private List<Map<String, Object>> limitWithoutIssue(
            List<Map<String, Object>> data, int limit) {
        if (data == null || data.isEmpty()) return List.of();
        return data.stream()
                .limit(limit)
                .map(row -> {
                    Map<String, Object> clean = new LinkedHashMap<>(row);
                    clean.remove("_issue");
                    return clean;
                })
                .toList();
    }

    private List<String> collectHeadersFromRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            headers.addAll(row.keySet());
        }
        return new ArrayList<>(headers);
    }

    private boolean isLargeFileMode(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("largeFileMode"));
    }

    private String getChunkRef(HttpSession session) {
        Object id = session.getAttribute("datasetId");
        if (id != null) return id.toString();
        Object fp = session.getAttribute("streamFilePath");
        return fp == null ? null : fp.toString();
    }

    private Path getReadableSessionPath(HttpSession session, String key) {
        Object value = session.getAttribute(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }

        try {
            Path path = Paths.get(value.toString());
            return Files.isRegularFile(path) && Files.isReadable(path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int getRowCount(HttpSession session) { return getIntAttr(session, "rowCount"); }
    private int getColCount(HttpSession session) { return getIntAttr(session, "colCount"); }

    private int getIntAttr(HttpSession session, String key) {
        Object val = session.getAttribute(key);
        return val instanceof Number n ? n.intValue() : 0;
    }

    private String userFacingError(Throwable ex, String fallback) {
        String message = rootMessage(ex).toLowerCase(Locale.ROOT);

        if (message.contains("duplicate key") || message.contains("e11000") || message.contains("already exists")) {
            return "Email already exists! Please login ya dusra email use karo.";
        }

        if (message.contains("exception authenticating")
                || message.contains("authentication failed")
                || message.contains("scram")
                || message.contains("command failed with error 18")) {
            return "Database authentication failed. Atlas username/password ya Database Access permissions check karo.";
        }

        if (message.contains("timed out")
                || message.contains("connection refused")
                || message.contains("no server chosen")
                || message.contains("unknown host")
                || message.contains("could not connect")) {
            return "Database connection issue aa rahi hai. Atlas Network Access aur connection string check karo.";
        }

        return fallback;
    }

    private String geminiFacingError(Throwable ex) {
        String message = rootMessage(ex);
        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("denied access") || lower.contains("permission_denied")) {
            return "Gemini project/API key ko access denied mil raha hai. Google AI Studio me naya API key/project use karo ya is project ke Gemini API access ko enable/restore karo.";
        }
        if (lower.contains("api key not valid") || lower.contains("invalid api key")) {
            return "GEMINI_API_KEY invalid hai. .env me fresh Google AI Studio API key set karo.";
        }
        if (lower.contains("quota") || lower.contains("rate limit") || lower.contains("resource_exhausted")) {
            return "Gemini quota/rate limit hit ho gaya hai. Billing/quota check karo ya thodi der baad try karo.";
        }
        if (lower.contains("not found") && lower.contains("model")) {
            return "Configured Gemini model available nahi hai. application.properties me gemini.model check karo.";
        }
        if (lower.contains("configured nahi hai")) {
            return message;
        }

        return userFacingError(ex, "Gemini service temporarily unavailable hai.");
    }

    private String rootMessage(Throwable ex) {
        String message = "";
        Throwable current = ex;

        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }

        return message;
    }

    private void clearUploadState(HttpSession session) {
        for (String key : new String[]{
                "uploadedData","sampleData","cleanedData","streamFilePath","cleanedFilePath",
                "headers","datasetId","largeFileMode","fileName",
                "rowCount","colCount","cleanRows","issueCount",
                "missingCount","duplicateCount","outlierCount","uploadMode","cleaningStatsFull"}) {
            session.removeAttribute(key);
        }
    }

    private void clearImageExtractionState(HttpSession session) {
        for (String key : new String[]{
                IMAGE_EXTRACTED_DATA_SESSION_KEY,
                IMAGE_EXTRACTED_HEADERS_SESSION_KEY,
                IMAGE_EXTRACTED_FILE_NAME_SESSION_KEY}) {
            session.removeAttribute(key);
        }
    }

    private Map<String, Boolean> parseEnabledCards(Object obj) {
        Map<String, Boolean> map = new HashMap<>();
        if (!(obj instanceof Map<?, ?> raw)) return map;
        raw.forEach((k, v) -> map.put(String.valueOf(k),
                Boolean.parseBoolean(String.valueOf(v))));
        return map;
    }

    private Map<String, String> parseSelectedOpts(Object obj) {
        Map<String, String> map = new HashMap<>();
        if (!(obj instanceof Map<?, ?> raw)) return map;
        raw.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return map;
    }

    private String buildCleanFileName(String original) {
        if (original == null || original.isBlank()) return "cleaned-data.xlsx";
        if (original.toLowerCase(Locale.ROOT).endsWith("-cleaned.xlsx")) return original;
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        return base + "-cleaned.xlsx";
    }
}
