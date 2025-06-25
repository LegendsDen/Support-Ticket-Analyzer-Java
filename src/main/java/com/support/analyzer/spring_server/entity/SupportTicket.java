package com.support.analyzer.spring_server.entity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "support_tickets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicket {
    @Id
    private String ticketId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Message> messages;
}

