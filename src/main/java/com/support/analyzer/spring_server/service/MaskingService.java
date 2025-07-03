package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.FlaskMaskedResponse;
import com.support.analyzer.spring_server.dto.FlaskMaskedResponseItem;
import com.support.analyzer.spring_server.dto.MaskBatchRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MaskingService {
    private static final Logger log = LoggerFactory.getLogger(MaskingService.class);

    @Value("${flask.baseurl}")
    private String flaskBaseUrl;

    private WebClient maskingClient;

    @PostConstruct
    public void initClient() {
        this.maskingClient = WebClient.builder()
                .baseUrl(flaskBaseUrl)
                .build();
    }

    public List<String> getMaskedMessages(String ticketId, List<String> messages) {
        try {

            return maskingClient.post()
                    .uri("/mask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new MaskRequest(messages))
                    .retrieve()
                    .bodyToMono(FlaskMaskedResponse.class)
                    .map(FlaskMaskedResponse::getMasked_messages)
                    .block();

        } catch (Exception e) {
            log.error("Masking error for ticket " + ticketId + ": " + e.getMessage());
            return null;
        }
    }

    public List<FlaskMaskedResponseItem> getMaskedMessagesBatch(List<MaskBatchRequest.MaskItem> batch) {
        try {
            log.info("Batch masking request for {} items", batch.size());

            List<FlaskMaskedResponseItem> rawResults = maskingClient.post()
                    .uri("/mask_batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("application/x-ndjson"))
                    .bodyValue(new MaskBatchRequest(batch))
                    .retrieve()
                    .bodyToFlux(FlaskMaskedResponseItem.class)
                    .doOnNext(item -> log.debug("Received masked result for ticket: {}", item.getTicketId()))
                    .collectList()
                    .block();

            // Merge chunks back together if needed
            List<FlaskMaskedResponseItem> mergedResults = mergeChunkedResults(rawResults);

            log.info("Processed {} raw results into {} merged results",
                    rawResults.size(), mergedResults.size());

            return mergedResults;

        } catch (Exception e) {
            log.error("Batch masking error: {}", e.getMessage());
            log.error("Failed batch ticket IDs: {}",
                    batch.stream()
                            .map(MaskBatchRequest.MaskItem::getTicketId)
                            .collect(Collectors.toList()));
            return List.of();
        }
    }

    private List<FlaskMaskedResponseItem> mergeChunkedResults(List<FlaskMaskedResponseItem> rawResults) {
        log.info("starting to merge chunked results, total raw results: {}", rawResults.size());
        // Group by base ticket ID (removing _chunk_X suffix if present)
        Map<String, List<FlaskMaskedResponseItem>> grouped = rawResults.stream()
                .collect(Collectors.groupingBy(item -> {
                    String ticketId = item.getTicketId();
                    // Remove chunk suffix if present
                    if (ticketId.contains("_chunk_")) {
                        return ticketId.substring(0, ticketId.indexOf("_chunk_"));
                    }
                    return ticketId;
                }));

        List<FlaskMaskedResponseItem> mergedResults = new ArrayList<>();

        for (Map.Entry<String, List<FlaskMaskedResponseItem>> entry : grouped.entrySet()) {
            String baseTicketId = entry.getKey();
            List<FlaskMaskedResponseItem> chunks = entry.getValue();

            if (chunks.size() == 1 && !chunks.get(0).getTicketId().contains("_chunk_")) {
                // Single, non-chunked result
                mergedResults.add(chunks.get(0));
            } else {
                // Multiple chunks - merge them
                List<String> allMaskedMessages = new ArrayList<>();
                chunks.stream()
                        .sorted((a, b) -> a.getTicketId().compareTo(b.getTicketId())) // Sort by chunk order
                        .forEach(chunk -> allMaskedMessages.addAll(chunk.getMaskedMessages()));

                log.info("Merged {} chunks for ticket {} into {} total messages",
                        chunks.size(), baseTicketId, allMaskedMessages.size());

                // Create merged result with original ticket ID
                FlaskMaskedResponseItem merged = new FlaskMaskedResponseItem(baseTicketId, allMaskedMessages);
                mergedResults.add(merged);
            }
        }

        return mergedResults;
    }

    private record MaskRequest(List<String> messages) {}
}