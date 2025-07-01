package com.support.analyzer.spring_server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbedBatchRequest {
    private List<EmbedItem> batches;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbedItem {
        private String ticketId;
        private String message;
    }
}
