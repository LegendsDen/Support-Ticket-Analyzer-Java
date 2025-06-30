package com.support.analyzer.spring_server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class OpenAIService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

    @Value("${openai.api.uri}")
    private String OpenAiUri;

    @Value("${openai.concurrent.requests.limit}")
    private int maxConcurrentRequests;

    @Value("${openai.request.timeout.seconds}")
    private int requestTimeoutSeconds;

    private WebClient openAiClient;
    private Semaphore requestSemaphore;

    @PostConstruct
    public void initClient() {
        this.openAiClient = WebClient.builder()
                .baseUrl(OpenAiUri)
                .defaultHeader("Content-Type", "application/json")
                .build();

        this.requestSemaphore = new Semaphore(maxConcurrentRequests, true); // Fair semaphore
        log.info("Initialized OpenAI service with max {} concurrent requests", maxConcurrentRequests);
    }

    @PreDestroy
    public void cleanup() {
        if (requestSemaphore != null) {
            log.info("Shutting down OpenAI service, waiting for {} active requests to complete",
                    maxConcurrentRequests - requestSemaphore.availablePermits());
        }
    }

    public String summarizeMessages(String rawMessages) {
        return executeWithSemaphore(() -> {
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
                log.error("OpenAI Summarization Error: {}", e, e);
                return null;
            }
        });
    }

    public TicketTriplet generateCompleteInference(String ticketId,String originalMessage, List<ElasticsearchSimilarInference> similarTriplets) {
        return executeWithSemaphore(() -> {
            try {


                log.debug("Generating complete inference with {} similar triplets", similarTriplets.size());

                StringBuilder tripletContext = new StringBuilder();
                tripletContext.append("Similar resolved support tickets:\n\n");

                for (int i = 0; i < similarTriplets.size(); i++) {
                    ElasticsearchSimilarInference triplet = similarTriplets.get(i);
                    tripletContext.append(String.format("Ticket %d:\n", i + 1));
                    tripletContext.append("issue: ").append(triplet.getIssue()).append("\n");
                    tripletContext.append("rca: ").append(triplet.getRca()).append("\n");
                    tripletContext.append("solution: ").append(triplet.getSolution()).append("\n\n");
                }

                String comprehensivePrompt = "Based on the following original support ticket message and similar resolved tickets, " +
                        "analyze and provide: issue identification, root cause analysis, and recommended solution. " +
                        "The given issue may be an enhancement so take that into account. Also give clear RCA and solution with bullet points. Make sure all the fields are text only and not an array."+
                        "Return in json format without any markdown or code block formatting with fields: rca, issue, solution."+
                        "Original Message:\n" + originalMessage + "\n\n" + tripletContext;

                String response = generateResponse(comprehensivePrompt);
                log.info("Generated comprehensive inference response: {}", response);

                if (response == null || response.isBlank()) {
                    log.warn("Failed to generate comprehensive inference");
                    return null;
                }
                TicketTriplet triplet = new TicketTriplet();

                try {
                    JsonNode root = new ObjectMapper().readTree(response);
                    triplet.setTicketId(ticketId);
                    triplet.setRca(root.path("rca").asText());
                    triplet.setIssue(root.path("issue").asText());
                    triplet.setSolution(root.path("solution").asText());
                } catch (Exception e) {
                    log.error("Failed to parse OpenAI response for ticket {}: {}", ticketId, e);
                    return null;
                }
                return triplet;
            } catch (Exception e) {
                log.error("Error generating complete inference: {}", e.getMessage());
                return null;
            }


        });
    }

    public TicketTriplet generateTicketTriplet(String ticketId, String summary) {
        return executeWithSemaphore(() -> {
            try {
                log.debug("Generating triplet for ticket: {}", ticketId);

                String prompt = """ 
                                Based on the following original support ticket message and similar resolved tickets, " +
                                "analyze and provide: issue identification, root cause analysis, and recommended solution. " +
                                "The given issue may be an enhancement so take that into account. Also give clear RCA and solution with bullet points. Make sure all the fields are text only and not an array."+
                                Return in json format without any markdown or code block formatting with fields: rca, issue, solution.
                
                Summary:
                %s
                """.formatted(summary);

                String response = generateResponse(prompt);

                if (response == null || response.isBlank()) {
                    log.warn("Failed to generate RCA for ticket: {}", ticketId);
                    return null;
                }
                TicketTriplet triplet = new TicketTriplet();
                try {
                    JsonNode root = new ObjectMapper().readTree(response);
                    triplet.setTicketId(ticketId);
                    triplet.setRca(root.path("rca").asText());
                    triplet.setIssue(root.path("issue").asText());
                    triplet.setSolution(root.path("solution").asText());
                } catch (Exception e) {
                    log.error("Failed to parse OpenAI response for ticket {}: {}", ticketId, e.getMessage());
                    return null;
                }
                log.info("Generated triplet for ticket {}: {}", ticketId, triplet);

                return triplet;

            } catch (Exception e) {
                log.error("Error generating triplet for ticket {}: {}", ticketId, e.getMessage());
                return null;
            }
        });
    }

    private <T> T executeWithSemaphore(java.util.function.Supplier<T> operation) {
        try {
            if (requestSemaphore.tryAcquire(requestTimeoutSeconds, TimeUnit.SECONDS)) {
                try {
                    log.debug("Acquired OpenAI request permit. Available permits: {}", requestSemaphore.availablePermits());
                    return operation.get();
                } finally {
                    requestSemaphore.release();
                    log.debug("Released OpenAI request permit. Available permits: {}", requestSemaphore.availablePermits());
                }
            } else {
                log.warn("Failed to acquire OpenAI request permit within {} seconds. Request dropped.", requestTimeoutSeconds);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while waiting for OpenAI request permit", e);
            return null;
        }
    }

    public String generateResponse(String prompt) {
        try {
            String requestBody = """
            {
                "model": "gpt-4",
                "max_tokens": 4096,
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
                    .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                            .filter(throwable -> throwable instanceof WebClientResponseException
                                    && (((WebClientResponseException) throwable).getStatusCode().value() == 429
                                    || ((WebClientResponseException) throwable).getStatusCode().value() == 400))
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                                WebClientResponseException ex = (WebClientResponseException) retrySignal.failure();
                                log.error("Failed after 3 retries. Status: {}, Response: {}",
                                        ex.getStatusCode(), ex.getResponseBodyAsString());
                                return retrySignal.failure();
                            }))
                    .doOnError(error -> log.error("Request failed: {}", error.getMessage()))
                    .map(this::extractSummaryFromResponse)
                    .block();
        } catch (Exception e) {
            log.error("OpenAI Error: {}", e.getMessage());
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

    // Monitoring methods
    public int getAvailablePermits() {
        return requestSemaphore.availablePermits();
    }

    public int getQueueLength() {
        return requestSemaphore.getQueueLength();
    }

    public boolean hasQueuedThreads() {
        return requestSemaphore.hasQueuedThreads();
    }

    public boolean isAtCapacity() {
        return requestSemaphore.availablePermits() == 0;
    }
}