package com.support.analyzer.spring_server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TripletWithEmbedding {
    private String ticketId;
    private String rca;
    private String issue;
    private String solution;
    private List<Double> issueEmbedding;
}