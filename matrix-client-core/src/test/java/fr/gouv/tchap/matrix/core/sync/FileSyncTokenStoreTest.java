package fr.gouv.tchap.matrix.core.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSyncTokenStoreTest {

	@TempDir
	Path dir;

	@Test
	void load_missingFile_returnsEmpty() {
		@SuppressWarnings("resource")
		FileSyncTokenStore store = new FileSyncTokenStore(dir.resolve("token"));

		assertEquals(Optional.empty(), store.load().toCompletableFuture().join());
	}

	@SuppressWarnings("resource")
	@Test
	void saveThenLoad_fromANewInstance_returnsTheToken() {
		Path file = dir.resolve("token");
		new FileSyncTokenStore(file).save("s123_456").toCompletableFuture().join();

		Optional<String> loaded = new FileSyncTokenStore(file).load().toCompletableFuture().join();

		assertEquals(Optional.of("s123_456"), loaded);
	}

	@Test
	void save_overwritesPreviousToken() {
		@SuppressWarnings("resource")
		FileSyncTokenStore store = new FileSyncTokenStore(dir.resolve("token"));
		store.save("first").toCompletableFuture().join();
		store.save("second").toCompletableFuture().join();

		assertEquals(Optional.of("second"), store.load().toCompletableFuture().join());
	}

	@SuppressWarnings("resource")
	@Test
	void save_createsMissingParentDirectories_andLeavesNoTempFile() {
		Path file = dir.resolve("a/b/token");

		new FileSyncTokenStore(file).save("s1").toCompletableFuture().join();

		assertTrue(Files.exists(file));
		assertFalse(Files.exists(dir.resolve("a/b/token.tmp")));
	}

	@SuppressWarnings("resource")
	@Test
	void load_blankFile_returnsEmpty() throws Exception {
		Path file = dir.resolve("token");
		Files.writeString(file, "  \n");

		assertEquals(Optional.empty(), new FileSyncTokenStore(file).load().toCompletableFuture().join());
	}

	@Test
	void close_finishesThePendingWrites() throws Exception {
		Path file = dir.resolve("token");
		FileSyncTokenStore store = new FileSyncTokenStore(file);

		store.save("s1"); // volontairement sans attendre
		store.save("s2");
		store.close();

		assertEquals("s2", java.nio.file.Files.readString(file).trim());
	}

	@Test
	void afterClose_saveFailsCleanly_andCloseIsIdempotent() {
		FileSyncTokenStore store = new FileSyncTokenStore(dir.resolve("token"));
		store.close();
		store.close();

		var failure = assertThrows(java.util.concurrent.CompletionException.class,
				() -> store.save("late").toCompletableFuture().join());

		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}
}
