package com.example.aianalysis.Model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Document(collection = "user_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserData {

    @Id
    private String id;

    private String fullname;
    private String email;
    private String contact;
    private String password;
}
