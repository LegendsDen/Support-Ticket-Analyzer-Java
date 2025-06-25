package com.support.analyzer.spring_server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OpenAIService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

    @Value("${openai.api.uri}")
    private String OpenAiUri;

    private WebClient openAiClient;

    @PostConstruct
    public void initClient() {
        this.openAiClient = WebClient.builder()
                .baseUrl(OpenAiUri)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String summarizeMessages(String rawMessages) {
        try {
            String prompt = """
            Summarize the following message while:
            - Retain key technical issues, error messages, and problem descriptions
            - Keep important context and steps to reproduce
            - Remove greetings, signatures, and conversational phrases
            - Format in clear, concise technical language
            
            Message: %s
            """.formatted(rawMessages);

            return generateResponse(prompt);
        } catch (Exception e) {
            log.error("OpenAI Summarization Error: {}", e.getMessage(), e);
            return null;
        }
    }

    public String generateResponse(String prompt) {
        try {
            String requestBody = """
                {
                    "model": "gpt-4o",
                    "max_tokens": 16000,
                    "client_identifier": "spr-ui-dev",
                    "messages": [
                        {
                            "role": "user",
                            "content": %s
                        }
                    ]
                }
            """.formatted(jsonEscape(prompt));

            return openAiClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractSummaryFromResponse)
                    .block();
        } catch (Exception e) {
            log.error("OpenAI Error: " + e.getMessage());
            return null;
        }
    }

    private String extractSummaryFromResponse(String response) {
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("Failed to parse OpenAI response: " + e.getMessage());
            return null;
        }
    }

    private String jsonEscape(String text) {
        return "\"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}