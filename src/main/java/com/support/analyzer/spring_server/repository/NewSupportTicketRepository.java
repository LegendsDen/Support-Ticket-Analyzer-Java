package com.support.analyzer.spring_server.repository;

import com.support.analyzer.spring_server.entity.NewSupportTicket;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NewSupportTicketRepository extends MongoRepository<NewSupportTicket,String> {
}
