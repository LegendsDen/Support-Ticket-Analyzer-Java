package com.support.analyzer.spring_server.controller;

import com.support.analyzer.spring_server.entity.Message;
import com.support.analyzer.spring_server.entity.NewSupportTicket;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.service.MongoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class MessageController {
    @Autowired
    private MongoService mongoService;

    @PostMapping("/ticket")
    public ResponseEntity<SupportTicket> saveSupportTicket(@RequestBody  SupportTicket supportTicket) {
        try{
            SupportTicket savedSupportTicket = mongoService.addSupportTicket(supportTicket);
            return ResponseEntity.ok(savedSupportTicket);
        }
        catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }

    }

    @GetMapping("/ticket")
    public ResponseEntity<List<SupportTicket>> getAllSupportTicket() {
        try{
            List<SupportTicket> supportTickets = mongoService.getAllSupportTicket();
            if (supportTickets.isEmpty()) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(supportTickets);

        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }

    }

    @PostMapping("/ticket/{ticketId}/message")
   public ResponseEntity<Void> addMessageToTicket(@PathVariable String ticketId, @RequestBody Message message) {

        mongoService.addMessageToTicket(ticketId, message);
        return ResponseEntity.ok().build();
    }
}
