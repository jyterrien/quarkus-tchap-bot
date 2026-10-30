package fr.gouv.tchap.matrix.sync;

import java.util.concurrent.CompletionStage;

public interface SyncService {

    CompletionStage<Void> start();

    CompletionStage<Void> stop();

    boolean isRunning();
}
