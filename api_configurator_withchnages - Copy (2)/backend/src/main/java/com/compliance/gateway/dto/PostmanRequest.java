package com.compliance.gateway.dto;

import java.util.List;

public class PostmanRequest {
    private String clientId;
    private String clientSecret;
    private String tenantName;
    private String requestorEmail;

    private String instanceUrl;

    private List<String> selectedApiIds;

    // Getters and Setters
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getRequestorEmail() {
        return requestorEmail;
    }

    public void setRequestorEmail(String requestorEmail) {
        this.requestorEmail = requestorEmail;
    }

    public String getInstanceUrl() {
        return instanceUrl;
    }

    public void setInstanceUrl(String instanceUrl) {
        this.instanceUrl = instanceUrl;
    }

    public List<String> getSelectedApiIds() {
        return selectedApiIds;
    }

    public void setSelectedApiIds(List<String> selectedApiIds) {
        this.selectedApiIds = selectedApiIds;
    }
}