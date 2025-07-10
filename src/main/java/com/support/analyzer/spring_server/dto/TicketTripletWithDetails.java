package com.support.analyzer.spring_server.dto;

import com.support.analyzer.spring_server.entity.TicketTriplet;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class TicketTripletWithDetails extends TicketTriplet {
    private InferenceDetails details;

    @Data
    public static class InferenceDetails {
        private String maskedResponse;
        private String summary;
        private List<SimilarTicketInfo> similarTickets;
    }

    @Data
    public static class SimilarTicketInfo {
        private String issue;
        private String rca;
        private String solution;

    }

    public TicketTripletWithDetails(TicketTriplet triplet, String maskedResponse, String summary, List<SimilarTicketInfo> similarTickets) {

        super(triplet.getTicketId(), triplet.getIssue(), triplet.getRca(), triplet.getSolution());
        this.details = new InferenceDetails();
        this.details.maskedResponse = maskedResponse;
        this.details.summary = summary;
        this.details.similarTickets = similarTickets;
    }
}