package fr.gouv.tchap.quarkus.rooms;

import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.room.MatrixRoom;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Un salon Tchap nommé. Tout ce qui est envoyé par cet objet l'est dans ce salon, et nulle part ailleurs.
 *
 * L'objet est injectable dès le démarrage ; il devient utilisable une fois le salon rejoint.
 * Tant qu'il n'est pas disponible (salon facultatif inaccessible, démarrage en cours),
 * les envois échouent avec une IllegalStateException, sans effet de bord.
 */
public final class TchapRoom {

    private final String name;
    private volatile MatrixRoom room;
    private volatile String unavailableReason = "not joined yet";

    TchapRoom(String name) {
        this.name = name;
    }

    /** Nom du salon dans la configuration. */
    public String name() {
        return name;
    }

    /** Identifiant résolu du salon (jamais un alias) ; vide tant que le salon n'est pas rejoint. */
    public Optional<String> roomId() {
        MatrixRoom r = room;
        return r == null ? Optional.empty() : Optional.of(r.roomId());
    }

    public boolean isAvailable() {
        return room != null;
    }

    /** Raison de l'indisponibilité ; vide si le salon est disponible. */
    public Optional<String> unavailableReason() {
        return isAvailable() ? Optional.empty() : Optional.of(unavailableReason);
    }

    public CompletionStage<MessageReceipt> sendText(String body) {
        MatrixRoom r = room;
        return r == null ? unavailable() : r.sendText(body);
    }

    public CompletionStage<MessageReceipt> sendNotice(String body) {
        MatrixRoom r = room;
        return r == null ? unavailable() : r.sendNotice(body);
    }

    /**
     * Envoie un message texte dans un thread de ce salon. {@code threadRootId} est l'événement
     * racine du thread (voir {@code TchapMessage#replyThreadId()} pour répondre à un message reçu).
     *
     * @throws IllegalArgumentException si {@code threadRootId} est nul ou vide et que le salon est disponible
     */
    public CompletionStage<MessageReceipt> sendTextInThread(String threadRootId, String body) {
        MatrixRoom r = room;
        return r == null ? unavailable() : r.sendTextInThread(threadRootId, body);
    }

    /** Comme {@link #sendTextInThread}, avec le msgtype {@code m.notice}. */
    public CompletionStage<MessageReceipt> sendNoticeInThread(String threadRootId, String body) {
        MatrixRoom r = room;
        return r == null ? unavailable() : r.sendNoticeInThread(threadRootId, body);
    }

    void bind(MatrixRoom matrixRoom) {
        this.room = matrixRoom;
    }

    void markUnavailable(String reason) {
        this.unavailableReason = reason;
        this.room = null;
    }

    private CompletionStage<MessageReceipt> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Tchap room '" + name + "' is not available: " + unavailableReason));
    }

    @Override
    public String toString() {
        return "TchapRoom[" + name + ", " + roomId().orElse("unavailable") + "]";
    }
}
