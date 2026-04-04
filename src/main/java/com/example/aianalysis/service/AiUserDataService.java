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

        // 🔥 CHECK: Email already exists
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {
            throw new RuntimeException("Email already exists!");
        }

        // 🔐 Encrypt password before save
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        userRepository.save(user);
    }

    // ✅ LOGIN (SPRING SECURITY USES THIS)
    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

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
    }
}