package com.example.aianalysis.Repo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import com.example.aianalysis.Model.Contact;

@Repository
public interface AiContactUs extends MongoRepository<Contact, String> {
    
}
