package fr.gouv.tchap.quarkus.rooms;

import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Registre des salons nommés : crée les TchapRoom, les fait rejoindre au
 * démarrage,
 * restreint la synchronisation à ces salons et indique à quel nom correspond un
 * événement.
 */
@ApplicationScoped
public class TchapRooms {

    private static final Logger LOG = Logger.getLogger(TchapRooms.class);
    private static final long JOIN_TIMEOUT_SEC = 30;

    @Inject
    TchapConfig config;

    @Inject
    MatrixClient client;

    /**
     * Fourni par le processeur de l'extension (bean synthétique). Absent quand le
     * runtime est exécuté
     * comme une simple application, sans phase de build de l'extension
     * (ex. @QuarkusTest du module runtime) :
     * dans ce cas aucun nom injecté n'est connu et on ne valide rien.
     */
    @Inject
    Instance<InjectedRoomNames> injected;

    /**
     * Un TchapRoom par salon configuré, créé dès l'instanciation : jamais modifié
     * ensuite.
     */
    private final Map<String, TchapRoom> handles = new LinkedHashMap<>();
    private final Map<String, String> nameByRoomId = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        LOG.infof("TchapRooms @PostConstruct: rooms=%s", config.rooms().keySet());
        config.rooms().keySet().forEach(name -> handles.put(name, new TchapRoom(name)));
    }

    /**
     * Appelé à la création des beans synthétiques @TchapRoomName("...") TchapRoom.
     */
    public TchapRoom get(String name) {
        TchapRoom room = handles.get(name);
        if (room == null) {
            throw new IllegalStateException(unknownRoomMessage(name));
        }
        return room;
    }

    public boolean isConfigured() {
        return !handles.isEmpty();
    }

    public Collection<TchapRoom> all() {
        return Collections.unmodifiableCollection(handles.values());
    }

    /** Nom configuré du salon portant cet identifiant résolu, s'il est suivi. */
    public Optional<String> nameOf(String roomId) {
        return Optional.ofNullable(nameByRoomId.get(roomId));
    }

    /**
     * Vérifie la configuration, fait rejoindre les salons et restreint la
     * synchronisation
     * aux salons disponibles. À appeler avant client.start(). Bloquant (30 s
     * maximum).
     *
     * @return false si des salons sont configurés mais qu'aucun n'est disponible :
     *         il ne faut alors pas démarrer la synchronisation (un filtre vide
     *         signifie « tous les salons »)
     * @throws IllegalStateException si un nom injecté n'est pas configuré,
     *                               ou si un salon requis ne peut pas être rejoint
     */
    public boolean initialize() {
        LOG.infof("initialize(): injectedNames=%s configuredRooms=%s",
                injected.isResolvable() ? injected.get().names() : "<absent>",
                config.rooms().keySet());
        validateInjectedNames();
        if (handles.isEmpty())
            return true;

        Map<String, CompletableFuture<String>> joins = new LinkedHashMap<>();
        handles.keySet().forEach(name -> joins.put(name, join(config.rooms().get(name).room())));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_TIMEOUT_SEC);
        List<String> errors = new ArrayList<>();
        List<String> available = new ArrayList<>();

        for (Map.Entry<String, CompletableFuture<String>> entry : joins.entrySet()) {
            String name = entry.getKey();
            TchapRoom handle = handles.get(name);
            TchapConfig.RoomConfig roomConfig = config.rooms().get(name);
            try {
                long remaining = Math.max(1L, deadline - System.nanoTime());
                String roomId = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                handle.bind(client.room(roomId));
                nameByRoomId.put(roomId, name);
                available.add(roomId);
                LOG.infof("Tchap room '%s' joined (%s)", name, roomId);
            } catch (Exception e) {
                if (e instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                String reason = roomConfig.room() + " -> " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage();
                handle.markUnavailable(reason);
                if (roomConfig.required()) {
                    errors.add("'" + name + "' (" + reason + ")");
                } else {
                    LOG.warnf("Optional Tchap room '%s' is unavailable: %s", name, reason);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Cannot join required Tchap room(s): " + String.join("; ", errors)
                    + ". Set quarkus.tchap.rooms.\"<name>\".required=false to start without it.");
        }
        if (!available.isEmpty()) {
            client.restrictToRooms(available);
        }
        return !available.isEmpty();
    }

    private CompletableFuture<String> join(String roomIdOrAlias) {
        try {
            return client.rooms().resolveAndJoin(roomIdOrAlias).toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void validateInjectedNames() {
        Set<String> injectedNames = injected.isResolvable() ? injected.get().names() : Set.of();
        Set<String> missing = new TreeSet<>(injectedNames);
        missing.removeAll(handles.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(unknownRoomMessage(missing.iterator().next())
                    + (missing.size() > 1 ? " (also missing: " + missing + ")" : ""));
        }
    }

    private static String unknownRoomMessage(String name) {
        return "Tchap room '" + name + "' is not configured: declare quarkus.tchap.rooms.\"" + name
                + "\".room=<room id or alias>";
    }
}
