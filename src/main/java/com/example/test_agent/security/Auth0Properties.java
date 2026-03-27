package com.example.test_agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "auth0")
public class Auth0Properties {

    private String audience;
    private List<String> allowedEmails = List.of();

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public List<String> getAllowedEmails() { return allowedEmails; }
    public void setAllowedEmails(List<String> allowedEmails) { this.allowedEmails = allowedEmails; }
}
