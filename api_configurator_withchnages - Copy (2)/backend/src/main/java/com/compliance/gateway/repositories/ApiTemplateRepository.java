package com.compliance.gateway.repositories;

import com.compliance.gateway.models.ApiTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiTemplateRepository extends JpaRepository<ApiTemplate, String> {
    List<ApiTemplate> findByIntegrationType(String integrationType);
}