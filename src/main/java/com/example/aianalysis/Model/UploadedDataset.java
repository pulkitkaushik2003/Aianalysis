package com.example.aianalysis.Model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Document(collection = "uploaded_datasets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadedDataset {

    @Id
    private String id;

    @Indexed
    private String fileName;

    @Indexed
    private String mode;

    private Integer rowCount;
    private Integer colCount;
    private Integer cleanRows;
    private Integer issueCount;
    private Integer missingCount;
    private Integer duplicateCount;
    private Integer outlierCount;

    @Indexed
    private LocalDateTime uploadedAt;

    private List<String> headers;
    private List<Map<String, Object>> data;
    private String filePath;
}
