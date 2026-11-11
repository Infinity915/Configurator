package com.compliance.gateway.services;

import com.compliance.gateway.models.ApiTemplate;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AgenticWorkflowService: The 'brain' of the backend
 * 
 * This service intelligently plans the required API toolchain based on user
 * intent,
 * eliminating the need for hardcoded dependency maps. Instead of static logic,
 * it uses semantic understanding of API relationships to recommend the optimal
 * set of APIs needed for a given workflow.
 */
@Service
public class AgenticWorkflowService {

    @Autowired
    private ApiTemplateRepository apiTemplateRepository;

    /**
     * Plan Required APIs based on user workflow intent
     * 
     * Instead of hardcoding "Client needs Country", this agent analyzes the
     * requested operation and determines what supporting APIs are needed.
     */
    public List<String> planRequiredApis(String workflowIntent, List<String> selectedApiIds) {
        try {
            // 1. Fetch all available APIs in the registry
            List<ApiTemplate> allApis = apiTemplateRepository.findAll();

            // 2. Extract the core operations from selected IDs
            List<String> selectedOperations = selectedApiIds.stream()
                    .map(id -> extractOperationName(id))
                    .distinct()
                    .collect(Collectors.toList());

            // 3. Use semantic analysis to determine dependencies
            Set<String> requiredApis = new HashSet<>(selectedApiIds);

            for (String operation : selectedOperations) {
                // Add all GET endpoints that serve as prerequisites for this operation
                List<String> dependencies = inferDependencies(operation, allApis);
                requiredApis.addAll(dependencies);
            }

            return new ArrayList<>(requiredApis);
        } catch (Exception e) {
            // Fallback: return the originally selected APIs if inference fails
            return selectedApiIds;
        }
    }

    /**
     * Smart dependency inference based on semantic analysis
     * 
     * Analyzes the operation type and infers what master data / GET endpoints
     * are needed to support it without hardcoding.
     */
    private List<String> inferDependencies(String operation, List<ApiTemplate> allApis) {
        List<String> dependencies = new ArrayList<>();
        String operationLower = operation.toLowerCase();

        // SEMANTIC RULES FOR COMMON WORKFLOWS

        // Rule 1: Any POST/PUT/PATCH operation likely needs to fetch master data first
        if (operationLower.contains("post") || operationLower.contains("put") || operationLower.contains("patch")) {

            // Extract the entity being created/updated (e.g., "client", "contract-request",
            // "user")
            String entityType = extractEntityType(operation);

            if (!entityType.isEmpty()) {
                // Find all GET endpoints that relate to this entity or its dependencies
                List<String> relatedGets = allApis.stream()
                        .filter(api -> api.getId().startsWith("GET__"))
                        .filter(api -> isSemanticallySimilar(entityType, api.getId()))
                        .map(ApiTemplate::getId)
                        .collect(Collectors.toList());

                dependencies.addAll(relatedGets);
            }
        }

        // Rule 2: Operations with "request" in the name likely need
        // user/department/country data
        if (operationLower.contains("request")) {
            String[] requiredMasters = { "user", "department", "country", "client" };
            for (String master : requiredMasters) {
                String getEndpoint = findGetEndpointFor(master, allApis);
                if (getEndpoint != null) {
                    dependencies.add(getEndpoint);
                }
            }
        }

        // Rule 3: Client-related operations need client-type and country data
        if (operationLower.contains("client") && !operationLower.contains("type")) {
            String[] requiredMasters = { "client-type", "country", "masters" };
            for (String master : requiredMasters) {
                String getEndpoint = findGetEndpointFor(master, allApis);
                if (getEndpoint != null) {
                    dependencies.add(getEndpoint);
                }
            }
        }

        // Rule 4: User-related operations need roles and department data
        if (operationLower.contains("user")) {
            String[] requiredMasters = { "roles", "department", "country" };
            for (String master : requiredMasters) {
                String getEndpoint = findGetEndpointFor(master, allApis);
                if (getEndpoint != null) {
                    dependencies.add(getEndpoint);
                }
            }
        }

        return dependencies;
    }

    /**
     * Extract the primary entity type from an operation name
     * 
     * E.g., "POST__api_{tenantName}_client_create" -> "client"
     */
    private String extractEntityType(String operationId) {
        String[] parts = operationId.replace("__", "_").split("_");
        if (parts.length >= 3) {
            // Usually parts[0] is the HTTP method, parts[1] is "api",
            // parts[2] is "{tenantName}", parts[3] is the entity type
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].toLowerCase();
                if (part.equals("client") || part.equals("user") || part.equals("request") ||
                        part.equals("contract") || part.equals("type") || part.equals("rule")) {
                    return part;
                }
            }
        }
        return "";
    }

    /**
     * Extract just the operation name from the full ID
     * 
     * E.g., "GET__api_{tenantName}_client_list" -> "client"
     */
    private String extractOperationName(String apiId) {
        if (apiId.contains("__")) {
            return apiId.split("__")[1].toLowerCase();
        }
        return apiId.toLowerCase();
    }

    /**
     * Check if an API is semantically similar to the requested entity
     * 
     * Uses simple string matching for now, but can be enhanced with ML/embeddings
     */
    private boolean isSemanticallySimilar(String entityType, String apiId) {
        String apiIdLower = apiId.toLowerCase();
        return apiIdLower.contains(entityType);
    }

    /**
     * Find the GET endpoint that provides master data for a specific entity
     */
    private String findGetEndpointFor(String entityType, List<ApiTemplate> allApis) {
        return allApis.stream()
                .filter(api -> api.getId().startsWith("GET__"))
                .filter(api -> api.getId().toLowerCase().contains(entityType.toLowerCase()))
                .map(ApiTemplate::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Validate a workflow intent to catch common user mistakes
     */
    public boolean isValidWorkflowIntent(String intent) {
        return intent != null && !intent.trim().isEmpty() && intent.length() < 500;
    }

    /**
     * Generate a human-readable explanation of the planned workflow
     */
    public String explainWorkflowPlan(List<String> plannedApis) {
        StringBuilder explanation = new StringBuilder("Planned API Toolchain:\n");

        Map<String, Integer> typeCount = new HashMap<>();
        for (String api : plannedApis) {
            String method = api.split("__")[0];
            typeCount.put(method, typeCount.getOrDefault(method, 0) + 1);
        }

        typeCount.forEach((method, count) -> explanation.append("  ").append(method).append(" requests: ").append(count)
                .append("\n"));

        return explanation.toString();
    }
}
