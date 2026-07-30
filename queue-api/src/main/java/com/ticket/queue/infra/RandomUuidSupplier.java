package com.ticket.queue.infra;

import com.ticket.queue.domain.UuidSupplier;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RandomUuidSupplier implements UuidSupplier {

    @Override
    public UUID get() {
        return UUID.randomUUID();
    }
}
