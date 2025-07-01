package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.EmbedBatchRequest;
import com.support.analyzer.spring_server.dto.FlaskEmbeddingResponse;
import com.support.analyzer.spring_server.dto.FlaskEmbeddingResponseItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;


@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${flask.baseurl}")
    private String flaskBaseUrl;

    private WebClient embeddingClient;

    @PostConstruct
    public void initClient() {
        this.embeddingClient = WebClient.builder()
                .baseUrl(flaskBaseUrl)
                .build();
    }

    public List<Double> getEmbedding(String ticketId, String message) {
        try {
            return embeddingClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new EmbedRequest(message))
                    .retrieve()
                    .bodyToMono(FlaskEmbeddingResponse.class)
                    .map(FlaskEmbeddingResponse::getEmbedding)
                    .block();
        } catch (Exception e) {
            log.error("Embedding Error: " + e.getMessage());
            return null;
        }
    }

    public List<FlaskEmbeddingResponseItem> getEmbeddingsBatch(List<EmbedBatchRequest.EmbedItem> batch) {
        try {
            return embeddingClient.post()
                    .uri("/embed_batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new EmbedBatchRequest(batch))
                    .retrieve()
                    .bodyToFlux(FlaskEmbeddingResponseItem.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.error("Batch embedding error: {}", e.getMessage());
            return List.of();
        }
    }

    private record EmbedRequest(String message) {
    }
}