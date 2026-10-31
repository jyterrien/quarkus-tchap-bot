package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.sync.SyncTokenStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persiste le token de synchronisation dans un fichier, pour reprendre après un redémarrage
 * sans perdre les messages reçus pendant l'arrêt (et sans rejouer l'historique).
 *
 * Les accès disque s'exécutent sur un thread dédié, dans l'ordre : ils ne bloquent jamais
 * l'appelant (save() est appelé depuis l'event loop Vert.x). L'écriture est atomique
 * (fichier temporaire puis renommage).
 */
public class FileSyncTokenStore implements SyncTokenStore {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tchap-token-io");
        t.setDaemon(true);
        return t;
    });

    private final Path file;

    public FileSyncTokenStore(Path file) {
        this.file = Objects.requireNonNull(file, "file is required").toAbsolutePath();
    }

    @Override
    public CompletionStage<Optional<String>> load() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(file)) return Optional.<String>empty();
                String token = Files.readString(file, StandardCharsets.UTF_8).trim();
                return token.isEmpty() ? Optional.<String>empty() : Optional.of(token);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read sync token file " + file, e);
            }
        }, IO);
    }

    @Override
    public CompletionStage<Void> save(String token) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, token, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write sync token file " + file, e);
            }
        }, IO);
    }
}
