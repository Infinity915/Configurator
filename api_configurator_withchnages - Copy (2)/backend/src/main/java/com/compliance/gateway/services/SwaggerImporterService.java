package com.compliance.gateway.services;

import com.compliance.gateway.models.ApiTemplate;
import com.compliance.gateway.repositories.ApiTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestTemplate;

@Service
public class SwaggerImporterService {

    @Autowired
    private ApiTemplateRepository templateRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public void importFromUrl(String swaggerUrl) throws Exception {
        // 1. Bypass SSL certificate validation for staging environments
        javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
            }
        };
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        // 2. Set explicit timeouts
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);

        RestTemplate restTemplate = new RestTemplate(factory);
        org.springframework.http.HttpHeaders httpHeaders = new org.springframework.http.HttpHeaders();
        httpHeaders.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>("parameters", httpHeaders);
        
        // 3. Simple retry mechanism
        int retries = 3;
        String jsonResponse = null;
        while (retries > 0) {
            try {
                org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(swaggerUrl, org.springframework.http.HttpMethod.GET, entity, String.class);
                jsonResponse = response.getBody();
                break;
            } catch (Exception e) {
                retries--;
                if (retries == 0) throw e;
                Thread.sleep(2000);
            }
        }

        JsonNode rootNode = objectMapper.readTree(jsonResponse);
        importFromJsonNode(rootNode);
    }

    public void importFromJsonNode(JsonNode rootNode) throws Exception {
        JsonNode paths = rootNode.get("paths");

        List<ApiTemplate> templates = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> fields = paths.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = fields.next();
            String path = pathEntry.getKey();
            JsonNode methods = pathEntry.getValue();

            Iterator<Map.Entry<String, JsonNode>> methodFields = methods.fields();
            while (methodFields.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodFields.next();
                String httpMethod = methodEntry.getKey().toUpperCase();
                JsonNode operation = methodEntry.getValue();

                boolean isBusinessApi = path.contains("/business/");
                String apiFamily = isBusinessApi ? "BUSINESS" : "LEGACY";
                String operationId;
                if (isBusinessApi) {
                    String formattedPath = path.replaceFirst("^/?", "").replace("/", "_").replaceAll("v\\d+", "v{version}");
                    operationId = httpMethod + "__" + formattedPath;
                } else {
                    operationId = operation.has("operationId")
                            ? operation.get("operationId").asText()
                            : httpMethod + "_" + path.replace("/", "_");
                }

                String summary = operation.has("summary")
                        ? operation.get("summary").asText()
                        : operationId;

                ObjectNode postmanItem = objectMapper.createObjectNode();
                postmanItem.put("name", summary);

                ObjectNode request = objectMapper.createObjectNode();
                request.put("method", httpMethod);

                // 1. Inject Authorization & Content Headers
                ArrayNode headers = objectMapper.createArrayNode();
                ObjectNode authHeader = objectMapper.createObjectNode();
                authHeader.put("key", "Authorization");
                authHeader.put("value", "Bearer {{access_token}}");
                authHeader.put("type", "text");
                headers.add(authHeader);

                if (httpMethod.equals("POST") || httpMethod.equals("PUT") || httpMethod.equals("PATCH")) {
                    ObjectNode ctHeader = objectMapper.createObjectNode();
                    ctHeader.put("key", "Content-Type");
                    ctHeader.put("value", "application/json");
                    headers.add(ctHeader);
                }
                request.set("header", headers);

                // 2. THE SMOKING GUN: Dual API Architecture Routing
                boolean isOldApi = path.contains("{version}") || path.toLowerCase().contains("/v1/")
                        || path.toLowerCase().contains("/v2/") || path.toLowerCase().contains("/v3/");
                String targetHostVariable = isOldApi ? "{{old_api_url}}" : "{{new_api_url}}";

                ObjectNode urlObj = objectMapper.createObjectNode();

                String normalizedPath = path
                        .replaceAll("(?i)\\{tenantname\\}", "{{tenant_name}}")
                        .replaceAll("(?i)\\{tenant-name\\}", "{{tenant_name}}")
                        .replace("{version}", "v1")
                        .replaceAll("(?i)/Master/", "/v1/masters/")
                        .replaceAll("(?i)/Client/", "/client/");

                String rawUrl = targetHostVariable + normalizedPath;

                ArrayNode host = objectMapper.createArrayNode();
                host.add(targetHostVariable);
                urlObj.set("host", host);

                ArrayNode pathArray = objectMapper.createArrayNode();
                for (String segment : normalizedPath.split("/")) {
                    if (!segment.isEmpty()) {
                        pathArray.add(segment);
                    }
                }
                urlObj.set("path", pathArray);

                // 3. Parse Query Parameters
                ArrayNode queryArray = objectMapper.createArrayNode();
                if (operation.has("parameters")) {
                    for (JsonNode param : operation.get("parameters")) {
                        if (param.has("in") && param.get("in").asText().equals("query")) {
                            String paramName = param.get("name").asText();
                            ObjectNode queryParam = objectMapper.createObjectNode();
                            queryParam.put("key", paramName);

                            if (paramName.equalsIgnoreCase("requestorUsername")
                                    || paramName.equalsIgnoreCase("requesterUsername")) {
                                queryParam.put("value", "{{requestorEmail}}");
                            } else {
                                queryParam.put("value", "");
                            }
                            queryArray.add(queryParam);
                            rawUrl += (rawUrl.contains("?") ? "&" : "?") + paramName + "="
                                    + queryParam.get("value").asText();
                        }
                    }
                }
                urlObj.put("raw", rawUrl);
                urlObj.set("query", queryArray);
                request.set("url", urlObj);

                // 4. Generate JSON Request Body (POST/PUT/PATCH)
                if (httpMethod.equals("POST") || httpMethod.equals("PUT") || httpMethod.equals("PATCH")) {
                    ObjectNode bodyObj = objectMapper.createObjectNode();
                    bodyObj.put("mode", "raw");

                    String samplePayload = "{\n  \"description\": \"Replace with actual data\"\n}";

                    if (operation.has("requestBody") && operation.get("requestBody").has("content")) {
                        JsonNode content = operation.get("requestBody").get("content");
                        if (content.has("application/json") && content.get("application/json").has("schema")) {
                            JsonNode schemaNode = content.get("application/json").get("schema");
                            if (isBusinessApi) {
                                schemaNode = flattenSchema(schemaNode, rootNode, 0);
                            }
                            samplePayload = generateSampleJson(schemaNode, rootNode,
                                    httpMethod, path);
                        }
                    } else if (operation.has("parameters")) {
                        for (JsonNode param : operation.get("parameters")) {
                            if (param.has("in") && param.get("in").asText().equals("body") && param.has("schema")) {
                                JsonNode schemaNode = param.get("schema");
                                if (isBusinessApi) {
                                    schemaNode = flattenSchema(schemaNode, rootNode, 0);
                                }
                                samplePayload = generateSampleJson(schemaNode, rootNode, httpMethod, path);
                                break;
                            }
                        }
                    }

                    // AUTO-CHAINING FIX: Strip quotes from variables so Postman sends them as
                    // Integers!
                    // Turns "applicationId": "{{applicationId}}" into "applicationId":
                    // {{applicationId}}
                    String finalPayload = samplePayload.replaceAll("\"\\{\\{([a-zA-Z0-9_]+)\\}\\}\"", "{{$1}}");

                    bodyObj.put("raw", finalPayload);
                    ObjectNode options = objectMapper.createObjectNode();
                    ObjectNode rawOpts = objectMapper.createObjectNode();
                    rawOpts.put("language", "json");
                    options.set("raw", rawOpts);
                    bodyObj.set("options", options);
                    request.set("body", bodyObj);
                }

                postmanItem.set("request", request);

                // 5. AUTO-CHAINING: Inject Harvester Script into GET requests
                if (httpMethod.equals("GET")) {
                    ArrayNode events = objectMapper.createArrayNode();
                    ObjectNode testEvent = objectMapper.createObjectNode();
                    testEvent.put("listen", "test");

                    ObjectNode script = objectMapper.createObjectNode();
                    script.put("type", "text/javascript");

                    ArrayNode exec = objectMapper.createArrayNode();
                    exec.add("if (pm.response.code === 200) {");
                    exec.add("    var jsonData = pm.response.json();");
                    exec.add("    var items = jsonData.data || jsonData.items || jsonData;");
                    exec.add("    if (Array.isArray(items) && items.length > 0) {");
                    exec.add("        var firstItem = items[0];");
                    exec.add("        for (var key in firstItem) {");
                    exec.add("            if (key.toLowerCase().endsWith('id')) {");
                    exec.add("                pm.collectionVariables.set(key, firstItem[key]);");
                    exec.add("            }");
                    exec.add("        }");
                    exec.add("        console.log('Automated Data Harvester: Captured IDs from response.');");
                    exec.add("    }");
                    exec.add("}");

                    script.set("exec", exec);
                    testEvent.set("script", script);
                    events.add(testEvent);

                    postmanItem.set("event", events);
                }

                // 6. Generate JSON Response Body for Documentation
                ArrayNode responsesArray = objectMapper.createArrayNode();
                if (operation.has("responses") && operation.get("responses").has("200")) {
                    JsonNode response200 = operation.get("responses").get("200");
                    ObjectNode responseNode = objectMapper.createObjectNode();
                    responseNode.put("name", "Successful Response");
                    responseNode.put("status", "OK");
                    responseNode.put("code", 200);

                    String sampleResponse = "{}";

                    if (response200.has("content") && response200.get("content").has("application/json")
                            && response200.get("content").get("application/json").has("schema")) {
                        JsonNode schemaNode = response200.get("content").get("application/json").get("schema");
                        if (isBusinessApi) {
                            schemaNode = flattenSchema(schemaNode, rootNode, 0);
                        }
                        sampleResponse = generateSampleJson(schemaNode, rootNode, "GET",
                                path);
                    } else if (response200.has("schema")) {
                        JsonNode schemaNode = response200.get("schema");
                        if (isBusinessApi) {
                            schemaNode = flattenSchema(schemaNode, rootNode, 0);
                        }
                        sampleResponse = generateSampleJson(schemaNode, rootNode, "GET", path);
                    }

                    responseNode.put("body", sampleResponse);
                    responsesArray.add(responseNode);
                }
                postmanItem.set("response", responsesArray);

                ApiTemplate template = new ApiTemplate();
                template.setId(operationId);
                template.setApiName(summary);
                template.setIntegrationType(isOldApi ? "OLD_API" : "NEW_API");
                template.setApiFamily(path.startsWith("/business/") ? "BUSINESS" : "LEGACY");
                template.setRawPostmanTemplate(objectMapper.writeValueAsString(postmanItem));

                templates.add(template);
            }
        }

        // templateRepository.deleteAll(); // Removed to allow Upsert
        templateRepository.saveAll(templates);
    }

    // =========================================================================
    // SMART HELPER METHODS: Context-Aware Payload Generation
    // =========================================================================

    private String generateSampleJson(JsonNode schema, JsonNode rootNode, String httpMethod, String path) {
        try {
            JsonNode sampleNode = buildSampleNode(schema, rootNode, 0, "", httpMethod, path);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sampleNode);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode buildSampleNode(JsonNode schema, JsonNode rootNode, int depth, String propName, String httpMethod,
            String path) {
        if (depth > 10 || schema == null)
            return objectMapper.createObjectNode();

        if (schema.has("$ref")) {
            JsonNode resolved = resolveRef(schema.get("$ref").asText(), rootNode);
            return buildSampleNode(resolved, rootNode, depth + 1, propName, httpMethod, path);
        }

        String type = schema.has("type") ? schema.get("type").asText() : "object";
        String propLower = propName != null ? propName.toLowerCase() : "";

        // 1. Handle Object Types
        if ("object".equals(type) || schema.has("properties")) {
            ObjectNode obj = objectMapper.createObjectNode();
            if (schema.has("properties")) {
                List<String> requiredFields = new ArrayList<>();
                if (schema.has("required")) {
                    for (JsonNode req : schema.get("required")) {
                        requiredFields.add(req.asText());
                    }
                }

                Iterator<Map.Entry<String, JsonNode>> fields = schema.get("properties").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String childPropName = field.getKey();
                    JsonNode propSchema = field.getValue();

                    // CRITICAL EXCLUSION RULE: Skip troublesome optional relational metadata
                    if (childPropName.equals("parentPartyIds") || childPropName.equals("subsidiaryPartyIds") ||
                            childPropName.equals("parentExternalIds") || childPropName.equals("subsidiaryExternalIds")
                            ||
                            childPropName.equals("customFields") || childPropName.equals("contactNames") ||
                            childPropName.equals("clientAccessDetails")) {
                        continue;
                    }

                    if (requiredFields.contains(childPropName) || shouldIncludeOptionalField(childPropName)) {
                        obj.set(childPropName,
                                buildSampleNode(propSchema, rootNode, depth + 1, childPropName, httpMethod, path));
                    }
                }
            }
            return obj;
        }

        // 2. Handle Arrays
        else if ("array".equals(type)) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            if (schema.has("items")) {
                JsonNode sampleItem = buildSampleNode(schema.get("items"), rootNode, depth + 1, propName, httpMethod,
                        path);
                if (sampleItem != null && !sampleItem.isNull() && !sampleItem.isMissingNode()) {
                    arrayNode.add(sampleItem);
                }
            }
            return arrayNode;
        }

        // 3. Handle Strings
        else if ("string".equals(type)) {
            String format = schema.has("format") ? schema.get("format").asText() : "";

            if ("uuid".equals(format) || propLower.contains("guid") || propLower.endsWith("id") && format.isEmpty()) {
                if ("uuid".equals(format) || propLower.contains("guid")) {
                    return objectMapper.getNodeFactory().textNode("3fa85f64-5717-4562-b3fc-2c963f66afa6");
                }
            }

            if (propLower.contains("reminder")) {
                return objectMapper.getNodeFactory().textNode("2027-11-01T12:00:00Z");
            } else if ("date-time".equals(format) || "date".equals(format) || propLower.contains("date")) {
                return objectMapper.getNodeFactory().textNode("2027-12-31T12:00:00Z");
            }

            if ("email".equals(format) || propLower.contains("email") ||
                    propLower.equals("to") || propLower.equals("cc") || propLower.equals("bcc")) {
                return objectMapper.getNodeFactory().textNode("test.integration.{{$randomInt}}@contractpodai.com");
            }

            if (propLower.contains("phone") || propLower.contains("contactnumber") || propLower.contains("mobile")) {
                return objectMapper.getNodeFactory().textNode("1234567890");
            }

            if (propLower.contains("name") || propLower.contains("externalid") || propLower.contains("external_id")) {
                return objectMapper.getNodeFactory().textNode("Test " + propName + " {{$randomInt}}");
            }
            if (propLower.contains("url") || propLower.contains("link")) {
                return objectMapper.getNodeFactory().textNode("https://example.com");
            }

            return objectMapper.getNodeFactory()
                    .textNode(schema.has("example") ? schema.get("example").asText() : "test_value");
        }

        // 4. Handle Numeric Types (AUTO-CHAINING INJECTOR)
        else if ("integer".equals(type) || "number".equals(type)) {
            // Check for Primary Key in POST
            if ("POST".equals(httpMethod) && propLower.endsWith("id") && path != null) {
                String pathLower = path.toLowerCase();
                String entityName = pathLower.substring(pathLower.lastIndexOf("/") + 1);
                entityName = entityName.replace("-", "");
                if (propLower.equals(entityName + "id") || propLower.equals("id")) {
                    return objectMapper.getNodeFactory().numberNode(0);
                }
            }

            // Inject Postman variables for IDs!
            if (propLower.endsWith("id") && propName != null && !propName.isEmpty()) {
                // Returns a string initially so Jackson doesn't crash, the unquoter fixes it
                // later.
                return objectMapper.getNodeFactory().textNode("{{" + propName + "}}");
            }
            if (propLower.contains("typeid") || propLower.contains("status")) {
                return objectMapper.getNodeFactory().numberNode(1);
            }
            return objectMapper.getNodeFactory().numberNode(schema.has("example") ? schema.get("example").asInt() : 1);
        }

        // 5. Handle Boolean Types
        else if ("boolean".equals(type)) {
            if (propLower.contains("default") || propLower.contains("primary")) {
                return objectMapper.getNodeFactory().booleanNode(false);
            }
            return objectMapper.getNodeFactory()
                    .booleanNode(schema.has("example") ? schema.get("example").asBoolean() : true);
        }

        return objectMapper.getNodeFactory().textNode("unknown");
    }

    private boolean shouldIncludeOptionalField(String fieldName) {
        return !fieldName.contains("metadata") &&
                !fieldName.contains("audit") &&
                !fieldName.contains("tracking") &&
                !fieldName.endsWith("Details");
    }

    private JsonNode resolveRef(String ref, JsonNode rootNode) {
        if (ref.startsWith("#/")) {
            String[] parts = ref.substring(2).split("/");
            JsonNode current = rootNode;
            for (String part : parts) {
                if (current != null && current.has(part)) {
                    current = current.get(part);
                } else {
                    return null;
                }
            }
            return current;
        }
        return null;
    }
    private JsonNode flattenSchema(JsonNode schema, JsonNode rootNode, int depth) {
        if (depth > 10 || schema == null) return objectMapper.createObjectNode();
        if (schema.has("$ref")) {
            JsonNode resolved = resolveRef(schema.get("$ref").asText(), rootNode);
            return flattenSchema(resolved, rootNode, depth + 1);
        }
        if (schema.isObject()) {
            ObjectNode flattened = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                flattened.set(field.getKey(), flattenSchema(field.getValue(), rootNode, depth + 1));
            }
            return flattened;
        } else if (schema.isArray()) {
            ArrayNode flattened = objectMapper.createArrayNode();
            for (JsonNode item : schema) {
                flattened.add(flattenSchema(item, rootNode, depth + 1));
            }
            return flattened;
        }
        return schema;
    }
}