package fr.gouv.tchap.matrix.event;

import java.time.Instant;

/**
 * Événement Matrix brut, tel que reçu par /sync.
 * Les champs correspondent aux propriétés communes de tous les événements.
 */
public record MatrixEvent(
    String eventId,
    String roomId,
    String sender,
    String type,       // ex: "m.room.message"
    String content,    // JSON brut du contenu
    Instant timestamp  // origin_server_ts converti
) {}