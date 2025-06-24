package com.support.analyzer.spring_server.controller;


import com.support.analyzer.spring_server.service.EmbeddingService;
import com.support.analyzer.spring_server.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheck {

    @Autowired
    private OpenAIService openAiService;

    @Autowired
    private EmbeddingService embeddingService;

    @GetMapping("/health-check")
    public String healthCheck() {

        return "OK";
    }
    @PostMapping("/openAi-health-check")
    public String openAiHealthCheck() {
        try {
            String result = openAiService.summarizeMessages(
                    "Test message for health check."
            );
            return "OpenAI reachable: " + (result != null && !result.isEmpty() ? "OK" : "No response");
        } catch (Exception e) {
            return "OpenAI unreachable: " + e.getMessage();
        }
    }
    @PostMapping("/embedding-health-check")
    public String embeddingHealthCheck() {
        try {
            Object embedding = embeddingService.getEmbedding("1","Test embedding health check.");
            return "Embedding API reachable: " + (embedding != null ? "OK" : "No response");
        } catch (Exception e) {
            return "Embedding API unreachable: " + e.getMessage();
        }
    }
}
