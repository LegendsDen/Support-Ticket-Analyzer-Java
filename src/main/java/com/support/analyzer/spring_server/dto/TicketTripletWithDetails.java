package com.support.analyzer.spring_server.dto;

import com.support.analyzer.spring_server.entity.TicketTriplet;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TicketTripletWithDetails extends TicketTriplet {
    private InferenceDetails details;

    @Data
    public static class InferenceDetails {
        private String maskedResponse;
        private String summary;
    }

    public TicketTripletWithDetails(TicketTriplet triplet, String maskedResponse, String summary) {
        super(triplet.getTicketId(), triplet.getRca(), triplet.getIssue(), triplet.getSolution());
        this.details = new InferenceDetails();
        this.details.maskedResponse = maskedResponse;
        this.details.summary = summary;
    }
}