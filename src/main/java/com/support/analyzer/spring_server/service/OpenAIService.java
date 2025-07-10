package com.support.analyzer.spring_server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.support.analyzer.spring_server.dto.ElasticsearchSimilarInference;
import com.support.analyzer.spring_server.dto.SummarizationRequest;
import com.support.analyzer.spring_server.dto.SummarizationResponse;
import com.support.analyzer.spring_server.entity.TicketTriplet;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
            - Capture key technical issues, error messages, and system behaviors in detail.
            - Include any important context or user actions that help identify the root cause.
            - Exclude greetings, sign-offs, and irrelevant chit-chat.
            - Write in detail, professional, and technical language suitable for internal ticket notes.

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
                        "The given issue may be an enhancement so take that into account. " +
                        "Also give clear and detailed RCA and solution using numbered points (1., 2., 3., etc.). " +
                        "If a point has multiple sentences, write them in one block — do not use \\n or newline characters. " +
                        "Ensure output is valid JSON, with all fields as plain strings only (no markdown, no code blocks, no arrays). " +
                        "Return only the following fields: rca, issue, solution." +

                        "\n\nUse language of words familiar to the original message and similar tickets.\n\n" +
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
    public List<SummarizationResponse> summarizeMessagesBatch(List<SummarizationRequest> requests) {
        return executeWithSemaphore(() -> {
            try {
                // Build a combined prompt for all tickets
                StringBuilder batchPrompt = new StringBuilder();
                batchPrompt.append("Summarize each of the following support ticket conversations into clear, concise paragraphs. " +
                        "For each ticket, provide a technical summary capturing key issues, error messages, and solutions.\n\n");

                // Add each ticket with a clear separator
                for (int i = 0; i < requests.size(); i++) {
                    batchPrompt.append("TICKET_").append(requests.get(i).getTicketId()).append(":\n");
                    batchPrompt.append(requests.get(i).getContent()).append("\n\n");
                }

                batchPrompt.append("\nProvide summaries in JSON format as an array of objects,  without any markdown or code block formatting each with 'ticketId' and 'summary' fields. " +
                        "Example format: [{\"ticketId\": \"TICKET_1\", \"summary\": \"...\"}, ...]");

                String response = generateResponse(batchPrompt.toString());
                if (response == null || response.isBlank()) {
                    return requests.stream()
                            .map(req -> SummarizationResponse.failure(req.getTicketId(), "Failed to generate summary"))
                            .toList();
                }

                try {
                    JsonNode root = new ObjectMapper().readTree(response);
                    List<SummarizationResponse> results = new ArrayList<>();

                    if (root.isArray()) {
                        for (JsonNode item : root) {
                            String ticketId = item.path("ticketId").asText().replace("TICKET_", "");
                            String summary = item.path("summary").asText();

                            if (summary != null && !summary.isBlank()) {
                                results.add(SummarizationResponse.success(ticketId, summary));
                            } else {
                                results.add(SummarizationResponse.failure(ticketId, "Empty summary received"));
                            }
                        }
                    }

                    // Handle any missing tickets
                    Set<String> processedIds = results.stream()
                            .map(SummarizationResponse::getTicketId)
                            .collect(Collectors.toSet());

                    requests.stream()
                            .map(SummarizationRequest::getTicketId)
                            .filter(id -> !processedIds.contains(id))
                            .forEach(id -> results.add(SummarizationResponse.failure(id, "No summary generated")));

                    return results;

                } catch (Exception e) {
                    log.error("Failed to parse batch summaries: {}", e.getMessage());
                    return requests.stream()
                            .map(req -> SummarizationResponse.failure(req.getTicketId(), "Failed to parse summary"))
                            .toList();
                }
            } catch (Exception e) {
                log.error("Batch summarization error: {}", e.getMessage());
                return requests.stream()
                        .map(req -> SummarizationResponse.failure(req.getTicketId(), "Summarization failed"))
                        .toList();
            }
        });
    }


    public List<TicketTriplet> generateTicketTripletsBatch(List<TripletBatchRequestItem> requests) {
        return executeWithSemaphore(() -> {
            try {
                // Construct prompt for batch triplet inference
                StringBuilder batchPrompt = new StringBuilder();
                batchPrompt.append("""
                    You are a technical support analyst.

                    For each of the following summarized support tickets, identify:
                    - The key technical issue
                    - The root cause (RCA)
                    - The recommended solution

                    Guidelines:
                    - The issue may also be an enhancement request — identify that if so.
                    - Provide detailed and clear RCA and solution in plain text.
                    - Avoid arrays or extra formatting.
                    - DO NOT wrap the response in markdown or code blocks.
                    - Provide the final output as a JSON array of objects with fields:
                      ticketId, issue, rca, solution.

                    Format:
                    [
                      {"ticketId": "TICKET_1", "issue": "...", "rca": "...", "solution": "..."},
                      ...
                    ]

                    Summaries:
                    """);

                for (TripletBatchRequestItem req : requests) {
                    batchPrompt.append("TICKET_").append(req.getTicketId()).append(":\n");
                    batchPrompt.append(req.getSummary()).append("\n\n");
                }

                String response = generateResponse(batchPrompt.toString());
                if (response == null || response.isBlank()) {
                    return requests.stream()
                            .map(req -> {
                                TicketTriplet t = new TicketTriplet();
                                t.setTicketId(req.getTicketId());
                                return t;
                            })
                            .toList();
                }

                try {
                    JsonNode root = new ObjectMapper().readTree(response);
                    List<TicketTriplet> triplets = new ArrayList<>();

                    if (root.isArray()) {
                        for (JsonNode item : root) {
                            String ticketId = item.path("ticketId").asText().replace("TICKET_", "");
                            String issue = item.path("issue").asText();
                            String rca = item.path("rca").asText();
                            String solution = item.path("solution").asText();

                            if (!issue.isBlank() && !rca.isBlank() && !solution.isBlank()) {
                                TicketTriplet t = new TicketTriplet();
                                t.setTicketId(ticketId);
                                t.setIssue(issue);
                                t.setRca(rca);
                                t.setSolution(solution);
                                triplets.add(t);
                            }
                        }
                    }

                    // Add missing IDs with empty triplet
                    Set<String> processedIds = triplets.stream()
                            .map(TicketTriplet::getTicketId)
                            .collect(Collectors.toSet());

                    requests.stream()
                            .map(TripletBatchRequestItem::getTicketId)
                            .filter(id -> !processedIds.contains(id))
                            .forEach(id -> {
                                TicketTriplet t = new TicketTriplet();
                                t.setTicketId(id);
                                triplets.add(t);
                            });

                    return triplets;

                } catch (Exception e) {
                    log.error("Failed to parse batch triplet response: {}", e.getMessage());
                    return requests.stream().map(req -> {
                        TicketTriplet t = new TicketTriplet();
                        t.setTicketId(req.getTicketId());
                        return t;
                    }).toList();
                }

            } catch (Exception e) {
                log.error("Batch triplet generation error: {}", e.getMessage());
                return requests.stream().map(req -> {
                    TicketTriplet t = new TicketTriplet();
                    t.setTicketId(req.getTicketId());
                    return t;
                }).toList();
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
//                log.info("Generated response for ticket {}: {}", ticketId, response);

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
            ObjectMapper mapper = new ObjectMapper();
            String requestBody = mapper.writeValueAsString(Map.of(
                    "model", "gpt-4",
                    "max_tokens", 16000,
                    "client_identifier", "spr-ui-dev",
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            ));

            return openAiClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(throwable ->
                                    (throwable instanceof WebClientResponseException &&
                                            (((WebClientResponseException) throwable).getStatusCode().value() == 429 ||
                                                    ((WebClientResponseException) throwable).getStatusCode().value() == 400)) ||
                                            (throwable instanceof WebClientRequestException &&
                                                    throwable.getCause() instanceof java.net.SocketException)
                            )
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                                Throwable ex = retrySignal.failure();
                                if (ex instanceof WebClientResponseException wcre) {
                                    log.error("Failed after retries. Status: {}, Response: {}",
                                            wcre.getStatusCode(), wcre.getResponseBodyAsString());
                                } else {
                                    log.error("Failed after retries. Exception: {}", ex.toString());
                                }
                                return ex;
                            }))


                    .doOnError(error -> log.error("Request failed: {}", error.getMessage()))
                    .map(this::extractSummaryFromResponse)
                    .block();

        } catch (Exception e) {
            log.error("OpenAI Error:", e);
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


    static class TripletBatchRequestItem {
    private String ticketId;
    private String summary;

    public TripletBatchRequestItem(String ticketId, String summary) {
        this.ticketId = ticketId;
        this.summary = summary;
    }

    public String getTicketId() { return ticketId; }
    public String getSummary() { return summary; }
}
}
