package io.github.houseelves.github.elf;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/**
 * One run of the elf at a time changes its files: the runner may run a refresh, an action from the tab and an
 * agent's agent-done at once, and each reads the state, changes it and writes it back. Held for the whole run.
 */
public final class ElfLock implements AutoCloseable {

    /** Less than the runner gives an action (150 seconds), so a wait that's too long fails with a reason. */
    static final Duration WAIT = Duration.ofSeconds(120);

    private final FileChannel channel;
    private final FileLock lock;

    private ElfLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ElfLock take(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        FileChannel channel = FileChannel.open(stateDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        Instant until = Instant.now().plus(WAIT);
        try {
            while (true) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    lock = null;
                }
                if (lock != null) return new ElfLock(channel, lock);
                if (Instant.now().isAfter(until)) {
                    throw new IOException("The GitHub elf is busy (a refresh is running?); try again in a moment");
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            channel.close();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the GitHub elf");
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
