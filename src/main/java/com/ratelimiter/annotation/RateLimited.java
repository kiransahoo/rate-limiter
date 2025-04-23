package com.ratelimiter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to apply rate limiting to any method.
 * This will buffer incoming messages and control their processing rate
 * to prevent system overload.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {
    /**
     * Maximum messages to process per second
     */
    int ratePerSecond() default 50;
    
    /**
     * Maximum concurrent processing allowed
     */
    int maxConcurrency() default 10;
    
    /**
     * Size of in-memory buffer before overflow to database
     */
    int memoryBufferSize() default 100;
    
    /**
     * Maximum retries before message goes to dead letter
     */
    int maxRetries() default 3;
}
