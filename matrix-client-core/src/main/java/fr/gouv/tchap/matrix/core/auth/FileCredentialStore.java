package fr.gouv.tchap.matrix.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.auth.Credentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Persiste les identifiants dans un fichier JSON, pour que le bot reprenne sa
 * session (et son token de renouvellement, à usage unique) après un
 * redémarrage. Le fichier contient des secrets : il est créé lisible par son
 * seul propriétaire quand le système de fichiers le permet. Les accès disque
 * s'exécutent sur un thread dédié, dans l'ordre ; l'écriture est atomique
 * (fichier temporaire puis renommage).
 *
 * Le thread d'écriture appartient à l'instance : {@link #close()} termine les
 * écritures déjà en attente puis le libère. C'est important ici : le refresh
 * token est à usage unique, et perdre la dernière écriture à l'arrêt oblige à
 * se reconnecter avec le mot de passe au démarrage suivant. Après
 * {@code close()}, {@code save} et {@code load} échouent.
 */
public class FileCredentialStore implements CredentialStore, AutoCloseable {

	private static final System.Logger LOG = System.getLogger(FileCredentialStore.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final long CLOSE_TIMEOUT_SEC = 5;

	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "tchap-credentials-io");
		t.setDaemon(true);
		return t;
	});

	private final Path file;

	public FileCredentialStore(Path file) {
		this.file = Objects.requireNonNull(file, "file is required").toAbsolutePath();
	}

	@Override
	public CompletionStage<Optional<Credentials>> load() {
		return submit(() -> CompletableFuture.supplyAsync(() -> {
			try {
				if (!Files.exists(file)) {
					return Optional.<Credentials>empty();
				}
				String content = Files.readString(file, StandardCharsets.UTF_8);
				if (content.isBlank()) {
					return Optional.<Credentials>empty();
				}
				JsonNode node = MAPPER.readTree(content);
				String accessToken = node.path("access_token").asText(null);
				if (accessToken == null || accessToken.isBlank()) {
					return Optional.<Credentials>empty();
				}
				String expiresAt = node.path("expires_at").asText(null);
				return Optional.of(new Credentials(node.path("login").asText(null), node.path("user_id").asText(null),
						node.path("device_id").asText(null), accessToken, node.path("refresh_token").asText(null),
						expiresAt == null ? null : Instant.parse(expiresAt)));
			} catch (IOException | RuntimeException e) {
				throw new UncheckedIOException("Cannot read credentials file " + file,
						e instanceof IOException ioe ? ioe : new IOException(e));
			}
		}, io));
	}

	@Override
	public CompletionStage<Void> save(Credentials credentials) {
		return submit(() -> CompletableFuture.runAsync(() -> {
			try {
				Path parent = file.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				ObjectNode node = MAPPER.createObjectNode();
				node.put("login", credentials.login());
				node.put("user_id", credentials.userId());
				node.put("device_id", credentials.deviceId());
				node.put("access_token", credentials.accessToken());
				node.put("refresh_token", credentials.refreshToken());
				node.put("expires_at", credentials.expiresAt() == null ? null : credentials.expiresAt().toString());

				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.deleteIfExists(tmp);
				createPrivateFile(tmp);
				Files.writeString(tmp, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot write credentials file " + file, e);
			}
		}, io));
	}

	/**
	 * Supprime le fichier (et son éventuel fichier temporaire) ; absent n'est pas
	 * une erreur.
	 */
	@Override
	public CompletionStage<Void> clear() {
		return submit(() -> CompletableFuture.runAsync(() -> {
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot delete credentials file " + file, e);
			}
		}, io));
	}

	/**
	 * Un magasin fermé échoue proprement au lieu de lever depuis le thread
	 * appelant.
	 */
	private <T> CompletionStage<T> submit(java.util.function.Supplier<CompletionStage<T>> task) {
		try {
			return task.get();
		} catch (RejectedExecutionException e) {
			return CompletableFuture.failedFuture(new IllegalStateException("Credential store is closed", e));
		}
	}

	/**
	 * Termine les écritures en attente (au plus {@value #CLOSE_TIMEOUT_SEC} s) puis
	 * arrête le thread d'écriture. Idempotent.
	 */
	@Override
	public void close() {
		io.shutdown();
		try {
			if (!io.awaitTermination(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
				LOG.log(System.Logger.Level.WARNING, "Credential store: pending writes not finished after {0} s",
						CLOSE_TIMEOUT_SEC);
				io.shutdownNow();
			}
		} catch (InterruptedException e) {
			io.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** rw------- quand le système est POSIX ; sinon permissions par défaut. */
	private static void createPrivateFile(Path path) throws IOException {
		try {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} catch (UnsupportedOperationException e) {
			Files.createFile(path);
		}
	}
}
