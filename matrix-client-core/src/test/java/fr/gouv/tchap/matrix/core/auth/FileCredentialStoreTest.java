package fr.gouv.tchap.matrix.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.auth.Credentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCredentialStoreTest {

	@TempDir
	Path dir;

	private static final Credentials SAMPLE = new Credentials("@bot.fr:srv", "@bot:srv", "DEV1", "at1", "rt1",
			Instant.parse("2026-11-11T16:00:00Z"));

	@SuppressWarnings("resource")
	@Test
	void missingFile_loadsEmpty() {
		assertTrue(new FileCredentialStore(dir.resolve("none.json")).load().toCompletableFuture().join().isEmpty());
	}

	@Test
	void saveThenLoad_roundTripsEveryField() {
		@SuppressWarnings("resource")
		FileCredentialStore store = new FileCredentialStore(dir.resolve("sub/credentials.json"));

		store.save(SAMPLE).toCompletableFuture().join();

		assertEquals(SAMPLE, store.load().toCompletableFuture().join().orElseThrow());
	}

	@Test
	void optionalFields_mayBeAbsent() {
		@SuppressWarnings("resource")
		FileCredentialStore store = new FileCredentialStore(dir.resolve("c.json"));
		Credentials minimal = new Credentials("@bot.fr:srv", null, null, "only-access", null, null);

		store.save(minimal).toCompletableFuture().join();

		Credentials loaded = store.load().toCompletableFuture().join().orElseThrow();
		assertEquals("only-access", loaded.accessToken());
		assertNull(loaded.refreshToken());
		assertNull(loaded.expiresAt());
		assertNull(loaded.deviceId());
	}

	@Test
	void secondSave_replacesTheFirst_andLeavesNoTemporaryFile() throws Exception {
		Path file = dir.resolve("c.json");
		@SuppressWarnings("resource")
		FileCredentialStore store = new FileCredentialStore(file);
		store.save(SAMPLE).toCompletableFuture().join();

		store.save(new Credentials("@bot.fr:srv", "@bot:srv", "DEV1", "at2", "rt2", null)).toCompletableFuture().join();

		assertEquals("rt2", store.load().toCompletableFuture().join().orElseThrow().refreshToken());
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count(), "seul le fichier final doit rester");
		}
	}

	@SuppressWarnings("resource")
	@Test
	void file_isReadableByItsOwnerOnly_onPosixSystems() throws Exception {
		Path file = dir.resolve("c.json");
		new FileCredentialStore(file).save(SAMPLE).toCompletableFuture().join();

		if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
			assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
					Files.getPosixFilePermissions(file));
		}
	}

	@SuppressWarnings("resource")
	@Test
	void blankOrCorruptFile_isHandled() throws Exception {
		Path blank = dir.resolve("blank.json");
		Files.writeString(blank, "  \n");
		assertTrue(new FileCredentialStore(blank).load().toCompletableFuture().join().isEmpty());

		Path corrupt = dir.resolve("corrupt.json");
		Files.writeString(corrupt, "{not json");
		assertThrows(CompletionException.class,
				() -> new FileCredentialStore(corrupt).load().toCompletableFuture().join());
	}

	@Test
	void close_finishesThePendingWrites_soTheLastRefreshTokenIsNotLost() {
		Path file = dir.resolve("c.json");
		FileCredentialStore store = new FileCredentialStore(file);

		store.save(SAMPLE); // volontairement sans attendre
		store.save(new Credentials("@bot.fr:srv", "@bot:srv", "DEV1", "at2", "rt2", null));
		store.close();

		@SuppressWarnings("resource")
		Credentials loaded = new FileCredentialStore(file).load().toCompletableFuture().join().orElseThrow();
		assertEquals("rt2", loaded.refreshToken());
	}

	@Test
	void afterClose_saveFailsCleanly_andCloseIsIdempotent() {
		FileCredentialStore store = new FileCredentialStore(dir.resolve("c.json"));
		store.close();
		store.close();

		CompletionException failure = assertThrows(CompletionException.class,
				() -> store.save(SAMPLE).toCompletableFuture().join());

		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	void clear_deletesTheFile_andTheTemporaryFile() throws Exception {
		Path file = dir.resolve("c.json");
		FileCredentialStore store = new FileCredentialStore(file);
		store.save(SAMPLE).toCompletableFuture().join();
		Files.writeString(dir.resolve("c.json.tmp"), "reste d'une écriture interrompue");

		store.clear().toCompletableFuture().join();

		assertFalse(Files.exists(file));
		assertFalse(Files.exists(dir.resolve("c.json.tmp")));
		assertTrue(store.load().toCompletableFuture().join().isEmpty());
	}

	@Test
	void clear_ofAMissingFile_isNotAnError() {
		assertDoesNotThrow(
				() -> new FileCredentialStore(dir.resolve("none.json")).clear().toCompletableFuture().join());
	}

	@Test
	void afterClear_aNewSaveWorks() {
		FileCredentialStore store = new FileCredentialStore(dir.resolve("c.json"));
		store.save(SAMPLE).toCompletableFuture().join();
		store.clear().toCompletableFuture().join();

		store.save(SAMPLE).toCompletableFuture().join();

		assertEquals(SAMPLE, store.load().toCompletableFuture().join().orElseThrow());
	}

	@Test
	void clear_afterClose_failsCleanly() {
		FileCredentialStore store = new FileCredentialStore(dir.resolve("c.json"));
		store.close();

		CompletionException failure = assertThrows(CompletionException.class,
				() -> store.clear().toCompletableFuture().join());

		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}
}
