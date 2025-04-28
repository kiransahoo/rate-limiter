//package com.ratelimiter.demo;
//
//import com.ratelimiter.annotation.RateLimited;
//import com.ratelimiter.config.RateLimiterConfig;
//import com.ratelimiter.model.MessageStatus;
//import com.ratelimiter.repository.MessageRepository;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.boot.autoconfigure.domain.EntityScan;
//import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
//import org.springframework.scheduling.annotation.EnableScheduling;
//import org.springframework.stereotype.Component;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.http.ResponseEntity;
//
//import java.util.UUID;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//
//@SpringBootApplication(scanBasePackages = {
//        "com.ratelimiter",          // Core rate limiter package
//        "com.ratelimiter.config",   // Configuration
//        "com.ratelimiter.core",     // Core components
//        "com.ratelimiter.model",    // Entity models
//        "com.ratelimiter.repository", // Repositories
//        "com.ratelimiter.demo"     // Demo package
//})
//@EntityScan(basePackages = {"com.ratelimiter.model"})
//@EnableJpaRepositories(basePackages = {"com.ratelimiter.repository"})
//@EnableScheduling
//public class RateLimiterDemo implements CommandLineRunner {
//
//    @Autowired
//    private DemoController controller;
//
//    @Autowired
//    private MessageRepository messageRepository;
//
//    @Autowired
//    private StatsMonitor statsMonitor;
//
//    public static void main(String[] args) {
//        SpringApplication.run(RateLimiterDemo.class, args);
//    }
//
//    @Override
//    public void run(String... args) throws Exception {
//        System.out.println("\n===== RATE LIMITER DEMO =====");
//        System.out.println("Memory buffer size: 3");
//        System.out.println("Rate limit: 2 messages/second");
//        System.out.println("Sending 10 messages rapidly...\n");
//
//        // Start stats monitoring
//        statsMonitor.startMonitoring();
//
//        // Clear any previous messages
//        System.out.println("Clearing previous messages...");
//        messageRepository.deleteAll();
//        controller.resetCounter();
//
//        // Send 10 messages rapidly to demonstrate buffering and overflow
//        for (int i = 1; i <= 10; i++) {
//            Message message = new Message(UUID.randomUUID().toString(), "Message " + i);
//
//            System.out.println("\nSending message " + i + " (ID: " + message.getId() + ")");
//            ResponseEntity<?> response = controller.processMessage(message);
//
//            if (response.getStatusCodeValue() == 200) {
//                System.out.println("✓ Response: 200 OK (Processed Immediately)");
//            } else {
//                System.out.println("⟳ Response: " + response.getStatusCodeValue() + " (Buffered for Later)");
//            }
//
//            // Almost no delay to ensure we hit rate limits
//            TimeUnit.MILLISECONDS.sleep(50);
//        }
//
//        // Allow time for background processing
//        System.out.println("\nWaiting for background processing to complete...");
//
//        // Wait and check stats until all messages are processed or timeout
//        for (int i = 0; i < 30; i++) {
//            if (controller.getProcessedCount() >= 10) {
//                System.out.println("\nAll messages processed!");
//                break;
//            }
//            TimeUnit.SECONDS.sleep(1);
//        }
//
//        // Stop monitoring
//        statsMonitor.stopMonitoring();
//
//        System.out.println("\n===== FINAL STATS =====");
//        System.out.println("Messages processed by controller: " + controller.getProcessedCount());
//        System.out.println("Messages in database: " + messageRepository.count());
//        System.out.println("Pending messages: " + messageRepository.countByStatus(MessageStatus.PENDING));
//        System.out.println("\n===== DEMO COMPLETE =====");
//    }
//
//    // Simple message class for demo
//    public static class Message {
//        private String id;
//        private String content;
//
//        public Message() {}
//
//        public Message(String id, String content) {
//            this.id = id;
//            this.content = content;
//        }
//
//        public String getId() { return id; }
//        public void setId(String id) { this.id = id; }
//        public String getContent() { return content; }
//        public void setContent(String content) { this.content = content; }
//
//        @Override
//        public String toString() {
//            return "Message{id='" + id + "', content='" + content + "'}";
//        }
//    }
//
//    // Controller with rate-limited endpoint
//    @Controller
//    public static class DemoController {
//        private final AtomicInteger processedCount = new AtomicInteger(0);
//
//        @PostMapping("/api/messages")
//        @RateLimited(
//                ratePerSecond = 2,         // Low rate to demonstrate buffering
//                maxConcurrency = 1,
//                memoryBufferSize = 3       // Small buffer to demonstrate overflow
//        )
//        public ResponseEntity<?> processMessage(@RequestBody Message message) {
//            // Increment counter and log
//            int count = processedCount.incrementAndGet();
//            System.out.println(">>> CONTROLLER: Processed message " + message.getContent() +
//                    " (total: " + count + ")");
//
//            return ResponseEntity.ok("Processed: " + message.getId());
//        }
//
//        public int getProcessedCount() {
//            return processedCount.get();
//        }
//
//        public void resetCounter() {
//            processedCount.set(0);
//        }
//    }
//
//    // Component to monitor stats periodically
//    @Component
//    public static class StatsMonitor {
//        @Autowired
//        private MessageRepository messageRepository;
//
//        @Autowired
//        private DemoController controller;
//
//        private volatile boolean monitoring = false;
//
//        public void startMonitoring() {
//            monitoring = true;
//        }
//
//        public void stopMonitoring() {
//            monitoring = false;
//        }
//
//        @org.springframework.scheduling.annotation.Scheduled(fixedRate = 1000) // Every second
//        public void reportStats() {
//            if (!monitoring) return;
//
//            try {
//                System.out.println("\n----- Current Stats -----");
//                System.out.println("Processed by controller: " + controller.getProcessedCount());
//                System.out.println("Messages in DB: " + messageRepository.count());
//                System.out.println("Pending messages: " + messageRepository.countByStatus(MessageStatus.PENDING));
//                System.out.println("------------------------");
//            } catch (Exception e) {
//                System.out.println("Error getting stats: " + e.getMessage());
//            }
//        }
//    }
//}