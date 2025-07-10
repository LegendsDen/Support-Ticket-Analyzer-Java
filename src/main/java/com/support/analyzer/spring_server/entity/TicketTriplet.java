package com.support.analyzer.spring_server.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "ticket_triplets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketTriplet {
    @Id
    private String ticketId;
    private String issue;
    private String rca;

    private String solution;
}
