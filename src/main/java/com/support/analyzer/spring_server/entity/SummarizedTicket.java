package com.support.analyzer.spring_server.entity;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "summarized_tickets")
@Data
public class SummarizedTicket {
    @Id
    private String ticketId;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public SummarizedTicket(String ticketId, String summary) {
        this.ticketId = ticketId;
        this.summary = summary;
    }

}
