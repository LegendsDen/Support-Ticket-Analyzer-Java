package com.support.analyzer.spring_server.repository;

import com.support.analyzer.spring_server.entity.TicketTriplet;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TicketTripletRepository extends MongoRepository<TicketTriplet,String> {
}
