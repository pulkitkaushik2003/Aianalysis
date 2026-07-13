package com.example.aianalysis.service;

import com.example.aianalysis.Model.UploadedDataset;
import com.example.aianalysis.Repo.UploadedDatasetRepo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UploadedDatasetService {

    private final UploadedDatasetRepo uploadedDatasetRepo;

    public UploadedDatasetService(UploadedDatasetRepo uploadedDatasetRepo) {
        this.uploadedDatasetRepo = uploadedDatasetRepo;
    }

    public UploadedDataset saveUploadedData(String fileName,
                                           String mode,
                                           Integer rowCount,
                                           Integer colCount,
                                           Integer cleanRows,
                                           Integer issueCount,
                                           Integer missingCount,
                                           Integer duplicateCount,
                                           Integer outlierCount,
                                           List<String> headers,
                                           List<Map<String, Object>> data) {
        UploadedDataset dataset = new UploadedDataset();
        dataset.setFileName(fileName);
        dataset.setMode(mode);
        dataset.setRowCount(rowCount);
        dataset.setColCount(colCount);
        dataset.setCleanRows(cleanRows);
        dataset.setIssueCount(issueCount);
        dataset.setMissingCount(missingCount);
        dataset.setDuplicateCount(duplicateCount);
        dataset.setOutlierCount(outlierCount);
        dataset.setHeaders(headers);
        dataset.setData(data);
        dataset.setUploadedAt(LocalDateTime.now());
        return uploadedDatasetRepo.save(dataset);
    }

    public UploadedDataset saveMetadataOnly(String fileName,
                                            String filePath,
                                            String mode,
                                            Integer rowCount,
                                            Integer colCount,
                                            Integer cleanRows,
                                            Integer issueCount,
                                            Integer missingCount,
                                            Integer duplicateCount,
                                            Integer outlierCount,
                                            List<String> headers) {
        UploadedDataset dataset = new UploadedDataset();
        dataset.setFileName(fileName);
        dataset.setFilePath(filePath);
        dataset.setMode(mode);
        dataset.setRowCount(rowCount);
        dataset.setColCount(colCount);
        dataset.setCleanRows(cleanRows);
        dataset.setIssueCount(issueCount);
        dataset.setMissingCount(missingCount);
        dataset.setDuplicateCount(duplicateCount);
        dataset.setOutlierCount(outlierCount);
        dataset.setHeaders(headers);
        dataset.setUploadedAt(LocalDateTime.now());
        return uploadedDatasetRepo.save(dataset);
    }

    public Optional<UploadedDataset> findById(String id) {
        return uploadedDatasetRepo.findById(id);
    }
}
