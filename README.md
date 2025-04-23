# Spring Rate Limiter

A robust rate limiting library for Spring Boot applications that provides both immediate rate limiting and asynchronous buffering capabilities to prevent OOM errors and ensure system stability under high load.

## Overview

This library implements a hybrid buffering strategy that combines in-memory queues with database persistence. When a rate-limited method receives more requests than it can handle:

1. Requests within the rate limit are processed immediately
2. Excess requests go to an in-memory buffer
3. Once the memory buffer fills, additional requests overflow to the database
4. A background processor handles buffered messages at the configured rate

This approach balances performance (fast in-memory processing) with reliability (persistent database storage).

## Installation

Add the library to your project using Maven:

```xml
<dependency>
    <groupId>com.ratelimiter</groupId>
    <artifactId>rate-limiter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or with Gradle:

```groovy
implementation 'com.ratelimiter:rate-limiter:1.0.0'
```

## Database Setup

The library requires a single database table to store overflow messages. Create this table before using the rate limiter.

### MySQL

```sql
CREATE TABLE rate_limiter_messages (
    id VARCHAR(36) PRIMARY KEY,
    message_id VARCHAR(255),
    target_method VARCHAR(255) NOT NULL,
    payload_type VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(4000),
    INDEX idx_status_method (status, target_method),
    INDEX idx_created_at (created_at)
);
```

### PostgreSQL

```sql
CREATE TABLE rate_limiter_messages (
    id VARCHAR(36) PRIMARY KEY,
    message_id VARCHAR(255),
    target_method VARCHAR(255) NOT NULL,
    payload_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(4000)
);
CREATE INDEX idx_rate_limiter_status_method ON rate_limiter_messages (status, target_method);
CREATE INDEX idx_rate_limiter_created_at ON rate_limiter_messages (created_at);
```

### Oracle

```sql
CREATE TABLE rate_limiter_messages (
    id VARCHAR2(36) PRIMARY KEY,
    message_id VARCHAR2(255),
    target_method VARCHAR2(255) NOT NULL,
    payload_type VARCHAR2(255) NOT NULL,
    payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    retry_count NUMBER(10) DEFAULT 0 NOT NULL,
    status VARCHAR2(20) NOT NULL,
    error_message VARCHAR2(4000)
);
CREATE INDEX idx_rlm_status_method ON rate_limiter_messages (status, target_method);
CREATE INDEX idx_rlm_created_at ON rate_limiter_messages (created_at);
```

### Microsoft SQL Server

```sql
CREATE TABLE rate_limiter_messages (
    id NVARCHAR(36) PRIMARY KEY,
    message_id NVARCHAR(255),
    target_method NVARCHAR(255) NOT NULL,
    payload_type NVARCHAR(255) NOT NULL,
    payload NVARCHAR(MAX) NOT NULL,
    created_at DATETIME2 NOT NULL,
    processed_at DATETIME2,
    retry_count INT NOT NULL DEFAULT 0,
    status NVARCHAR(20) NOT NULL,
    error_message NVARCHAR(4000)
);
CREATE INDEX idx_status_method ON rate_limiter_messages (status, target_method);
CREATE INDEX idx_created_at ON rate_limiter_messages (created_at);
```

## Configuration

Add the following to your Spring Boot application:

```java
@SpringBootApplication
@EntityScan(basePackages = {"com.ratelimiter.model"})
@EnableJpaRepositories(basePackages = {"com.ratelimiter.repository"})
@EnableScheduling
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

Ensure your application's datasource is properly configured in `application.properties` or `application.yml`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/your_database
spring.datasource.username=your_username
spring.datasource.password=your_password
```

## Basic Usage

Add the `@RateLimited` annotation to any method you want to rate limit:

```java
@RestController
@RequestMapping("/api")
public class OrderController {

    @PostMapping("/orders")
    @RateLimited(
        ratePerSecond = 10,       // Maximum messages to process per second
        maxConcurrency = 5,       // Maximum concurrent processing
        memoryBufferSize = 100,   // Size of in-memory buffer before overflow
        maxRetries = 3            // Maximum retries before dead letter
    )
    public ResponseEntity<?> processOrder(@RequestBody Order order) {
        // Process the order...
        return ResponseEntity.ok("Order processed: " + order.getId());
    }
}
```

When this endpoint receives more requests than it can handle:
- Requests within the rate limit are processed immediately (HTTP 200)
- Excess requests are buffered and return HTTP 202 (Accepted)
- The background processor handles buffered messages at the configured rate

## Tuning Parameters for Performance and Memory Balance

The `@RateLimited` annotation has four parameters that can be tuned for optimal performance:

### 1. ratePerSecond

This parameter defines how many requests per second your method can handle.

**Guidelines:**
- Set this to 70-80% of your measured maximum sustainable throughput
- For CPU-intensive operations, set lower (10-50/sec)
- For I/O-bound operations, you can set higher (100-500/sec)
- For database-intensive operations, align with your database connection pool size

**Performance Impact:**
- Too high: System can become overloaded during traffic spikes
- Too low: Underutilizes resources and creates unnecessary buffering

**Recommended starting points:**
- REST API endpoints: 50-100/sec
- Database write operations: 20-50/sec
- Complex processing: 10-20/sec

### 2. maxConcurrency

This limits how many instances of the method can execute simultaneously.

**Guidelines:**
- Set this to 50-75% of your application's thread pool size
- For CPU-intensive methods, set to number of available CPU cores
- For I/O waiting methods, can be set higher (2-3x CPU cores)

**Performance Impact:**
- Too high: Can exhaust thread pool and memory
- Too low: Creates artificial bottlenecks

**Recommended starting points:**
- REST API endpoints: 10-20
- Database operations: 5-10
- External API calls: 20-30

### 3. memoryBufferSize

This controls how many messages are kept in memory before overflowing to the database.

**Guidelines:**
- Larger values improve performance but increase memory usage
- Set based on expected traffic spikes and memory constraints
- Consider message size when setting this value

**Performance Impact:**
- Too high: Can cause OOM errors during traffic spikes
- Too low: Causes excessive database writes, reducing performance

**Recommended sizing:**
- For small messages (<10KB): 1000-5000
- For medium messages (10-100KB): 100-1000
- For large messages (>100KB): 10-100

**Memory calculation:** A rough estimate of memory usage is:
`memoryBufferSize × average message size × number of rate-limited methods`

### 4. maxRetries

This defines how many times a failed message will be retried before being moved to the dead letter queue.

**Guidelines:**
- For idempotent operations: 3-5 retries
- For non-idempotent operations: 0-1 retries or implement custom retry logic
- Consider exponential backoff for external system calls

**Performance Impact:**
- Too high: Failed messages can clog the system
- Too low: Transient failures aren't properly handled

## Advanced Usage

### Service Methods

You can apply rate limiting to service methods as well:

```java
@Service
public class OrderService {
    
    @RateLimited(
        ratePerSecond = 20,
        maxConcurrency = 5,
        memoryBufferSize = 200,
        maxRetries = 3
    )
    public String processOrder(Order order) {
        // Process order
        return "Order processed: " + order.getId();
    }
}
```

### Return Types

For methods with non-ResponseEntity return types, buffered calls will return null. If you need a response for every call, consider using ResponseEntity:

```java
@RateLimited(ratePerSecond = 5)
public ResponseEntity<?> methodWithResponse(Payload payload) {
    // When processed immediately
    return ResponseEntity.ok("Processed");
    
    // When buffered, library will return ResponseEntity.accepted()
}
```

### Multiple Rate Limited Methods

When using multiple rate-limited methods, each gets its own buffer and configuration:

```java
// High throughput, small messages
@RateLimited(ratePerSecond = 200, memoryBufferSize = 5000)
public ResponseEntity<?> highThroughputMethod(SmallPayload payload) { ... }

// Low throughput, large messages
@RateLimited(ratePerSecond = 10, memoryBufferSize = 50)
public ResponseEntity<?> processingIntensiveMethod(LargePayload payload) { ... }
```

## Monitoring and Management

### Logging

Enable detailed logging in your `application.properties`:

```properties
logging.level.com.ratelimiter=DEBUG
```

### Database Monitoring

You can query the `rate_limiter_messages` table to monitor buffered messages:

```sql
-- Count messages by status
SELECT status, COUNT(*) FROM rate_limiter_messages GROUP BY status;

-- View pending messages
SELECT * FROM rate_limiter_messages WHERE status = 'PENDING' ORDER BY created_at;

-- View dead letters
SELECT * FROM rate_limiter_messages WHERE status = 'DEAD_LETTER';
```

### Custom Monitoring

Inject the `MessageRepository` to build custom monitoring:

```java
@Autowired
private MessageRepository messageRepository;

@GetMapping("/stats")
public Map<String, Object> getStats() {
    Map<String, Object> stats = new HashMap<>();
    stats.put("pendingCount", messageRepository.countByStatus(MessageStatus.PENDING));
    stats.put("deadLetterCount", messageRepository.countByStatus(MessageStatus.DEAD_LETTER));
    return stats;
}
```

## Limitations

- Only works with methods that take a single parameter (the payload)
- Return value handling is limited when buffering (returns 202 or null)
- Uses reflection for method invocation

## Performance Considerations

For optimal performance:

1. Size your memory buffer appropriately to minimize database operations
2. Set concurrency limits that match your infrastructure capabilities
3. Use connection pooling for the database
4. Consider adding additional indexes to the table for specific query patterns
5. For very high throughput systems, consider horizontal scaling
6. Monitor JVM memory usage and adjust buffer sizes if needed

## Dead Letter Processing

Failed messages (after maxRetries) go to the dead letter queue with status `DEAD_LETTER`. You can implement a scheduled process to review and potentially reprocess these messages.

## Example: Sizing for a High-Traffic API

For a REST API expected to handle 500 requests/second with occasional spikes to 2000 requests/second:

```java
@RateLimited(
    ratePerSecond = 500,        // Handle normal traffic
    maxConcurrency = 50,        // Assuming 100 total application threads
    memoryBufferSize = 3000,    // Handle 6-second spike at max traffic
    maxRetries = 3
)
public ResponseEntity<?> handleRequest(@RequestBody Request request) {
    // Process request
    return ResponseEntity.ok(result);
}
```

## License

This project is licensed under the MIT License.