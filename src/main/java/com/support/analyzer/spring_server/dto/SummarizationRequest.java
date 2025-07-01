package com.support.analyzer.spring_server.dto;

import lombok.Data;

@Data
public class SummarizationRequest {
    private final String ticketId;
    private final String content;

    public SummarizationRequest(String ticketId, String content) {
        this.ticketId = ticketId;
        this.content = content;
    }
}
