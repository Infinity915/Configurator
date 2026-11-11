package com.compliance.gateway.services;

import com.compliance.gateway.dto.GenerateCollectionRequest;
import com.compliance.gateway.models.ApiTemplate;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GeneratorService {

        // =========================================================================
        // 1. THE WORKFLOW REGISTRY (Configuration Only)
        // =========================================================================
        //
        // HOW TO ADD A NEW WORKFLOW IN THE FUTURE:
        // 1. Create a new List<String> containing the EXACT IDs of the APIs required.
        // 2. The first item should usually be the core mutating API (POST/PUT/PATCH).
        // 3. Add the exact string IDs for all required GET dependencies.
        // 4. Add the human-readable "Fallback Names" (as they appear in the UI/DB).
        // 5. Map both the System ID and the Fallback Name to the list using .put()
        //
        // NOTE: You do NOT need to modify the scrubber or the matcher logic below.
        // The engine will automatically lock down and fetch exactly what you define
        // here.
        // =========================================================================
        private static final Map<String, List<String>> WORKFLOW_REGISTRY = new HashMap<>();

        static {
                // ---------------------------------------------------------------------
                // 1. LEGACY: CREATE CONTRACT REQUEST (Strictly Pruned)
                // ---------------------------------------------------------------------
                List<String> legacyContractReq = Arrays.asList(
                                "POST__api_{tenantName}_contract-request",
                                "GET__api_{tenantName}_client",
                                "GET__api_{tenantName}_client_{clientID}",
                                "GET__api_{tenantName}_client_external_{clientExternalID}",
                                "GET__api_{tenantName}_legal-party",
                                "GET__v1_masters_currency",
                                "GET__api_{tenantName}_user",
                                "GET__api_{tenantName}_application-type",
                                "GET__api_{tenantName}_application-type_intake-form-field-groups",

                                // Human-readable DB Fallbacks & UI Mappings
                                "Create Contract Request",
                                "Get All Clients",
                                "Get Client details by id",
                                "Get Client details by External id",
                                "Get All Legal Party",
                                "Get Currency by Id",
                                "Get currency",
                                "Get User Details",
                                "Get all user details",
                                "Get All Application Type",
                                "Get Intake Form Fields");
                WORKFLOW_REGISTRY.put("POST__api_{tenantName}_contract-request", legacyContractReq);
                WORKFLOW_REGISTRY.put("Create Contract Request", legacyContractReq);

                // ---------------------------------------------------------------------
                // 2. LEGACY: CREATE CLIENT
                // ---------------------------------------------------------------------
                List<String> legacyClient = Arrays.asList(
                                "POST__api_{tenantName}_Client",
                                "GET__api_{tenantName}_client_{clientID}",
                                "GET__api_{tenantName}_client_external_{clientExternalID}",
                                "GET__api_{tenantName}_client-type",
                                "GET__v1_masters_country",
                                "GET__v1_client-types-metadata_get-fields",
                                "PUT__api_{tenantName}_client_{clientID}",
                                "PATCH__api_{tenantName}_client_{clientID}",

                                // Human-readable DB Fallbacks & UI Mappings
                                "Create Client",
                                "Get Client by ID",
                                "Get Client by ExternalID",
                                "Get All Client Types",
                                "Get All Country",
                                "GET Custom Field",
                                "Update Client details");
                WORKFLOW_REGISTRY.put("POST__api_{tenantName}_Client", legacyClient);
                WORKFLOW_REGISTRY.put("Create Client", legacyClient);

                // ---------------------------------------------------------------------
                // 3. BUSINESS API: CONTRACT REQUEST (Maxis)
                // ---------------------------------------------------------------------
                List<String> businessContractReq = Arrays.asList(
                                "POST__business_v{version}_contract-request",
                                "GET__business_v{version}_contract-request_{id}",
                                "PATCH__business_v{version}_contract-request_{id}",
                                "PUT__business_v{version}_contract-request_{id}",
                                "POST__business_v{version}_contract-request_ById",
                                "POST__business_v{version}_version_documentupload_{requestId}",
                                "GET__business_v{version}_status_{id}");
                // Business UI usually passes exact IDs, so fallback mapping is optional
                WORKFLOW_REGISTRY.put("POST__business_v{version}_contract-request", businessContractReq);

                // ---------------------------------------------------------------------
                // 4. FUTURE WORKFLOW TEMPLATE
                // ---------------------------------------------------------------------
                /*
                 * List<String> futureWorkflow = Arrays.asList(
                 * "POST__api_{tenantName}_new-feature",
                 * "GET__api_{tenantName}_dependency-1",
                 * "Create New Feature" // UI Name
                 * );
                 * WORKFLOW_REGISTRY.put("POST__api_{tenantName}_new-feature", futureWorkflow);
                 * WORKFLOW_REGISTRY.put("Create New Feature", futureWorkflow);
                 */
        }

        @Autowired
        private ApiTemplateRepository templateRepository;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private AgenticWorkflowService agenticWorkflowService;

        public String generatePostmanCollection(GenerateCollectionRequest request) {
                try {
                        // 1. Initialize the Root Collection Structure
                        Map<String, Object> collection = new LinkedHashMap<>();

                        // 2. Build Information Block
                        String collectionName = "Leah_"
                                        + (request.getTenantName() != null ? request.getTenantName() : "Integration")
                                        + "_Collection";
                        Map<String, String> info = new HashMap<>();
                        info.put("name", collectionName);
                        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
                        collection.put("info", info);

                        // =========================================================================
                        // 3. GENERATION & ISOLATION LOGIC (Inside generatePostmanCollection)
                        // =========================================================================

                        // 1. STRICT ENVIRONMENT DETECTION
                        boolean isBusinessEnv = "BUSINESS".equalsIgnoreCase(request.getEnvironmentType());
                        if (request.getEnvironmentType() == null && request.getSelectedApiIds() != null) {
                                isBusinessEnv = request.getSelectedApiIds().stream()
                                                .anyMatch(id -> id != null && id.toLowerCase().contains("business"));
                        }
                        boolean hasBusinessApi = isBusinessEnv; // Keeping for backward compatibility below

                        List<Map<String, String>> variables = new ArrayList<>();
                        List<Map<String, Object>> items = new ArrayList<>();

                        if (isBusinessEnv) {
                                // --- MAXIS BUSINESS API (Must use cloudstaging3) ---
                                variables.add(createVariable("tenantName", "maxis"));
                                variables.add(createVariable("tenant_name", "maxis")); // Required for legacy GETs
                                variables.add(createVariable("username", request.getRequestorEmail()));
                                variables.add(createVariable("requestorEmail", request.getRequestorEmail()));
                                variables.add(createVariable("password",
                                                request.getPassword() != null ? request.getPassword() : ""));

                                variables.add(createVariable("auth_api",
                                                "https://cloudstaging3.contractpod.com/cpaimt_auth/auth"));
                                variables.add(createVariable("auth_url",
                                                "https://cloudstaging3.contractpod.com/cpaimt_auth/auth"));
                                variables.add(createVariable("Base_Url", "https://maxis.api.clm.stg3.leahai.com"));
                                variables.add(createVariable("new_api_url",
                                                "https://cpai-productapi-stg.azurewebsites.net"));
                                variables.add(createVariable("old_api_url",
                                                "https://cloudstaging3.contractpod.com/cpaimt_api"));

                                variables.add(createVariable("access_token", ""));
                                variables.add(createVariable("accessToken", ""));
                                variables.add(createVariable("refreshToken", ""));
                                variables.add(createVariable("Version", "1"));
                                variables.add(createVariable("workflowId", "1"));
                                variables.add(createVariable("requestId", "90337"));

                                items.add(buildBusinessAuthItem());
                        } else {
                                // --- LEGACY EXECO (Must use cloudstaging) ---
                                String tenant = (request.getTenantName() != null && !request.getTenantName().isEmpty())
                                                ? request.getTenantName()
                                                : "execo2";
                                variables.add(createVariable("tenantName", tenant));
                                variables.add(createVariable("tenant_name", tenant));
                                variables.add(createVariable("requestorEmail", request.getRequestorEmail()));
                                variables.add(createVariable("username", request.getRequestorEmail()));
                                variables.add(createVariable("password",
                                                request.getPassword() != null ? request.getPassword() : ""));

                                variables.add(createVariable("auth_url",
                                                "https://cloudstaging.contractpod.com/cpaimt_auth/auth"));
                                variables.add(createVariable("auth_api",
                                                "https://cloudstaging.contractpod.com/cpaimt_auth/auth"));
                                variables.add(createVariable("old_api_url",
                                                "https://cloudstaging.contractpod.com/cpaimt_api"));
                                variables.add(createVariable("new_api_url",
                                                "https://cpai-productapi-stg.azurewebsites.net"));

                                variables.add(createVariable("access_token", ""));
                                variables.add(createVariable("accessToken", ""));

                                items.add(buildLegacyAuthItem());
                        }

                        // 6. Process Database Templates for Data APIs
                        List<String> enrichedApiIds = resolveDependencies(request.getSelectedApiIds());

                        List<ApiTemplate> fetchedTemplates = templateRepository.findAll();
                        List<ApiTemplate> selectedTemplatesList = new ArrayList<>();

                        for (ApiTemplate doc : fetchedTemplates) {
                                if (doc.getId() == null)
                                        continue;

                                boolean isMatched = false;

                                for (String lookupId : enrichedApiIds) {
                                        String cleanLookup = lookupId.trim();

                                        // STRICT EXACT MATCHING ONLY. No regex stripping, no .contains().
                                        if (doc.getId().trim().equalsIgnoreCase(cleanLookup)) {
                                                isMatched = true;
                                                break;
                                        }
                                        if (doc.getApiName() != null
                                                        && doc.getApiName().trim().equalsIgnoreCase(cleanLookup)) {
                                                isMatched = true;
                                                break;
                                        }
                                }

                                if (isMatched) {
                                        selectedTemplatesList.add(doc);
                                }
                        }

                        // BLOCKLIST: Explicitly reject templates that must never appear in generated
                        // collections
                        Set<String> blocklist = Set.of(
                                        "Submit Questionnaire",
                                        "Upload Supporting Document");
                        selectedTemplatesList.removeIf(doc -> (doc.getApiName() != null && blocklist.stream()
                                        .anyMatch(b -> b.equalsIgnoreCase(doc.getApiName().trim()))) ||
                                        (doc.getId() != null && blocklist.stream()
                                                        .anyMatch(b -> b.equalsIgnoreCase(doc.getId().trim()))));

                        // SORTING: Ensure templates are added in the exact order requested by the
                        // workflow
                        selectedTemplatesList.sort((doc1, doc2) -> {
                                int index1 = Integer.MAX_VALUE;
                                int index2 = Integer.MAX_VALUE;
                                for (int i = 0; i < enrichedApiIds.size(); i++) {
                                        String clean = enrichedApiIds.get(i).trim();
                                        if ((doc1.getId() != null && doc1.getId().trim().equalsIgnoreCase(clean)) ||
                                                        (doc1.getApiName() != null && doc1.getApiName().trim()
                                                                        .equalsIgnoreCase(clean))) {
                                                index1 = Math.min(index1, i);
                                        }
                                        if ((doc2.getId() != null && doc2.getId().trim().equalsIgnoreCase(clean)) ||
                                                        (doc2.getApiName() != null && doc2.getApiName().trim()
                                                                        .equalsIgnoreCase(clean))) {
                                                index2 = Math.min(index2, i);
                                        }
                                }
                                // If they have the same index (e.g. not found, which shouldn't happen), sort
                                // GETs first
                                if (index1 == index2) {
                                        boolean get1 = (doc1.getId() != null
                                                        && doc1.getId().toUpperCase().startsWith("GET"))
                                                        || (doc1.getApiName() != null && doc1.getApiName().toUpperCase()
                                                                        .startsWith("GET"));
                                        boolean get2 = (doc2.getId() != null
                                                        && doc2.getId().toUpperCase().startsWith("GET"))
                                                        || (doc2.getApiName() != null && doc2.getApiName().toUpperCase()
                                                                        .startsWith("GET"));
                                        if (get1 && !get2)
                                                return -1;
                                        if (!get1 && get2)
                                                return 1;
                                        return 0;
                                }
                                return Integer.compare(index1, index2);
                        });

                        Iterable<ApiTemplate> selectedTemplates = selectedTemplatesList;

                        // AUTO-SEEDING: Extract variables dynamically from selected templates to ensure
                        // 100% bulletproof seeding
                        Set<String> dynamicVariables = new HashSet<>();
                        for (ApiTemplate template : selectedTemplates) {
                                if ("GLOBAL_VARIABLES".equals(template.getId()))
                                        continue;
                                java.util.regex.Matcher matcher = java.util.regex.Pattern
                                                .compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}")
                                                .matcher(template.getRawPostmanTemplate());
                                while (matcher.find()) {
                                        dynamicVariables.add(matcher.group(1));
                                }
                        }

                        Optional<ApiTemplate> globalVarTemplateOpt = templateRepository.findById("GLOBAL_VARIABLES");
                        if (globalVarTemplateOpt.isPresent()) {
                                try {
                                        List<Map<String, String>> globalVars = objectMapper.readValue(
                                                        globalVarTemplateOpt.get().getRawPostmanTemplate(),
                                                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {
                                                        });
                                        for (Map<String, String> gVar : globalVars) {
                                                variables.add(gVar);
                                        }
                                } catch (Exception e) {
                                        System.err.println("Failed to load GLOBAL_VARIABLES: " + e.getMessage());
                                }
                        }

                        // Inject dynamically discovered variables (fallback if GLOBAL_VARIABLES missed
                        // them)
                        for (String varName : dynamicVariables) {
                                if (!varName.equals("tenant_name") && !varName.equals("requestorEmail") &&
                                                !varName.equals("password") && !varName.equals("access_token") &&
                                                !varName.equals("auth_url") && !varName.equals("old_api_url") &&
                                                !varName.equals("new_api_url")) {

                                        boolean alreadyExists = variables.stream()
                                                        .anyMatch(v -> v.get("key").equals(varName));
                                        if (!alreadyExists) {
                                                variables.add(createVariable(varName, "1"));
                                        }
                                }
                        }

                        collection.put("variable", variables);

                        // Items are built below from selectedTemplates

                        for (ApiTemplate template : selectedTemplates) {
                                String rawJson = template.getRawPostmanTemplate();

                                // Standardize the template string replacements
                                String injectedJson = rawJson
                                                .replace("{{tenant-name}}",
                                                                request.getTenantName() != null
                                                                                ? request.getTenantName()
                                                                                : "")
                                                .replace("{{requestorEmail}}",
                                                                request.getRequestorEmail() != null
                                                                                ? request.getRequestorEmail()
                                                                                : "");

                                // Convert the injected string back to a Map so Jackson can safely nest it
                                Map<String, Object> itemMap = objectMapper.readValue(injectedJson, Map.class);

                                // FIX: Execute pure URL synchronization based on OpenAPI spec
                                fixAndSynchronizePostmanUrl(itemMap, hasBusinessApi);

                                if (hasBusinessApi && "BUSINESS".equals(template.getApiFamily())) {
                                        Map<String, Object> req = (Map<String, Object>) itemMap.get("request");
                                        String method = req != null ? (String) req.get("method") : "";
                                        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                                                        || "PATCH".equalsIgnoreCase(method)) {
                                                injectWorkflowIdCaptureScript(itemMap);
                                        }
                                }

                                // FIX #1 & #2: Override upload endpoints from DB
                                // The DB stores these with wrong body type (raw JSON) and broken URL variable.
                                // We intercept them here and inject the correct form-data body.
                                String templateId = template.getId();
                                if (templateId != null && templateId.contains("version_upload_form-data")) {
                                        fixUploadFormDataItem(itemMap);
                                } else if (templateId != null && templateId.contains("version_upload_json-data")) {
                                        fixUploadJsonDataItem(itemMap);
                                }

                                String docName = template.getApiName() != null ? template.getApiName().trim() : "";
                                String docId = templateId != null ? templateId.trim() : "";

                                // Combine name and ID for ultra-loose, bulletproof matching
                                String searchTarget = (docName + " " + docId).toLowerCase();

                                // =========================================================================
                                // 2. AUTOMATIC PAYLOAD CLEANER (Strict Variables - NO HARDCODING)
                                // =========================================================================
                                if (searchTarget.contains("contract-request")) {
                                        try {
                                                Map<String, Object> requestNode = (Map<String, Object>) itemMap
                                                                .get("request");
                                                if (requestNode != null && requestNode.containsKey("body")) {
                                                        Map<String, Object> bodyNode = (Map<String, Object>) requestNode
                                                                        .get("body");

                                                        String strictPayload = "{\n" +
                                                                        "  \"id\": 0,\n" +
                                                                        "  \"applicationTypeId\": {{applicationTypeId}},\n"
                                                                        +
                                                                        "  \"recordId\": 0,\n" +
                                                                        "  \"requestTypeId\": {{requestTypeId}},\n" +
                                                                        "  \"isUploadedContract\": true,\n" +
                                                                        "  \"assignees\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"userId\": {{userId}},\n" +
                                                                        "      \"departmentId\": {{departmentId}},\n" +
                                                                        "      \"functionId\": null,\n" +
                                                                        "      \"isPrimary\": true\n" +
                                                                        "    }\n" +
                                                                        "  ],\n" +
                                                                        "  \"requesterUser\": {\n" +
                                                                        "    \"UserId\": {{userId}},\n" +
                                                                        "    \"DepartmentId\": {{departmentId}}\n" +
                                                                        "  },\n" +
                                                                        "  \"contractValue\": {\n" +
                                                                        "    \"currencyId\": {{currencyId}},\n" +
                                                                        "    \"amount\": 122\n" +
                                                                        "  },\n" +
                                                                        "  \"legalParties\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"legalPartyId\": {{legalPartyId}},\n" +
                                                                        "      \"isPrimary\": true\n" +
                                                                        "    }\n" +
                                                                        "  ],\n" +
                                                                        "  \"contractPriority\": {\n" +
                                                                        "    \"priority\": true,\n" +
                                                                        "    \"priorityReason\": \"test\"\n" +
                                                                        "  },\n" +
                                                                        "  \"recordClassificationId\": 0,\n" +
                                                                        "  \"integrationId\": [\n" +
                                                                        "    \"string\"\n" +
                                                                        "  ],\n" +
                                                                        "  \"clients\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"clientId\": {{clientId}},\n" +
                                                                        "      \"isPrimary\": true,\n" +
                                                                        "      \"addressDetailId\": {{addressDetailId}},\n"
                                                                        +
                                                                        "      \"contactNumberDetailId\": {{contactNumberDetailId}},\n"
                                                                        +
                                                                        "      \"emailDetailId\": {{emailDetailId}},\n"
                                                                        +
                                                                        "      \"contactNameDetailId\": {{contactNameDetailId}},\n"
                                                                        +
                                                                        "      \"roleId\": 2,\n" +
                                                                        "      \"customFields\": []\n" +
                                                                        "    }\n" +
                                                                        "  ],\n" +
                                                                        "  \"requestorUsername\": \"string\",\n" +
                                                                        "  \"description\": \"123\",\n" +
                                                                        "  \"isConfidential\": true,\n" +
                                                                        "  \"skipCustomFields\": true,\n" +
                                                                        "  \"skipClientCustomFields\": true,\n" +
                                                                        "  \"confidentialRecords\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"UserID\": {{userId}},\n" +
                                                                        "      \"RoleId\": {{roleId}}\n" +
                                                                        "    }\n" +
                                                                        "  ],\n" +
                                                                        "  \"customFields\": [\n" +
                                                                        "    {\n" +
                                                                        "      \"customFieldId\": {{customFieldId}},\n"
                                                                        +
                                                                        "      \"customFieldValue\": \"{{customFieldValue}}\"\n"
                                                                        +
                                                                        "    }\n" +
                                                                        "  ]\n" +
                                                                        "}";

                                                        bodyNode.put("raw", strictPayload);
                                                }
                                        } catch (Exception e) {
                                        }
                                }

                                // =========================================================================
                                // 3. AUTOMATIC VARIABLE CHAINING (Chained Extraction + JSON Safe Nulls)
                                // =========================================================================
                                String scriptCode = null;

                                if (searchTarget.contains("legal party") || searchTarget.contains("legal-party")) {
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; var item = Array.isArray(data) ? data[0] : data; if(item) { pm.collectionVariables.set('legalPartyId', item.id || item.Id || item.legalPartyId); } } catch(e){}";
                                } else if ((searchTarget.contains("application type")
                                                || searchTarget.contains("application-type"))
                                                && !searchTarget.contains("intake")) {
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; var item = Array.isArray(data) ? data[0] : data; if(item) { pm.collectionVariables.set('applicationTypeId', item.id || item.Id || item.applicationTypeId); pm.collectionVariables.set('requestTypeId', 1); } } catch(e){}";
                                } else if (searchTarget.contains("user") && !searchTarget.contains("requester")) {
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; if(Array.isArray(data) && data.length > 0) { var item = data.find(u => u.departmentId != null) || data[0]; pm.collectionVariables.set('userId', item.id || item.Id || item.userId); pm.collectionVariables.set('departmentId', item.departmentId || (item.departmentIds ? item.departmentIds[0] : 'null')); pm.collectionVariables.set('roleId', item.roleId || 2); } } catch(e){}";

                                        // --- CLIENT CHAINING ---
                                } else if (docName.equalsIgnoreCase("Get All Clients")) {
                                        // STEP 1: Extract clientId and prep URL for detail request
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; if(Array.isArray(data) && data.length > 0) { "
                                                        +
                                                        "var cid = data[0].clientId || data[0].id || data[0].Id; " +
                                                        "pm.collectionVariables.set('clientId', cid); " +
                                                        "pm.collectionVariables.set('clientID', cid); " +
                                                        "pm.collectionVariables.set('id', cid); " +
                                                        "} } catch(e){}";
                                } else if (docName.equalsIgnoreCase("Get Client details by id")
                                                || docName.equalsIgnoreCase("Get Client details by External id")) {
                                        // STEP 2: Extract sub-IDs safely using the string 'null' to protect JSON
                                        // formatting
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; var item = Array.isArray(data) ? data[0] : data; if(item) { "
                                                        +
                                                        "var getSubId = function(arr) { return (arr && arr.length > 0) ? (arr[0].addressTypeId || arr[0].addressDetailId || arr[0].contactNumberDetailId || arr[0].emailDetailId || arr[0].contactNameDetailId || arr[0].id || arr[0].Id) : 'null'; }; "
                                                        +
                                                        "pm.collectionVariables.set('addressDetailId', item.addressTypeId || item.addressDetailId || getSubId(item.addresses) || getSubId(item.addressDetails) || 'null'); "
                                                        +
                                                        "pm.collectionVariables.set('contactNumberDetailId', item.contactNumberDetailId || getSubId(item.contactNumbers) || 'null'); "
                                                        +
                                                        "pm.collectionVariables.set('emailDetailId', item.emailDetailId || getSubId(item.emails) || getSubId(item.emailDetails) || 'null'); "
                                                        +
                                                        "pm.collectionVariables.set('contactNameDetailId', item.contactNameDetailId || getSubId(item.contactNames) || 'null'); "
                                                        +
                                                        "} } catch(e){}";

                                        // --- CURRENCY CHAINING ---
                                } else if (docName.toLowerCase().contains("all currency")
                                                || docName.equalsIgnoreCase("Get currency")) {
                                        // STEP 1: Extract currencyId and prep URL
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; if(Array.isArray(data) && data.length > 0) { "
                                                        +
                                                        "var curId = data[0].id || data[0].Id || data[0].currencyId; " +
                                                        "pm.collectionVariables.set('currencyId', curId); " +
                                                        "pm.collectionVariables.set('id', curId); " +
                                                        "} } catch(e){}";
                                } else if (docName.equalsIgnoreCase("Get Currency by Id")) {
                                        // STEP 2: Save detailed ID
                                        scriptCode = "try { var res = pm.response.json(); var data = res.data || res.items || res; var item = Array.isArray(data) ? data[0] : data; if(item) { "
                                                        +
                                                        "pm.collectionVariables.set('currencyId', item.id || item.Id || item.currencyId); "
                                                        +
                                                        "} } catch(e){}";
                                } else if (searchTarget.contains("intake")) {
                                        scriptCode = "try { var res = pm.response.json(); " +
                                                        "var targetField = null; " +
                                                        "var findF = function(o) { " +
                                                        "    if (targetField || !o || typeof o !== 'object') return; " +
                                                        "    if (o.fieldType && String(o.fieldType).toLowerCase() === 'text' && (o.isRequired === true || o.isMandatory === true)) { "
                                                        +
                                                        "        targetField = o; return; " +
                                                        "    } " +
                                                        "    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) findF(o[i]); } "
                                                        +
                                                        "    else { for (var k in o) { if (Object.prototype.hasOwnProperty.call(o, k)) findF(o[k]); } } "
                                                        +
                                                        "}; " +
                                                        "findF(res); " +
                                                        "if (targetField) { " +
                                                        "    pm.collectionVariables.set('customFieldId', targetField.fieldId || targetField.id || targetField.Id); "
                                                        +
                                                        "    pm.collectionVariables.set('customFieldValue', 'random test'); "
                                                        +
                                                        "} else { " +
                                                        "    pm.collectionVariables.set('customFieldId', 'null'); " +
                                                        "    pm.collectionVariables.set('customFieldValue', 'null'); " +
                                                        "} } catch(e){}";
                                }

                                // Attach script to the Postman Event
                                if (scriptCode != null) {
                                        Map<String, Object> scriptNode = new HashMap<>();
                                        scriptNode.put("type", "text/javascript");
                                        scriptNode.put("exec", Arrays.asList(scriptCode));
                                        List<Map<String, Object>> events = (List<Map<String, Object>>) itemMap
                                                        .getOrDefault("event", new ArrayList<>());
                                        events.removeIf(e -> "test".equals(e.get("listen")));
                                        events.add(Map.of("listen", "test", "script", scriptNode));
                                        itemMap.put("event", events);
                                }

                                items.add(itemMap);
                        }

                        collection.put("item", items);

                        // 7. Serialize the perfect JSON tree back to a String
                        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(collection);

                } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to generate Postman Collection JSON", e);
                }
        }

        // --- Helper Methods ---

        // =========================================================================
        // 2. THE SCRUBBER ENGINE (HYBRID-AWARE RUTHLESS LOCKDOWN)
        // =========================================================================
        private List<String> resolveDependencies(List<String> rawSelectedApiIds) {
                if (rawSelectedApiIds == null || rawSelectedApiIds.isEmpty())
                        return new ArrayList<>();

                Set<String> finalCleanList = new LinkedHashSet<>();
                boolean workflowTriggered = false;

                for (String id : rawSelectedApiIds) {
                        if (id == null)
                                continue;

                        // If the user selected managed workflows, accumulate their exact dependencies.
                        // We check ALL selections so that hybrid scenarios (Business + Legacy) work.
                        for (Map.Entry<String, List<String>> entry : WORKFLOW_REGISTRY.entrySet()) {
                                if (id.trim().equalsIgnoreCase(entry.getKey())) {
                                        finalCleanList.addAll(entry.getValue());
                                        workflowTriggered = true;
                                }
                        }
                }

                // If any workflow was triggered, IGNORE EVERYTHING ELSE and return ONLY the
                // accumulated exact dependencies.
                if (workflowTriggered) {
                        return new ArrayList<>(finalCleanList);
                }

                // If no managed workflow was hit, return the exact selections
                return rawSelectedApiIds.stream().filter(Objects::nonNull).map(String::trim)
                                .collect(Collectors.toList());
        }

        private Map<String, String> createVariable(String key, String value) {
                Map<String, String> var = new HashMap<>();
                var.put("key", key);
                var.put("value", value);
                var.put("type", "string");
                return var;
        }

        // =========================================================================
        // 4. AUTHENTICATION BUILDERS
        // =========================================================================

        private Map<String, Object> buildLegacyAuthItem() {
                Map<String, Object> authItem = new LinkedHashMap<>();
                authItem.put("name", "1. Get Authentication Token");

                // CRITICAL: Script to auto-save Legacy token
                Map<String, Object> script = new HashMap<>();
                script.put("type", "text/javascript");
                script.put("exec", Arrays.asList(
                                "pm.test(\"Status code is 200\", function () { pm.response.to.have.status(200); });",
                                "const responseData = pm.response.json();",
                                "pm.collectionVariables.set(\"access_token\", responseData.access_token);",
                                "pm.collectionVariables.set(\"accessToken\", responseData.access_token);"));
                authItem.put("event", Arrays.asList(Map.of("listen", "test", "script", script)));

                Map<String, Object> req = new LinkedHashMap<>();
                req.put("method", "POST");
                req.put("url", "{{auth_url}}/token");

                Map<String, Object> body = new HashMap<>();
                body.put("mode", "raw");
                body.put("raw", "{\n    \"grant_type\": \"password\",\n    \"username\": \"{{requestorEmail}}\",\n    \"password\": \"{{password}}\",\n    \"domain\": \"{{tenant_name}}\",\n    \"scope\": \"openid profile email\"\n}");
                body.put("options", Map.of("raw", Map.of("language", "json")));
                req.put("body", body);
                req.put("header", Arrays.asList(Map.of("key", "Content-Type", "value", "application/json")));

                authItem.put("request", req);
                return authItem;
        }

        private Map<String, Object> buildBusinessAuthItem() {
                Map<String, Object> authItem = new LinkedHashMap<>();
                authItem.put("name", "Get Token (Maxis)");

                // CRITICAL: Script to auto-save Maxis tokens
                Map<String, Object> script = new HashMap<>();
                script.put("type", "text/javascript");
                script.put("exec", Arrays.asList(
                                "pm.test(\"Status code is 200\", function () { pm.response.to.have.status(200); });",
                                "const responseData = pm.response.json();",
                                "pm.collectionVariables.set(\"accessToken\", responseData.access_token);",
                                "pm.collectionVariables.set(\"refreshToken\", responseData.refresh_token);",
                                "pm.collectionVariables.set(\"access_token\", responseData.access_token);"));
                authItem.put("event", Arrays.asList(Map.of("listen", "test", "script", script)));

                Map<String, Object> req = new LinkedHashMap<>();
                req.put("method", "POST");
                req.put("url", "{{auth_api}}/token");

                Map<String, Object> body = new HashMap<>();
                body.put("mode", "raw");
                body.put("raw", "{\n    \"username\": \"{{username}}\",\n    \"password\": \"{{password}}\",\n    \"domain\": \"{{tenantName}}\",\n    \"call_for_integration\": 9,\n    \"grant_type\": \"Password\"\n}");
                body.put("options", Map.of("raw", Map.of("language", "json")));
                req.put("body", body);
                req.put("header", Arrays.asList(Map.of("key", "Content-Type", "value", "application/json")));

                authItem.put("request", req);
                return authItem;
        }

        @SuppressWarnings("unchecked")
        private void injectWorkflowIdCaptureScript(Map<String, Object> itemMap) {
                List<Map<String, Object>> events = (List<Map<String, Object>>) itemMap.get("event");
                if (events == null) {
                        events = new ArrayList<>();
                        itemMap.put("event", events);
                }

                Map<String, Object> testEvent = null;
                for (Map<String, Object> event : events) {
                        if ("test".equals(event.get("listen"))) {
                                testEvent = event;
                                break;
                        }
                }

                if (testEvent == null) {
                        testEvent = new LinkedHashMap<>();
                        testEvent.put("listen", "test");
                        Map<String, Object> script = new LinkedHashMap<>();
                        script.put("type", "text/javascript");
                        script.put("exec", new ArrayList<String>());
                        testEvent.put("script", script);
                        events.add(testEvent);
                }

                Map<String, Object> script = (Map<String, Object>) testEvent.get("script");
                List<String> exec = (List<String>) script.get("exec");
                if (exec == null) {
                        exec = new ArrayList<>();
                        script.put("exec", exec);
                }

                exec.add("const responseJson = pm.response.json();");
                exec.add("if (responseJson.workflowId) {");
                exec.add("    pm.collectionVariables.set(\"workflowId\", responseJson.workflowId);");
                exec.add("}");
        }

        public String sanitizeUrl(String rawPath) {
                String sanitized = rawPath.replaceAll("(?i)/v1/masters/", "/api/{{tenant_name}}/Master/");
                sanitized = sanitized.replaceAll("(?i)\\{tenantname\\}", "{{tenant_name}}");
                sanitized = sanitized.replaceAll("(?i)\\{tenant-name\\}", "{{tenant_name}}");
                sanitized = sanitized.replaceAll("(?i)/master/", "/Master/");
                sanitized = sanitized.replaceAll("(?i)/client/", "/Client/");
                return sanitized;
        }

        public List<String> planOptimizedWorkflow(String workflowIntent, List<String> selectedApis) {
                return agenticWorkflowService.planRequiredApis(workflowIntent, selectedApis);
        }

        /**
         * CORRECT URL SYNCHRONIZER
         * Strips host variable from path array, defeats bracket corruption, and
         * rebuilds safely.
         */
        @SuppressWarnings("unchecked")
        private void fixAndSynchronizePostmanUrl(Map itemMap, boolean hasBusinessApi) {
                try {
                        Map request = (Map) itemMap.get("request");
                        if (request == null)
                                return;

                        Map urlObj = (Map) request.get("url");
                        if (urlObj == null)
                                return;

                        // 1. Isolate the path by strictly removing the host
                        String rawUrl = (String) urlObj.getOrDefault("raw", "");
                        String pathOnly = rawUrl.replace("{{new_api_url}}", "").replace("{{old_api_url}}", "")
                                        .replace("{{Base_Url}}", "");

                        // Check if this specific endpoint is a Business API to resolve the correct host
                        boolean isBusinessEndpoint = pathOnly.toLowerCase().contains("/business/");
                        String hostVariable = isBusinessEndpoint ? "{{Base_Url}}" : "{{new_api_url}}";

                        // --- NEW: COUNTRY ENDPOINT FIX ---
                        if (pathOnly.toLowerCase().contains("/v1/masters/")) {
                                pathOnly = pathOnly.replaceAll("(?i)/v1/masters/", "/Master/");
                        }
                        // ---------------------------------

                        // 2. Strip ALL brackets completely to defeat database corruption, then rebuild
                        // them safely
                        if (pathOnly.contains("{") || pathOnly.contains("}")) {
                                // This physically destroys {{{tenant_name}}} and turns it into pure text:
                                // tenant_name
                                pathOnly = pathOnly.replaceAll("[{}]", "");

                                // Now we safely inject exactly two brackets around known variables
                                pathOnly = pathOnly.replace("tenantname", "{{tenant_name}}")
                                                .replace("tenant_name", "{{tenant_name}}")
                                                .replace("applicationTypeId", "{{applicationTypeId}}")
                                                .replace("activityId", "{{activityId}}")
                                                // FIX: map requestId → {{cpaiRequestID}} BEFORE generic {id}
                                                // replacement
                                                .replace("requestId", "{{cpaiRequestID}}")
                                                .replace("id", "{{id}}");
                        }

                        // 3. Ensure the single proper prefix exists
                        if (!pathOnly.startsWith("/")) {
                                pathOnly = "/" + pathOnly;
                        }

                        // 4. Update the raw URL
                        String finalRawUrl = hostVariable + pathOnly;
                        urlObj.put("raw", finalRawUrl);

                        // 5. Build the Postman Path Array (Crucially WITHOUT the host variable AND
                        // WITHOUT query parameters)
                        List pathSegments = new ArrayList<>();
                        String pathWithoutQuery = pathOnly;
                        int queryIndex = pathOnly.indexOf("?");
                        if (queryIndex != -1) {
                                pathWithoutQuery = pathOnly.substring(0, queryIndex);
                        }

                        String[] segments = pathWithoutQuery.split("/");

                        for (String segment : segments) {
                                if (!segment.trim().isEmpty()) {
                                        pathSegments.add(segment.trim());
                                }
                        }

                        urlObj.put("path", pathSegments);
                        urlObj.put("host", Collections.singletonList(hostVariable));

                        request.put("url", urlObj);
                        itemMap.put("request", request);

                } catch (Exception e) {
                        System.err.println("Error fixing Postman URL synchronization: " + e.getMessage());
                }
        }

        /**
         * HARDCODED ITEM BUILDER
         * Builds a complete Postman request item from scratch for endpoints not in
         * MongoDB.
         * 
         * @param name         Display name of the request
         * @param method       HTTP method (GET, POST, etc.)
         * @param rawUrl       Full raw URL string with Postman variables
         * 
         * 
         *                     11
         * 
         * @param pathSegments URL path segments array (without host)
         * @param queryParams  2D array of [key, value] query params (nullable for POST)
         * @param body         Raw JSON body string, "FORM_DATA" for multipart, or null
         *                     for GET
         * @param hostVariable The collection variable name to use as host (e.g.
         *                     "old_api_url")
         */
        private Map<String, Object> buildHardcodedItem(
                        String name, String method, String rawUrl, String[] pathSegments,
                        String[][] queryParams, String body, String hostVariable) {

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);

                // Auth: bearer token
                Map<String, Object> auth = new LinkedHashMap<>();
                auth.put("type", "bearer");
                Map<String, String> bearerToken = new LinkedHashMap<>();
                bearerToken.put("key", "token");
                bearerToken.put("value", "{{access_token}}");
                bearerToken.put("type", "string");
                auth.put("bearer", Collections.singletonList(bearerToken));

                Map<String, Object> request = new LinkedHashMap<>();
                request.put("auth", auth);
                request.put("method", method);

                // Header
                Map<String, Object> contentTypeHeader = new LinkedHashMap<>();
                contentTypeHeader.put("key", "Content-Type");
                contentTypeHeader.put("value", "application/json");
                contentTypeHeader.put("type", "text");
                request.put("header", Collections.singletonList(contentTypeHeader));

                // Body
                if (body != null) {
                        Map<String, Object> bodyMap = new LinkedHashMap<>();
                        if ("FORM_DATA".equals(body)) {
                                bodyMap.put("mode", "formdata");
                                List<Map<String, Object>> formFields = new ArrayList<>();
                                for (String[] field : new String[][] {
                                                { "RequestorUsername", "{{requestorUserName}}" },
                                                { "DocumentType", "{{documentTypeID}}" },
                                                { "IsChildRequest", "false" },
                                                { "Comment", "" },
                                                { "Title", "" },
                                                { "IsDescriptor", "false" },
                                                { "IsContractVersion", "false" },
                                                { "RequestId", "{{cpaiRequestID}}" },
                                                { "IsAttachmentUploadComplete", "true" },
                                                { "IsFinalSignedCopy", "false" }
                                }) {
                                        Map<String, Object> f = new LinkedHashMap<>();
                                        f.put("key", field[0]);
                                        f.put("value", field[1]);
                                        f.put("type", "text");
                                        formFields.add(f);
                                }
                                // Add the file field
                                Map<String, Object> fileField = new LinkedHashMap<>();
                                fileField.put("key", "file");
                                fileField.put("type", "file");
                                fileField.put("src", "");
                                formFields.add(fileField);
                                bodyMap.put("formdata", formFields);
                        } else {
                                bodyMap.put("mode", "raw");
                                bodyMap.put("raw", body);
                                Map<String, Object> options = new LinkedHashMap<>();
                                Map<String, Object> rawOpt = new LinkedHashMap<>();
                                rawOpt.put("language", "json");
                                options.put("raw", rawOpt);
                                bodyMap.put("options", options);
                        }
                        request.put("body", bodyMap);
                }

                // URL
                Map<String, Object> urlMap = new LinkedHashMap<>();
                // Build raw URL with query params appended
                String fullRawUrl = rawUrl;
                if (queryParams != null && queryParams.length > 0) {
                        StringBuilder qs = new StringBuilder("?");
                        for (int i = 0; i < queryParams.length; i++) {
                                if (i > 0)
                                        qs.append("&");
                                qs.append(queryParams[i][0]).append("=").append(queryParams[i][1]);
                        }
                        fullRawUrl = rawUrl + qs;
                }
                urlMap.put("raw", fullRawUrl);
                urlMap.put("host", Collections.singletonList("{{" + hostVariable + "}}"));
                urlMap.put("path", Arrays.asList(pathSegments));

                // Add query params to URL object
                if (queryParams != null && queryParams.length > 0) {
                        List<Map<String, Object>> qpList = new ArrayList<>();
                        for (String[] qp : queryParams) {
                                Map<String, Object> q = new LinkedHashMap<>();
                                q.put("key", qp[0]);
                                q.put("value", qp[1]);
                                qpList.add(q);
                        }
                        urlMap.put("query", qpList);
                }

                request.put("url", urlMap);
                item.put("request", request);
                return item;
        }

        /**
         * FIX UPLOAD FORM-DATA ITEM
         * Overrides the body of the upload/form-data endpoint from DB (which stores raw
         * JSON)
         * to the correct multipart/form-data format, and fixes the URL to use
         * {{cpaiRequestID}}.
         */
        @SuppressWarnings("unchecked")
        private void fixUploadFormDataItem(Map<String, Object> itemMap) {
                try {
                        Map<String, Object> request = (Map<String, Object>) itemMap.get("request");
                        if (request == null)
                                return;

                        // Fix URL: ensure it ends with {{cpaiRequestID}}
                        Map<String, Object> urlObj = (Map<String, Object>) request.get("url");
                        if (urlObj != null) {
                                String rawUrl = (String) urlObj.getOrDefault("raw", "");
                                // Replace any broken variable at the end with the correct one
                                rawUrl = rawUrl.replaceAll("/\\{\\{[^}]*\\}\\}$", "/{{cpaiRequestID}}")
                                                .replaceAll("/requestId$", "/{{cpaiRequestID}}")
                                                .replaceAll("/\\{\\{id\\}\\}$", "/{{cpaiRequestID}}");
                                urlObj.put("raw", rawUrl);
                                // Fix host to use old_api_url
                                urlObj.put("host", Collections.singletonList("{{old_api_url}}"));
                                request.put("url", urlObj);
                        }

                        // Override body: replace raw JSON with correct form-data
                        Map<String, Object> bodyMap = new LinkedHashMap<>();
                        bodyMap.put("mode", "formdata");
                        List<Map<String, Object>> formFields = new ArrayList<>();
                        for (String[] field : new String[][] {
                                        { "contractFileName", "document.docx", "text" },
                                        { "isAI", "false", "text" },
                                        { "isFinalSignedCopy", "false", "text" },
                                        { "isConvertToWord", "false", "text" },
                                        { "finalSignedDate", "", "text" }
                        }) {
                                Map<String, Object> f = new LinkedHashMap<>();
                                f.put("key", field[0]);
                                f.put("value", field[1]);
                                f.put("type", field[2]);
                                formFields.add(f);
                        }
                        // The contractFile binary field
                        Map<String, Object> fileField = new LinkedHashMap<>();
                        fileField.put("key", "contractFile");
                        fileField.put("type", "file");
                        fileField.put("src", "");
                        formFields.add(fileField);
                        bodyMap.put("formdata", formFields);
                        request.put("body", bodyMap);
                        itemMap.put("request", request);
                } catch (Exception e) {
                        System.err.println("Error fixing upload form-data item: " + e.getMessage());
                }
        }

        /**
         * FIX UPLOAD JSON-DATA ITEM
         * Fixes the URL variable to use {{cpaiRequestID}} and ensures host is
         * old_api_url.
         */
        @SuppressWarnings("unchecked")
        private void fixUploadJsonDataItem(Map<String, Object> itemMap) {
                try {
                        Map<String, Object> request = (Map<String, Object>) itemMap.get("request");
                        if (request == null)
                                return;

                        // Fix URL: ensure it ends with {{cpaiRequestID}}
                        Map<String, Object> urlObj = (Map<String, Object>) request.get("url");
                        if (urlObj != null) {
                                String rawUrl = (String) urlObj.getOrDefault("raw", "");
                                rawUrl = rawUrl.replaceAll("/\\{\\{[^}]*\\}\\}$", "/{{cpaiRequestID}}")
                                                .replaceAll("/requestId$", "/{{cpaiRequestID}}")
                                                .replaceAll("/\\{\\{id\\}\\}$", "/{{cpaiRequestID}}");
                                urlObj.put("raw", rawUrl);
                                urlObj.put("host", Collections.singletonList("{{old_api_url}}"));
                                request.put("url", urlObj);
                        }

                        // Ensure JSON body has the correct structure per documentation
                        Map<String, Object> bodyMap = new LinkedHashMap<>();
                        bodyMap.put("mode", "raw");
                        bodyMap.put("raw", "{\n" +
                                        "  \"contractFile\": \"<Base64_encoded_file_content_here>\",\n" +
                                        "  \"contractFileName\": \"document.docx\",\n" +
                                        "  \"isAI\": false,\n" +
                                        "  \"isFinalSignedCopy\": false,\n" +
                                        "  \"isConvertToWord\": false,\n" +
                                        "  \"finalSignedDate\": null\n" +
                                        "}");
                        Map<String, Object> options = new LinkedHashMap<>();
                        Map<String, Object> rawOpt = new LinkedHashMap<>();
                        rawOpt.put("language", "json");
                        options.put("raw", rawOpt);
                        bodyMap.put("options", options);
                        request.put("body", bodyMap);
                        itemMap.put("request", request);
                } catch (Exception e) {
                        System.err.println("Error fixing upload json-data item: " + e.getMessage());
                }
        }
}