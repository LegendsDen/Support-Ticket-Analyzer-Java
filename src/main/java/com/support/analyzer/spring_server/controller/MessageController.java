package com.support.analyzer.spring_server.controller;

import com.support.analyzer.spring_server.entity.Message;
import com.support.analyzer.spring_server.entity.SupportTicket;
import com.support.analyzer.spring_server.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
public class MessageController {
    @Autowired
    private MessageService messageService;

    @PostMapping("/ticket")
    public ResponseEntity<SupportTicket> saveSupportTicket(@RequestBody SupportTicket supportTicket) {
        try{
            SupportTicket savedSupportTicket = messageService.saveSupportTicket(supportTicket);
            return ResponseEntity.ok(savedSupportTicket);
        }
        catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }

    }

    @GetMapping("/ticket")
    public ResponseEntity<List<SupportTicket>> getAllSupportTicket() {
        List<SupportTicket> supportTickets = messageService.getAllSupportTicket();
        return ResponseEntity.ok(supportTickets);
    }

    @PostMapping("/ticket/{ticketId}/message")
   public ResponseEntity<Void> addMessageToTicket(@PathVariable String ticketId, @RequestBody Message message) {
        messageService.addMessageToTicket(ticketId, message);
        return ResponseEntity.ok().build();
    }
}
