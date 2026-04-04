package com.example.aianalysis.Model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Document(collection = "user_ContactUs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Contact {
    @Id
    private String id;
    private String fullname;
    @Indexed(unique = true)
    private String email;
    private String contact;
    private String serviceIntreasted;
    private String message;

    
}
