package com.support.analyzer.spring_server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

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
        You are a technical support analyst. Summarize the following conversation logs into a single clear paragraph.

        Guidelines:
        - Capture key technical issues, error messages, and system behaviors.
        - Include any important context or user actions that help identify the root cause.
        - Exclude greetings, sign-offs, and irrelevant chit-chat.
        - Write in concise, professional, and technical language suitable for internal ticket notes.

        Conversation Logs:
        %s
        """.formatted(rawMessages);

            return generateResponse(prompt);
        } catch (Exception e) {
            log.error("OpenAI Summarization Error: {}", e.getMessage(), e);
            return null;
        }
    }

    public ElasticsearchSimilarInference generateCompleteInference(String originalMessage, List<ElasticsearchSimilarInference> similarTriplets) {
        try {
            log.debug("Generating simple complete inference with {} similar triplets", similarTriplets.size());

            // Join all similar triplets with issue\nrca\nsolution format
            StringBuilder tripletContext = new StringBuilder();
            tripletContext.append("Similar resolved support tickets:\n\n");

            for (int i = 0; i < similarTriplets.size(); i++) {
                ElasticsearchSimilarInference triplet = similarTriplets.get(i);
                tripletContext.append(String.format("Ticket %d:\n", i + 1));
                tripletContext.append("issue: ").append(triplet.getIssue()).append("\n");
                tripletContext.append("rca: ").append(triplet.getRca()).append("\n");
                tripletContext.append("solution: ").append(triplet.getSolution()).append("\n\n");
            }

            // Single comprehensive prompt
            String comprehensivePrompt = "Based on the following original support ticket message and similar resolved tickets, " +
                    "analyze and provide: issue identification, root cause analysis, and recommended solution. " +
                    "Format your response as: Issue|RCA|Solution (separated by | character):\n\n " +
                    "Do NOT repeat the format or add any labels like \"Issue|RCA|Solution\"." +
                    "Original Message:\n" + originalMessage + "\n\n" + tripletContext.toString();

            String response = generateResponse(comprehensivePrompt);
            log.info("Generated comprehensive inference response: {}", response);

            if (response == null || response.isBlank()) {
                log.warn("Failed to generate comprehensive inference");
                return null;
            }

            // Parse the pipe-separated response
            String[] parts = response.trim().split("\\|");
            log.info("Parsed inference response parts: {}", (Object) parts);
            if (parts.length >= 3) {
                return new ElasticsearchSimilarInference(
                        parts[1].trim(), // RCA
                        parts[0].trim(), // Issue
                        parts[2].trim()  // Solution
                );
            } else {
                log.warn("Failed to parse comprehensive inference response: {}", response);
                return null;
            }

        } catch (Exception e) {
            log.error("Error generating simple complete inference: {}", e.getMessage());
            return null;
        }
    }
    public TicketTriplet generateTicketTriplet(String ticketId, String summary) {
        try {
            log.debug("Generating triplet for ticket: {}", ticketId);

            String rcaPrompt = """
            Based on the following support ticket summary, identify the root cause analysis (RCA).
            -  return only the root cause without extra explanation.
            
            Summary:
            %s
            """.formatted(summary);

            String rca = generateResponse(rcaPrompt);

            if (rca == null || rca.isBlank()) {
                log.warn("Failed to generate RCA for ticket: {}", ticketId);
                return null;
            }

            // Generate Issue
            String issuePrompt = "Based on the following support ticket summary, identify the main issue. " +
                    "Provide only the issue description without additional explanation: " + summary;
            String issue = generateResponse(issuePrompt);

            if (issue == null || issue.isBlank()) {
                log.warn("Failed to generate issue for ticket: {}", ticketId);
                return null;
            }

            // Generate Solution
            String solutionPrompt = "Based on the following support ticket summary, provide the solution. " +
                    "Provide only the solution without additional explanation: " + summary;
            String solution = generateResponse(solutionPrompt);

            if (solution == null || solution.isBlank()) {
                log.warn("Failed to generate solution for ticket: {}", ticketId);
                return null;
            }

            // Create and return TicketTriplet
            TicketTriplet triplet = new TicketTriplet();
            triplet.setTicketId(ticketId);
            triplet.setRca(rca.trim());
            triplet.setIssue(issue.trim());
            triplet.setSolution(solution.trim());

            log.debug("Generated triplet for ticket {}: RCA={}, Issue={}, Solution={}",
                    ticketId, rca.length(), issue.length(), solution.length());

            return triplet;

        } catch (Exception e) {
            log.error("Error generating triplet for ticket {}: {}", ticketId, e.getMessage());
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