package fr.gouv.tchap.matrix.core.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FileSyncTokenStoreTest {

    @TempDir
    Path dir;

    @Test
    void load_missingFile_returnsEmpty() {
        FileSyncTokenStore store = new FileSyncTokenStore(dir.resolve("token"));

        assertEquals(Optional.empty(), store.load().toCompletableFuture().join());
    }

    @Test
    void saveThenLoad_fromANewInstance_returnsTheToken() {
        Path file = dir.resolve("token");
        new FileSyncTokenStore(file).save("s123_456").toCompletableFuture().join();

        Optional<String> loaded = new FileSyncTokenStore(file).load().toCompletableFuture().join();

        assertEquals(Optional.of("s123_456"), loaded);
    }

    @Test
    void save_overwritesPreviousToken() {
        FileSyncTokenStore store = new FileSyncTokenStore(dir.resolve("token"));
        store.save("first").toCompletableFuture().join();
        store.save("second").toCompletableFuture().join();

        assertEquals(Optional.of("second"), store.load().toCompletableFuture().join());
    }

    @Test
    void save_createsMissingParentDirectories_andLeavesNoTempFile() {
        Path file = dir.resolve("a/b/token");

        new FileSyncTokenStore(file).save("s1").toCompletableFuture().join();

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("a/b/token.tmp")));
    }

    @Test
    void load_blankFile_returnsEmpty() throws Exception {
        Path file = dir.resolve("token");
        Files.writeString(file, "  \n");

        assertEquals(Optional.empty(), new FileSyncTokenStore(file).load().toCompletableFuture().join());
    }
}
