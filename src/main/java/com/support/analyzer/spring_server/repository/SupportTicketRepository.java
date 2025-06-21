package com.support.analyzer.spring_server.repository;

import com.support.analyzer.spring_server.entity.SupportTicket;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SupportTicketRepository extends MongoRepository<SupportTicket,String> {
}
