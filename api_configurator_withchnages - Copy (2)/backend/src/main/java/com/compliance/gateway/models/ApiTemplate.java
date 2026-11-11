package com.compliance.gateway.models;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import lombok.Data;

@Data
@Entity
@Table(name = "api_templates")
public class ApiTemplate {
    
    @Id
    private String id;
    
    private String apiName;
    private String integrationType;
    
    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawPostmanTemplate;
    
    private String apiFamily;
    
    @Lob
    @Column(columnDefinition = "TEXT")
    private String sampleRequest;
    
    @Lob
    @Column(columnDefinition = "TEXT")
    private String sampleResponse;
}