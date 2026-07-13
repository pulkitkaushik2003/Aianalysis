package com.example.aianalysis.service;

import com.example.aianalysis.Model.UserData;
import com.example.aianalysis.Repo.AianalysisRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {

    @Autowired
    private AianalysisRepo userRepo;

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.from:${spring.mail.username:}}")
    private String mailFrom;

    public record SendResult(String token, boolean mailSent) {}

    public SendResult createAndSendToken(String email, HttpServletRequest request) {
        String token = UUID.randomUUID().toString();
        long expiry = Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli();

        Optional<UserData> opt = userRepo.findByEmail(email);
        if (opt.isPresent()) {
            UserData user = opt.get();
            user.setResetToken(token);
            user.setResetTokenExpiry(expiry);
            userRepo.save(user);

            String link = buildResetLink(request, token);
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(user.getEmail());
                message.setFrom(mailFrom == null || mailFrom.isBlank() ? "no-reply@insightanalytics.local" : mailFrom);
                message.setSubject("InsightAnalytics - Password reset");
                message.setText("Click the link to reset your password:\n" + link + "\nThis link expires in 1 hour.");
                mailSender.send(message);
                return new SendResult(token, true);
            } catch (Exception ex) {
                ex.printStackTrace();
                return new SendResult(token, false);
            }
        }

        // Always return a token but mailSent=false when user not found — prevents user enumeration
        return new SendResult(token, false);
    }

    public Optional<UserData> validateToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<UserData> opt = userRepo.findByResetToken(token);
        if (opt.isEmpty()) return Optional.empty();
        UserData u = opt.get();
        Long expiry = u.getResetTokenExpiry();
        if (expiry == null) return Optional.empty();
        if (Instant.now().toEpochMilli() > expiry) return Optional.empty();
        return Optional.of(u);
    }

    private String buildResetLink(HttpServletRequest req, String token) {
        String scheme = req.getScheme();
        String host = req.getServerName();
        int port = req.getServerPort();
        String portPart = (port == 80 || port == 443) ? "" : ":" + port;
        String context = req.getContextPath() == null ? "" : req.getContextPath();
        return scheme + "://" + host + portPart + context + "/reset-password?token=" + token;
    }
}
