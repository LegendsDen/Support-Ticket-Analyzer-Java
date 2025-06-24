package com.support.analyzer.spring_server.service;

import com.support.analyzer.spring_server.dto.FlaskEmbeddingResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;


@Service
public class EmbeddingService {

    @Value("${flask.embedding.baseurl}")
    private String flaskBaseUrl;

    private WebClient embeddingClient;

    @PostConstruct
    public void initClient() {
        this.embeddingClient = WebClient.builder()
                .baseUrl(flaskBaseUrl)
                .build();
    }

    public List<Double> getEmbedding(String ticketId,String message) {
        try {
            return embeddingClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new EmbedRequest(ticketId, message))
                    .retrieve()
                    .bodyToMono(FlaskEmbeddingResponse.class)
                    .map(FlaskEmbeddingResponse::getEmbedding)
                    .block();
        } catch (Exception e) {
            System.err.println("Embedding Error: " + e.getMessage());
            return null;
        }
    }
    private record EmbedRequest(String ticketId, String message) {}
}
