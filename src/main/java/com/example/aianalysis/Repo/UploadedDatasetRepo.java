package com.example.aianalysis.Repo;

import com.example.aianalysis.Model.UploadedDataset;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadedDatasetRepo extends MongoRepository<UploadedDataset, String> {
}
