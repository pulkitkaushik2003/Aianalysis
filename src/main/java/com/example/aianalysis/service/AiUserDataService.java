package com.example.aianalysis.service;

import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.aianalysis.Model.UserData;
import com.example.aianalysis.Repo.AianalysisRepo;

@Service
public class AiUserDataService implements UserDetailsService {

    @Autowired
    private AianalysisRepo userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ✅ SIGNUP (REGISTER USER)
    public void registerUser(UserData user) {
        try {
            // 🔥 CHECK: Email already exists
            if (userRepository.findByEmail(user.getEmail()).isPresent()) {
                throw new RuntimeException("Email already exists!");
            }

            // 🔐 Encrypt password before save
            user.setPassword(passwordEncoder.encode(user.getPassword()));

            userRepository.save(user);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(resolveRepositoryError(ex), ex);
        }
    }

    // ✅ LOGIN (SPRING SECURITY USES THIS)
    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {
        try {
            UserData user = userRepository.findByEmail(email)
                    .orElseThrow(() ->
                            new UsernameNotFoundException(
                                    "User not found with email: " + email
                            ));

            return new User(
                    user.getEmail(),
                    user.getPassword(),
                    Collections.emptyList() // roles later add kar sakte ho
            );
        } catch (UsernameNotFoundException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(resolveRepositoryError(ex), ex);
        }
    }

    private String resolveRepositoryError(Throwable ex) {
        String message = rootMessage(ex).toLowerCase();

        if (message.contains("duplicate key") || message.contains("e11000") || message.contains("already exists")) {
            return "Email already exists! Please login or use another email.";
        }

        if (message.contains("exception authenticating")
                || message.contains("authentication failed")
                || message.contains("scram")
                || message.contains("command failed with error 18")) {
            return "Database authentication failed. Please contact administrator.";
        }

        if (message.contains("timed out")
                || message.contains("connection refused")
                || message.contains("no server chosen")
                || message.contains("unknown host")
                || message.contains("could not connect")) {
            return "Database is currently unreachable. Please try again later.";
        }

        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Database operation failed. Please try again."
                : ex.getMessage();
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
}
