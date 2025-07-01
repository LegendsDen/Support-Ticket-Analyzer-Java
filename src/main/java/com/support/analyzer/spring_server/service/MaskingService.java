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

import java.util.List;

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

            return maskingClient.post()
                    .uri("/mask_batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new MaskBatchRequest(batch))
                    .retrieve()
                    .bodyToFlux(FlaskMaskedResponseItem.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.error("Batch masking error: {}", e.getMessage());
            return List.of();
        }
    }


    private record MaskRequest(List<String> messages) {}
}
