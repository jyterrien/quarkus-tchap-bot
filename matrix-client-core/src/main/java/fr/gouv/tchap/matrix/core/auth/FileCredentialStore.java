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

/**
 * Persiste les identifiants dans un fichier JSON, pour que le bot reprenne sa
 * session (et son token de renouvellement, à usage unique) après un
 * redémarrage. Le fichier contient des secrets : il est créé lisible par son
 * seul propriétaire quand le système de fichiers le permet. Les accès disque
 * s'exécutent sur un thread dédié, dans l'ordre ; l'écriture est atomique
 * (fichier temporaire puis renommage).
 */
public class FileCredentialStore implements CredentialStore {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
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
		return CompletableFuture.supplyAsync(() -> {
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
						e instanceof IOException io ? io : new IOException(e));
			}
		}, IO);
	}

	@Override
	public CompletionStage<Void> save(Credentials credentials) {
		return CompletableFuture.runAsync(() -> {
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
		}, IO);
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
