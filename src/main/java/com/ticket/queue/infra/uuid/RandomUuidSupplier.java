package com.ticket.queue.infra.uuid;

import com.ticket.queue.domain.port.UuidSupplier;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RandomUuidSupplier implements UuidSupplier {

    @Override
    public UUID get() {
        return UUID.randomUUID();
    }
}