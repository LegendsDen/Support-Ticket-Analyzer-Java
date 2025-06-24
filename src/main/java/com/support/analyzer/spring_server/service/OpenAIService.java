package com.support.analyzer.spring_server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;


@Service
public class OpenAIService {
    private WebClient openAiClient;

    @PostConstruct
    public void initClient() {
        this.openAiClient = WebClient.builder()
                .baseUrl("http://prod0-intuitionx-llm-router-v2.sprinklr.com/chat-completion")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String summarizeMessages(String rawMessages) {
        try {
            String prompt = "Give only issue in for following, : " + rawMessages;

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
            System.err.println("OpenAI Error: " + e.getMessage());
            return null;
        }
    }

    private String extractSummaryFromResponse(String response) {
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            System.err.println("Failed to parse OpenAI response: " + e.getMessage());
            return null;
        }
    }

    private String jsonEscape(String text) {
        return "\"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
