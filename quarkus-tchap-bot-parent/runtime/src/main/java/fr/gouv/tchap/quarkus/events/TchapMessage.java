package fr.gouv.tchap.quarkus.events;

import java.time.Instant;

public record TchapMessage(
    String eventId,
    String roomId,
    String sender,
    String body,
    Instant timestamp,
    boolean encrypted,
    String messageType
) {}
