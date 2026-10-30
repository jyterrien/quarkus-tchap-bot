package fr.gouv.tchap.matrix.room;

public record Room(
    String roomId,
    String name,
    boolean encrypted
) {}
