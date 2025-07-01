package com.support.analyzer.spring_server.dto;

import lombok.Data;

@Data
public class SummarizationResponse {
    private final String ticketId;
    private final String summary;
    private final boolean success;
    private final String errorMessage;

    public SummarizationResponse(String ticketId, String summary, boolean success, String errorMessage) {
        this.ticketId = ticketId;
        this.summary = summary;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // Success constructor
    public static SummarizationResponse success(String ticketId, String summary) {
        return new SummarizationResponse(ticketId, summary, true, null);
    }

    // Failure constructor
    public static SummarizationResponse failure(String ticketId, String errorMessage) {
        return new SummarizationResponse(ticketId, null, false, errorMessage);
    }
}
