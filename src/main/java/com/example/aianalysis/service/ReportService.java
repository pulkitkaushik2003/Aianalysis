package com.example.aianalysis.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportService {

    public static class ChatEntry {
        private final String id;
        private final String question;
        private final String answer;
        private final String mode;
        private final long timestamp;

        public ChatEntry(String question, String answer, String mode, long timestamp) {
            this(null, question, answer, mode, timestamp);
        }

        public ChatEntry(String id, String question, String answer, String mode, long timestamp) {
            this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            this.question = question == null ? "" : question;
            this.answer = answer == null ? "" : answer;
            this.mode = mode == null ? "" : mode;
            this.timestamp = timestamp;
        }

        public String getId() { return id; }
        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public String getMode() { return mode; }
        public long getTimestamp() { return timestamp; }
    }

    public static class ChartEntry {
        private final String id;
        private final String title;
        private final String chartType;
        private final String configJson;
        private final String imageData;
        private final long timestamp;

        public ChartEntry(String id, String title, String chartType, String configJson, String imageData, long timestamp) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.title = title == null ? "" : title;
            this.chartType = chartType == null ? "" : chartType;
            this.configJson = configJson == null ? "" : configJson;
            this.imageData = imageData == null ? "" : imageData;
            this.timestamp = timestamp;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getChartType() { return chartType; }
        public String getConfigJson() { return configJson; }
        public String getImageData() { return imageData; }
        public long getTimestamp() { return timestamp; }
    }

    public static class Report {
        private final List<ChatEntry> chats = new ArrayList<>();
        private final List<ChartEntry> charts = new ArrayList<>();

        public List<ChatEntry> getChats() { return chats; }
        public List<ChartEntry> getCharts() { return charts; }
    }

    private final Map<String, Report> store = new ConcurrentHashMap<>();

    public boolean saveChat(String sessionId, ChatEntry entry) {
        if (sessionId == null || entry == null) return false;
        Report r = store.computeIfAbsent(sessionId, k -> new Report());
        synchronized (r) {
            boolean exists = r.getChats().stream()
                    .anyMatch(c -> c.getQuestion().equals(entry.getQuestion()) && c.getAnswer().equals(entry.getAnswer()));
            if (!exists) {
                r.getChats().add(entry);
                return true;
            }
            return false;
        }
    }

    public boolean saveChart(String sessionId, ChartEntry entry) {
        if (sessionId == null) return false;
        Report r = store.computeIfAbsent(sessionId, k -> new Report());
        synchronized (r) {
            boolean exists = r.getCharts().stream()
                    .anyMatch(c -> {
                        String a = c.getConfigJson() == null ? "" : c.getConfigJson();
                        String b = entry.getConfigJson() == null ? "" : entry.getConfigJson();
                        String ia = c.getImageData() == null ? "" : c.getImageData();
                        String ib = entry.getImageData() == null ? "" : entry.getImageData();
                        return (!a.isBlank() && a.equals(b)) || (!ia.isBlank() && ia.equals(ib));
                    });
            if (!exists) {
                r.getCharts().add(entry);
                System.out.println("[ReportService] Saved chart for session=" + sessionId + " id=" + entry.getId() + " title=" + entry.getTitle());
                return true;
            } else {
                System.out.println("[ReportService] Chart already exists for session=" + sessionId + " title=" + entry.getTitle());
                return false;
            }
        }
    }

    public Optional<Report> getReport(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(store.get(sessionId));
    }

    public void clearCharts(String sessionId) {
        if (sessionId == null) return;
        Report r = store.get(sessionId);
        if (r == null) return;
        synchronized (r) { r.getCharts().clear(); }
    }

    /**
     * Debug helper: return a summary of all stored reports.
     */
    public Map<String, Object> dumpAllReports() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Report> e : store.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            Report r = e.getValue();
            info.put("chats", r.getChats().size());
            info.put("charts", r.getCharts().size());
            List<String> titles = new ArrayList<>();
            for (ChartEntry ce : r.getCharts()) titles.add(ce.getTitle());
            info.put("chartTitles", titles);
            out.put(e.getKey(), info);
        }
        return out;
    }
}
