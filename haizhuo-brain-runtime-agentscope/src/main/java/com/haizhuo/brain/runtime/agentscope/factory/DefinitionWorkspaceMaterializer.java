package com.haizhuo.brain.runtime.agentscope.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Materializes a verified, content-addressed, read-only published workspace package. */
public class DefinitionWorkspaceMaterializer {
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String COMPLETE_FILE = ".complete";
    private static final int MAX_FILES_PER_ASSET = 100;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_BYTES_PER_ASSET = 2 * 1024 * 1024;
    private static final int MAX_FILES_PER_BUNDLE = 1000;
    private static final int MAX_BYTES_PER_BUNDLE = 32 * 1024 * 1024;
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path baseDir;

    public DefinitionWorkspaceMaterializer(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir);
    }

    public Path materialize(RuntimeDefinitionSnapshot definition) {
        Objects.requireNonNull(definition);
        String bundleHash = definition.definitionBundleHash();
        if (bundleHash == null || !bundleHash.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid definition bundle hash path segment");
        List<RuntimeWorkspaceFile> files = definition.workspaceFiles().stream()
                .sorted(Comparator.comparing(RuntimeWorkspaceFile::relativePath)).toList();
        Map<String, ExpectedFile> expected = expectedFiles(definition, files);
        verifyWorkspaceContentHash(definition, files);

        try {
            Files.createDirectories(baseDir);
            Path root = baseDir.toRealPath();
            Path destination = root.resolve(bundleHash).normalize();
            if (!destination.getParent().equals(root)) throw new IllegalArgumentException("Bundle path escaped workspace root");
            Object jvmLock = JVM_LOCKS.computeIfAbsent(destination, ignored -> new Object());
            synchronized (jvmLock) {
                Path lockFile = root.resolve(bundleHash + ".lock");
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.lock()) {
                    if (!lock.isValid()) throw new IllegalStateException("Could not lock workspace materialization");
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        verifyMaterialized(definition, destination, expected);
                        return destination;
                    }
                    return createAtomically(definition, root, destination, expected);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to materialize definition workspace for bundle " + bundleHash, error);
        }
    }

    private Path createAtomically(RuntimeDefinitionSnapshot definition, Path root, Path destination,
                                  Map<String, ExpectedFile> expected) throws IOException {
        Path temporary = Files.createTempDirectory(root, "." + definition.definitionBundleHash() + ".tmp-");
        try {
            for (Map.Entry<String, ExpectedFile> entry : expected.entrySet()) {
                Path target = resolveUnder(temporary, entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue().content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            verifyMaterialized(definition, temporary, expected, false);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            } catch (FileAlreadyExistsException concurrentWinner) {
                verifyMaterialized(definition, destination, expected);
                return destination;
            }
            makePublishedDirectoriesReadOnly(destination);
            for (String immutableFile : expected.keySet()) makeReadOnly(resolveUnder(destination, immutableFile));
            verifyMaterialized(definition, destination, expected);
            return destination;
        } finally {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) deleteTree(temporary);
        }
    }

    private static Map<String, ExpectedFile> expectedFiles(RuntimeDefinitionSnapshot definition,
                                                           List<RuntimeWorkspaceFile> files) {
        Map<String, ExpectedFile> expected = new LinkedHashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        addExpected(expected, foldedPaths, "AGENTS.md", definition.instructions());
        addExpected(expected, foldedPaths, MANIFEST_FILE, canonicalManifestJson(definition.workspaceManifestJson()));
        String marker = definition.definitionBundleHash() + "\n" + definition.workspaceContentHash() + "\n";
        addExpected(expected, foldedPaths, COMPLETE_FILE, marker);

        if (files.size() > MAX_FILES_PER_BUNDLE) throw new IllegalArgumentException("Workspace file count exceeds bundle limit");
        Map<String, int[]> perAsset = new HashMap<>();
        int totalBytes = 0;
        for (RuntimeWorkspaceFile file : files) {
            String path = normalizeRelativePath(file.relativePath());
            if ("AGENTS.md".equalsIgnoreCase(path) || MANIFEST_FILE.equalsIgnoreCase(path)
                    || COMPLETE_FILE.equalsIgnoreCase(path))
                throw new IllegalArgumentException("Asset file collides with a reserved workspace file: " + path);
            if ("SKILL".equals(file.capabilityType()) && !path.startsWith("skills/"))
                throw new IllegalArgumentException("Skill files must be under skills/: " + path);
            if ("KNOWLEDGE".equals(file.capabilityType()) && !path.startsWith("knowledge/"))
                throw new IllegalArgumentException("Knowledge files must be under knowledge/: " + path);
            if (!"SKILL".equals(file.capabilityType()) && !"KNOWLEDGE".equals(file.capabilityType()))
                throw new IllegalArgumentException("Unsupported workspace asset type: " + file.capabilityType());
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            if (content.length != file.byteSize()) throw new IllegalArgumentException("Workspace file byte size does not match: " + path);
            if (content.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Workspace file exceeds per-file limit: " + path);
            if (file.sha256().length() != 64 || !sha256(content).equalsIgnoreCase(file.sha256()))
                throw new IllegalArgumentException("Workspace file checksum does not match: " + path);
            int[] assetUsage = perAsset.computeIfAbsent(file.capabilityCode() + "@" + file.capabilityRevision(), ignored -> new int[2]);
            assetUsage[0]++;
            assetUsage[1] += content.length;
            if (assetUsage[0] > MAX_FILES_PER_ASSET || assetUsage[1] > MAX_BYTES_PER_ASSET)
                throw new IllegalArgumentException("Workspace asset exceeds per-asset quota: " + file.capabilityCode());
            totalBytes += content.length;
            if (totalBytes > MAX_BYTES_PER_BUNDLE) throw new IllegalArgumentException("Workspace contents exceed bundle limit");
            addExpected(expected, foldedPaths, path, file.content());
        }
        return expected;
    }

    private static void addExpected(Map<String, ExpectedFile> expected, Set<String> foldedPaths,
                                    String path, String content) {
        String normalized = normalizeRelativePath(path);
        if (!foldedPaths.add(normalized.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Workspace file paths collide case-insensitively: " + normalized);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        expected.put(normalized, new ExpectedFile(bytes, sha256(bytes)));
    }

    private static void verifyWorkspaceContentHash(RuntimeDefinitionSnapshot definition,
                                                   List<RuntimeWorkspaceFile> files) {
        String manifestJson = canonicalManifestJson(definition.workspaceManifestJson());
        String actual;
        if (definition.configuration().profile() == RuntimeProfile.LEGACY_STABLE) {
            actual = CanonicalJson.sha256(Map.of("instructions", definition.instructions(),
                    "manifestJson", manifestJson));
        } else {
            List<Map<String, Object>> manifest = files.stream().map(file -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("relativePath", normalizeRelativePath(file.relativePath()));
                item.put("sha256", file.sha256());
                item.put("byteSize", file.byteSize());
                item.put("capabilityCode", file.capabilityCode());
                item.put("capabilityRevision", file.capabilityRevision());
                item.put("capabilityType", file.capabilityType());
                return item;
            }).toList();
            actual = CanonicalJson.sha256(Map.of("instructions", definition.instructions(),
                    "manifestJson", manifestJson, "fileManifest", manifest));
        }
        if (!actual.equalsIgnoreCase(definition.workspaceContentHash()))
            throw new IllegalArgumentException("Frozen workspace content hash does not match its manifest and files");
    }

    private static String canonicalManifestJson(String manifestJson) {
        try {
            Object manifest = JSON.readValue(manifestJson, Object.class);
            if (!(manifest instanceof Map<?, ?>))
                throw new IllegalArgumentException("Frozen workspace manifest must be a JSON object");
            return CanonicalJson.write(manifest);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Frozen workspace manifest is not valid JSON", error);
        }
    }

    private static void verifyMaterialized(RuntimeDefinitionSnapshot definition, Path directory,
                                           Map<String, ExpectedFile> expected) throws IOException {
        verifyMaterialized(definition, directory, expected, true);
    }

    private static void verifyMaterialized(RuntimeDefinitionSnapshot definition, Path directory,
                                           Map<String, ExpectedFile> expected, boolean requireReadOnly) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Published workspace is not a regular directory: " + directory);
        for (Map.Entry<String, ExpectedFile> entry : expected.entrySet()) {
            Path target = resolveUnder(directory, entry.getKey());
            rejectSymlinkParents(directory, target);
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Published workspace file is missing or not regular: " + entry.getKey());
            if (requireReadOnly) verifyReadOnly(target);
            byte[] actual = Files.readAllBytes(target);
            if (actual.length != entry.getValue().content().length
                    || !sha256(actual).equals(entry.getValue().sha256()))
                throw new IllegalStateException("Published workspace file failed integrity check: " + entry.getKey());
        }
        verifyNoUnapprovedFiles(directory, expected.keySet(), requireReadOnly);
        String marker = Files.readString(directory.resolve(COMPLETE_FILE), StandardCharsets.UTF_8);
        String expectedMarker = definition.definitionBundleHash() + "\n" + definition.workspaceContentHash() + "\n";
        if (!expectedMarker.equals(marker)) throw new IllegalStateException("Published workspace completion marker does not match");
    }

    private static void verifyNoUnapprovedFiles(Path directory, Set<String> expected, boolean requireReadOnly) throws IOException {
        Set<String> expectedAssetPaths = new HashSet<>();
        for (String path : expected) {
            if (path.startsWith("skills/") || path.startsWith("knowledge/")) expectedAssetPaths.add(path);
        }
        Set<String> actualAssetPaths = new HashSet<>();
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.toList()) {
                if (path.equals(directory)) continue;
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("Published workspace contains a symbolic link: " + directory.relativize(path));
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (requireReadOnly && (relative.equals("skills") || relative.startsWith("skills/")
                        || relative.equals("knowledge") || relative.startsWith("knowledge/"))) verifyReadOnly(path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("Published workspace contains a non-regular entry: " + directory.relativize(path));
                if (relative.startsWith("skills/") || relative.startsWith("knowledge/")) {
                    actualAssetPaths.add(relative);
                } else if (expected.contains(relative) || relative.startsWith("plans/")) {
                    continue;
                } else {
                    throw new IllegalStateException("Published workspace contains an unapproved file: " + relative);
                }
            }
        }
        if (!actualAssetPaths.equals(expectedAssetPaths))
            throw new IllegalStateException("Published skills or knowledge contain unapproved or missing files");
    }

    private static void rejectSymlinkParents(Path root, Path target) {
        Path current = root;
        Path relativeParent = root.relativize(target).getParent();
        if (relativeParent == null) return;
        for (Path segment : relativeParent) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalStateException("Published workspace path contains a symbolic link");
        }
    }

    private static Path resolveUnder(Path root, String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path target = root.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Workspace path escaped package root");
        return target;
    }

    private static String normalizeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*") || path.contains("\\"))
            throw new IllegalArgumentException("Workspace file path must be a normalized relative POSIX path");
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment) || segment.contains(":"))
                throw new IllegalArgumentException("Workspace file path contains an unsafe segment");
        }
        return String.join("/", segments);
    }

    private static void makePublishedDirectoriesReadOnly(Path root) throws IOException {
        for (String namespace : List.of("skills", "knowledge")) {
            Path directory = root.resolve(namespace);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var walk = Files.walk(directory)) {
                List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path path : paths) makeReadOnly(path);
            }
        }
    }

    private static void makeReadOnly(Path path) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setReadOnly(true);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } else {
                Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
            }
            return;
        }
        throw new IOException("Filesystem does not support read-only published workspace files");
    }

    private static void verifyReadOnly(Path path) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) return;
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                .noneMatch(permission -> permission == PosixFilePermission.OWNER_WRITE
                        || permission == PosixFilePermission.GROUP_WRITE || permission == PosixFilePermission.OTHERS_WRITE)) return;
        throw new IllegalStateException("Published workspace entry is writable: " + path);
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (!Files.isSymbolicLink(path) && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (dos != null) dos.setReadOnly(false);
                        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (posix != null) {
                            EnumSet<PosixFilePermission> writable = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                                            PosixFilePermission.OTHERS_EXECUTE)
                                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
                            Files.setPosixFilePermissions(path, writable);
                        }
                    }
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    if (!path.equals(root)) throw error;
                    throw error;
                }
            }
        }
    }

    private record ExpectedFile(byte[] content, String sha256) {}
}
