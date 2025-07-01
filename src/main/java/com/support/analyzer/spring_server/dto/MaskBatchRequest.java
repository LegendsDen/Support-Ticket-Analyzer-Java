package com.support.analyzer.spring_server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaskBatchRequest {
    private List<MaskItem> batches;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaskItem {
        private String ticketId;
        private List<String> messages;
    }
}
