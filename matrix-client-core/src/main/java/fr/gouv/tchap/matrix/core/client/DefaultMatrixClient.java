package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.core.auth.DefaultSession;
import fr.gouv.tchap.matrix.core.message.DefaultMessageService;
import fr.gouv.tchap.matrix.core.message.UuidTransactionIdGenerator;
import fr.gouv.tchap.matrix.core.room.DefaultRoomService;
import fr.gouv.tchap.matrix.core.sync.SyncEngine;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.core.transport.MatrixTransport;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.room.RoomService;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DefaultMatrixClient implements MatrixClient {

    private final Session session;
    private final MessageService messageService;
    private final RoomService roomService;
    private final SyncEngine syncEngine;
    private final AtomicReference<ClientStatus> status = new AtomicReference<>(ClientStatus.NEW);

    public DefaultMatrixClient(MatrixTransport transport,
                               String accessToken,
                               String userId,
                               SyncTokenStore tokenStore,
                               Consumer<MatrixEvent> eventConsumer) {
        MatrixHttpApi api = new MatrixHttpApi(transport, accessToken);
        this.session = new DefaultSession(userId, null, accessToken);
        this.messageService = new DefaultMessageService(api, new UuidTransactionIdGenerator());
        this.roomService = new DefaultRoomService(api);
        this.syncEngine = new SyncEngine(api, tokenStore, eventConsumer);
    }

    @Override
    public CompletionStage<Void> start() {
        status.set(ClientStatus.STARTING);
        return syncEngine.start().thenRun(() -> status.set(ClientStatus.RUNNING));
    }

    @Override
    public CompletionStage<Void> stop() {
        status.set(ClientStatus.STOPPING);
        return syncEngine.stop().thenRun(() -> status.set(ClientStatus.STOPPED));
    }

    @Override public MessageService messages() { return messageService; }
    @Override public RoomService rooms()       { return roomService; }
    @Override public Session session()         { return session; }
    @Override public ClientStatus status()     { return status.get(); }
}
