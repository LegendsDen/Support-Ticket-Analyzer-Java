package com.support.analyzer.spring_server.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "ticket_triplets")
@Data
public class TicketTriplet {
    @Id
    private String ticketId;
    private String rca;
    private String issue;
    private String solution;
}
