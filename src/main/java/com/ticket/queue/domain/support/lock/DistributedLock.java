package com.ticket.queue.domain.support.lock;

import com.ticket.queue.domain.support.exception.ErrorType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    String prefix();

    String dynamicKey() default "";

    long waitTime() default 0L;

    long leaseTime() default 3_000L;

    ErrorType errorType() default ErrorType.LOCK_ACQUISITION_FAILED;

    String message() default "Failed to acquire distributed lock";
}