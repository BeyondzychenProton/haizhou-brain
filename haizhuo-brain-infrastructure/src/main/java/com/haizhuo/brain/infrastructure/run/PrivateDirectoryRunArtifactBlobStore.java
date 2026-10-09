package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/** 使用私有目录存储成果物，只接受服务端生成的成果物 ID 作为地址。 */
public final class PrivateDirectoryRunArtifactBlobStore implements RunArtifactBlobStore {
    private static final String LOCK_FILE = ".artifact-store.lock";
    private static final String CURSOR_FILE = ".artifact-cleanup.cursor";
    private static final int HARD_SCAN_LIMIT = 1_000;
    private static final int HARD_CANDIDATE_LIMIT = 25;
    private static final Duration HARD_ELAPSED_LIMIT = Duration.ofSeconds(5);
    private static final long WRITE_LOCK_WAIT_MILLIS = 10_000;
    private static final ConcurrentMap<Path, ReentrantLock> JVM_ROOT_LOCKS = new ConcurrentHashMap<>();

    private final Path configuredRoot;

    // 定时分批处理期间保持 DirectoryStream 打开。旁路游标可让其他进程或重启后的进程
    // 恢复当前位置，避免重复扫描已经处理过的目录前缀。
    private Path scannerRoot;
    private DirectoryStream<Path> scanStream;
    private Iterator<Path> scanIterator;
    private String checkpoint;
    private String seekAnchor;
    private boolean seekingAnchor;
    private boolean cursorInitialized;

    public PrivateDirectoryRunArtifactBlobStore(Path configuredRoot) {
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    }

    @Override
    public void write(String artifactId, byte[] content) throws IOException {
        Path root = root();
        Path target = target(root, artifactId);
        try (StoreLock ignored = acquireStoreLock(root, WRITE_LOCK_WAIT_MILLIS)) {
            Path temporary = Files.createTempFile(root, ".artifact-", ".tmp");
            try {
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException existing) {
                    verifyExisting(target, content);
                }
                if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(temporary);
                verifyExisting(target, content);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @Override
    public Optional<byte[]> read(String artifactId) throws IOException {
        Path root = root();
        Path file = target(root, artifactId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(Files.readAllBytes(file));
    }

    @Override
    public synchronized CleanupBatch cleanupExpired(Instant cutoff, int maxScanned, int maxCandidates,
                                                     Duration maxElapsed,
                                                     Predicate<String> isBlobRefReferenced)
            throws IOException {
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(maxElapsed, "maxElapsed");
        Objects.requireNonNull(isBlobRefReferenced, "isBlobRefReferenced");
        int scanLimit = Math.max(0, Math.min(maxScanned, HARD_SCAN_LIMIT));
        int candidateLimit = Math.max(0, Math.min(maxCandidates, HARD_CANDIDATE_LIMIT));
        long budgetNanos = maxElapsed.isNegative() ? 0
                : maxElapsed.compareTo(HARD_ELAPSED_LIMIT) >= 0
                ? HARD_ELAPSED_LIMIT.toNanos() : maxElapsed.toNanos();
        if (scanLimit == 0 || candidateLimit == 0 || budgetNanos == 0)
            return new CleanupBatch(true, 0, 0, 0, 0, true);

        Path root = root();
        long startedAt = System.nanoTime();
        long deadline = startedAt + budgetNanos;
        try (StoreLock ignored = acquireStoreLock(root, remainingMillis(deadline))) {
            ensureScanner(root);
            int scanned = 0;
            String lastScannedName = null;
            boolean completedSweep = false;
            boolean timeLimitReached = false;
            List<Candidate> candidates = new ArrayList<>();

            while (scanned < scanLimit) {
                if (System.nanoTime() >= deadline) {
                    timeLimitReached = true;
                    break;
                }
                if (!scanIterator.hasNext()) {
                    if (seekingAnchor) {
                        // 已保存的目录项消失或目录顺序发生变化时，重新开始一次有界扫描；
                        // 只有完整完成定位后才会进入此恢复分支。
                        resetScanner();
                        persistCursor(root, "");
                        checkpoint = "";
                        openScanner(root, "");
                        continue;
                    }
                    completedSweep = true;
                    resetScanner();
                    persistCursor(root, "");
                    checkpoint = "";
                    break;
                }

                Path entry = scanIterator.next();
                scanned++;
                String name = entry.getFileName().toString();
                if (seekingAnchor) {
                    if (name.equals(seekAnchor)) {
                        seekingAnchor = false;
                        lastScannedName = name;
                    }
                    continue;
                }

                lastScannedName = name;
                Candidate candidate = candidate(root, entry, cutoff);
                if (candidate != null) candidates.add(candidate);
            }

            if (!completedSweep && !seekingAnchor && lastScannedName != null) {
                checkpoint = lastScannedName;
                persistCursor(root, checkpoint);
            }
            if (System.nanoTime() >= deadline) timeLimitReached = true;

            candidates.sort(Comparator.comparing(Candidate::lastModifiedAt));
            if (candidates.size() > candidateLimit)
                candidates = new ArrayList<>(candidates.subList(0, candidateLimit));

            // 删除文件前先解析整批候选项。数据库查询失败时保留整批文件；
            // 所有查询和删除都在同一根目录锁内执行。
            List<CheckedCandidate> checked = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (System.nanoTime() >= deadline) {
                    timeLimitReached = true;
                    break;
                }
                boolean referenced = isBlobRefReferenced.test(candidate.blobRef());
                checked.add(new CheckedCandidate(candidate, referenced));
            }

            int deleted = 0;
            int retained = 0;
            for (CheckedCandidate item : checked) {
                if (System.nanoTime() >= deadline) {
                    timeLimitReached = true;
                    break;
                }
                if (item.referenced()) {
                    retained++;
                } else if (deleteIfUnchanged(root, item.candidate(), cutoff)) {
                    deleted++;
                } else {
                    retained++;
                }
            }
            return new CleanupBatch(true, scanned, candidates.size(), deleted, retained, timeLimitReached);
        }
    }

    private Path root() throws IOException {
        Files.createDirectories(configuredRoot);
        Path root = configuredRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Private artifact storage is unavailable");
        return root;
    }

    private static Path target(Path root, String artifactId) throws IOException {
        try {
            UUID id = UUID.fromString(artifactId);
            if (!id.toString().equals(artifactId)) throw new IllegalArgumentException("non-canonical id");
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid artifact reference", invalid);
        }
        Path target = root.resolve(artifactId + ".blob").normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Invalid artifact reference");
        return target;
    }

    private static void verifyExisting(Path target, byte[] expected) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Artifact blob could not be finalized");
        byte[] actual = Files.readAllBytes(target);
        if (!CanonicalJson.sha256Hex(actual).equals(CanonicalJson.sha256Hex(expected)))
            throw new IOException("Immutable artifact blob conflicts");
    }

    private Candidate candidate(Path root, Path entry, Instant cutoff) throws IOException {
        String name = entry.getFileName().toString();
        if (!name.endsWith(".blob")) return null;
        String blobRef = name.substring(0, name.length() - ".blob".length());
        Path expected;
        try {
            expected = target(root, blobRef);
        } catch (IOException invalidReference) {
            return null;
        }
        if (!expected.equals(entry.normalize())) return null;
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException disappeared) {
            return null;
        }
        if (!attributes.isRegularFile()) return null;
        Instant modified = attributes.lastModifiedTime().toInstant();
        if (!modified.isBefore(cutoff)) return null;
        return new Candidate(blobRef, modified, attributes.size(), fileKey(attributes));
    }

    private static boolean deleteIfUnchanged(Path root, Candidate candidate, Instant cutoff) throws IOException {
        Path file = target(root, candidate.blobRef());
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException disappeared) {
            return false;
        }
        if (!attributes.isRegularFile()
                || attributes.size() != candidate.byteSize()
                || !attributes.lastModifiedTime().toInstant().equals(candidate.lastModifiedAt())
                || !Objects.equals(fileKey(attributes), candidate.fileKey())
                || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)) return false;
        try {
            Files.delete(file);
            return true;
        } catch (NoSuchFileException disappeared) {
            return false;
        }
    }

    private void ensureScanner(Path root) throws IOException {
        String persisted = readCursor(root);
        if (!cursorInitialized || !root.equals(scannerRoot) || !Objects.equals(persisted, checkpoint)) {
            resetScanner();
            checkpoint = persisted;
            cursorInitialized = true;
            openScanner(root, persisted);
        }
    }

    private void openScanner(Path root, String cursor) throws IOException {
        scannerRoot = root;
        scanStream = Files.newDirectoryStream(root);
        scanIterator = scanStream.iterator();
        seekAnchor = cursor;
        seekingAnchor = cursor != null && !cursor.isEmpty();
        cursorInitialized = true;
    }

    private void resetScanner() throws IOException {
        if (scanStream != null) scanStream.close();
        scanStream = null;
        scanIterator = null;
        scannerRoot = null;
        seekAnchor = null;
        seekingAnchor = false;
        cursorInitialized = false;
    }

    private static String readCursor(Path root) throws IOException {
        Path cursorFile = root.resolve(CURSOR_FILE);
        if (!Files.exists(cursorFile, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(cursorFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Private artifact cleanup cursor is invalid");
        long size = Files.size(cursorFile);
        if (size > 512) throw new IOException("Private artifact cleanup cursor is invalid");
        String encoded = Files.readString(cursorFile, StandardCharsets.US_ASCII).trim();
        if (encoded.isEmpty()) return "";
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            // 此值只与直接子项文件名比较，不会被解析成路径。
            if (decoded.isEmpty() || decoded.length() > 255 || decoded.equals(".") || decoded.equals(".."))
                throw new IllegalArgumentException("invalid name");
            return decoded;
        } catch (RuntimeException invalid) {
            throw new IOException("Private artifact cleanup cursor is invalid", invalid);
        }
    }

    private static void persistCursor(Path root, String cursor) throws IOException {
        Path cursorFile = root.resolve(CURSOR_FILE);
        if (Files.exists(cursorFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(cursorFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Private artifact cleanup cursor is invalid");
        String encoded = cursor == null || cursor.isEmpty() ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
        Path temporary = Files.createTempFile(root, ".artifact-cursor-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(encoded.getBytes(StandardCharsets.US_ASCII));
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try {
                Files.move(temporary, cursorFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cursorFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static StoreLock acquireStoreLock(Path root, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        ReentrantLock local = JVM_ROOT_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        boolean localAcquired;
        try {
            localAcquired = local.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Private artifact storage lock was interrupted", interrupted);
        }
        if (!localAcquired) throw new IOException("Private artifact storage is busy");

        FileChannel channel = null;
        try {
            Path lockPath = root.resolve(LOCK_FILE);
            try {
                Files.createFile(lockPath);
            } catch (FileAlreadyExistsException alreadyExists) {
                // 多个进程可能共享此文件，打开前会在下方完成校验。
            }
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Private artifact storage lock is invalid");
            channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            do {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) return new StoreLock(local, channel, lock);
                } catch (OverlappingFileLockException busyInProcess) {
                    // 同一进程中的另一个实例可能没有共用当前 ClassLoader 的锁映射。
                }
                if (System.nanoTime() >= deadline) break;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Private artifact storage lock was interrupted", interrupted);
                }
            } while (true);
            throw new IOException("Private artifact storage is busy");
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            local.unlock();
            throw failure;
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static String fileKey(BasicFileAttributes attributes) {
        return attributes.fileKey() == null ? null : attributes.fileKey().toString();
    }

    private record Candidate(String blobRef, Instant lastModifiedAt, long byteSize, String fileKey) { }
    private record CheckedCandidate(Candidate candidate, boolean referenced) { }

    private record StoreLock(ReentrantLock local, FileChannel channel, FileLock fileLock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                fileLock.release();
            } finally {
                try {
                    channel.close();
                } finally {
                    local.unlock();
                }
            }
        }
    }
}
