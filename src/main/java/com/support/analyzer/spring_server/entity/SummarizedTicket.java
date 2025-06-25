package com.support.analyzer.spring_server.entity;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "summarized_tickets")
@Data
@NoArgsConstructor
@AllArgsConstructor
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
