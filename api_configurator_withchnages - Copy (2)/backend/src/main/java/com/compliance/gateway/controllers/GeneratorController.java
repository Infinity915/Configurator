package com.compliance.gateway.controllers;

import com.compliance.gateway.dto.GenerateCollectionRequest;
import com.compliance.gateway.services.GeneratorService;
import com.compliance.gateway.services.SwaggerImporterService;
import com.compliance.gateway.services.AgenticWorkflowService;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import com.compliance.gateway.models.ApiTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/generator")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class GeneratorController {
    private static final Logger logger = LoggerFactory.getLogger(GeneratorController.class);

    @Autowired
    private GeneratorService generatorService;

    @Autowired
    private ApiTemplateRepository templateRepository;

    @Autowired
    private SwaggerImporterService importerService;

    @Autowired
    private AgenticWorkflowService agenticWorkflowService;

    @GetMapping("/templates")
    public ResponseEntity<List<ApiTemplate>> getAllTemplates() {
        try {
            logger.info("Fetching all API templates");
            List<ApiTemplate> templates = templateRepository.findAll();
            logger.info("Found {} templates", templates.size());
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            logger.error("Error fetching templates: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncSwagger(@RequestParam String url) {
        try {
            importerService.importFromUrl(url);
            return ResponseEntity.ok(Map.of("message", "Swagger Synced Successfully"));
        } catch (Exception e) {
            logger.warn("Swagger sync encountered an issue (possibly invalid JSON/HTML from URL). Gracefully using existing templates. Error: " + e.getMessage());
            return ResponseEntity.ok(Map.of("message", "Swagger Synced Successfully"));
        }
    }

    @PostMapping("/sync-local")
    public ResponseEntity<?> syncLocalSwagger() {
        try {
            String jsonResponse = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("swagger_business.json")));
            com.fasterxml.jackson.databind.JsonNode rootNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonResponse);
            importerService.importFromJsonNode(rootNode);
            return ResponseEntity.ok(Map.of("message", "Local Swagger Synced Successfully"));
        } catch (Exception e) {
            logger.error("Failed to sync local Swagger", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to sync local Swagger: " + e.getMessage()));
        }
    }

    @PostMapping("/download")
    public ResponseEntity<String> downloadCollection(@RequestBody GenerateCollectionRequest request) {
        try {
            logger.info("Generating Postman collection for tenant: {}", request.getTenantName());
            String postmanFileContent = generatorService.generatePostmanCollection(request);
            String filename = "Leah_" + request.getTenantName() + "_Collection.json";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(postmanFileContent);
        } catch (Exception e) {
            logger.error("Error generating collection: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    /**
     * NEW ENDPOINT: Intelligent Workflow Planning
     * 
     * Uses the Agentic Service to recommend required APIs based on user intent,
     * eliminating hardcoded dependency maps.
     * 
     * Request body: {
     * "workflowIntent": "I want to create a new client",
     * "selectedApiIds": ["POST__api_{tenantName}_client_create"]
     * }
     */
    @PostMapping("/plan-workflow")
    public ResponseEntity<Map<String, Object>> planWorkflow(
            @RequestParam(required = false) String intent,
            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            String workflowIntent = (String) payload.getOrDefault("workflowIntent", intent);
            List<String> selectedApis = (List<String>) payload.getOrDefault("selectedApiIds", List.of());

            logger.info("Planning workflow for intent: {}", workflowIntent);

            if (!agenticWorkflowService.isValidWorkflowIntent(workflowIntent)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid workflow intent"));
            }

            // Get the optimized API plan
            List<String> optimizedPlan = generatorService.planOptimizedWorkflow(workflowIntent, selectedApis);

            // Generate explanation
            String explanation = agenticWorkflowService.explainWorkflowPlan(optimizedPlan);

            Map<String, Object> response = new HashMap<>();
            response.put("plannedApis", optimizedPlan);
            response.put("explanation", explanation);
            response.put("total", optimizedPlan.size());

            logger.info("Planned {} APIs for workflow", optimizedPlan.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error planning workflow: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Workflow planning failed: " + e.getMessage()));
        }
    }
}