package com.support.analyzer.spring_server.controller;


import com.support.analyzer.spring_server.service.ElasticsearchService;
import com.support.analyzer.spring_server.service.EmbeddingService;
import com.support.analyzer.spring_server.service.MaskingService;
import com.support.analyzer.spring_server.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class HealthCheck {

    @Autowired
    private OpenAIService openAiService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MaskingService maskingService;


    @Autowired
    private ElasticsearchService elasticsearchService;

    @GetMapping("/health-check")
    public String healthCheck() {

        return "OK";
    }
    @PostMapping("/openAi-health-check")
    public String openAiHealthCheck() {
        try {
            String result = openAiService.summarizeMessages(
                    "india plays very well, but lost cwc 2023 final cricket."
            );
            return result;
        } catch (Exception e) {
            return "OpenAI unreachable: " + e.getMessage();
        }
    }
    @PostMapping("/embedding-health-check")
    public Object embeddingHealthCheck() {
        try {
            Object embedding = embeddingService.getEmbedding("1","Test embedding health check.");
//            return "Embedding API reachable: " + (embedding != null ? "OK" : "No response");
            return embedding;
        } catch (Exception e) {
            return "Embedding API unreachable: " + e.getMessage();
        }
    }
    @PostMapping("/masking-health-check")
    public String maskingHealthCheck() {
        try {
            // Assuming you have a method in OpenAIService for masking
            List<String> result = maskingService.getMaskedMessages("1", List.of("Test masking Sushant Kumar  health check Sprinklr."));
            return "Masking API reachable: " + result;
        } catch (Exception e) {
            return "Masking API unreachable: " + e.getMessage();
        }
    }
    @PostMapping("/ES-health-check")
    public String testEmbeddingStorage(@RequestBody EmbeddingRequest request) {
        try {
            // Step 1: Call Flask server for embedding
            List<Double> embedding = embeddingService.getEmbedding(request.ticketId(), request.message());

            if (embedding == null || embedding.isEmpty()) {
                return "Failed to get embedding from Flask.";
            }

            // Step 2: Store embedding in ES
            elasticsearchService.indexEmbedding(request.ticketId(), embedding);
            return "Embedding stored in Elasticsearch for ticketId: " + request.ticketId();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private record EmbeddingRequest(String ticketId, String message) {}
}
