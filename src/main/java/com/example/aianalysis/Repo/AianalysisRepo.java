package com.example.aianalysis.Repo;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import com.example.aianalysis.Model.UserData;

@Repository
public interface AianalysisRepo extends MongoRepository<UserData, String> {
    Optional<UserData> findByEmail(String email);
}
