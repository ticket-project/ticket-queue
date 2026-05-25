package com.ticket.queue.application;

import org.springframework.stereotype.Component;

@Component
public class PollingIntervalPolicy {

    public long pollAfterSeconds(final long position) {
        if (position > 10_000) {
            return 30L;
        }
        if (position > 1_000) {
            return 10L;
        }
        if (position > 100) {
            return 5L;
        }
        return 2L;
    }
}
