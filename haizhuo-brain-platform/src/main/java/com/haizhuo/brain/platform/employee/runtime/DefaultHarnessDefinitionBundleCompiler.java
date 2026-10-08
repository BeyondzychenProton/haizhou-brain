package com.haizhuo.brain.platform.employee.runtime;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetFile;
import com.haizhuo.brain.platform.employee.CapabilityAssetRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetRevision;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministically compiles the reviewed employee draft into an immutable runtime bundle. */
public class DefaultHarnessDefinitionBundleCompiler implements HarnessDefinitionBundleCompiler {
    static final int MAX_ITERATIONS_FLOOR = 1;
    static final int MAX_ITERATIONS_CEILING = 20;

    private final AgentDefinitionRepository repository;
    private final CapabilityAssetRepository assets;

    /** Compatibility constructor for legacy-only callers. */
    public DefaultHarnessDefinitionBundleCompiler(AgentDefinitionRepository repository) {
        this(repository, null);
    }

    public DefaultHarnessDefinitionBundleCompiler(AgentDefinitionRepository repository,
                                                  CapabilityAssetRepository assets) {
        this.repository = repository;
        this.assets = assets;
    }

    @Override
    public HarnessDefinitionBundle compile(long definitionVersionId, String employeeName, AgentDefinitionDraft draft) {
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        if (employeeName == null || employeeName.isBlank()) throw new IllegalArgumentException("employeeName is required");
        if (draft.modelProvider() == null || draft.modelProvider().isBlank())
            throw new IllegalArgumentException("Model provider must not be blank");
        if (draft.modelName() == null || draft.modelName().isBlank())
            throw new IllegalArgumentException("Model name must not be blank");
        if (draft.instructions() == null || draft.instructions().isBlank())
            throw new IllegalArgumentException("Instructions must not be blank");

        EmployeeRuntimeConfiguration configuration = draft.configuration();
        int maxIterations = configuration.runtimePolicy().maxIterations();
        if (maxIterations < MAX_ITERATIONS_FLOOR || maxIterations > MAX_ITERATIONS_CEILING)
            throw new IllegalArgumentException("maxIterations must be between 1 and 20");
        boolean legacy = configuration.profile() == RuntimeProfile.LEGACY_STABLE;

        List<PublishedToolSchema> catalog = new ArrayList<>();
        List<RuntimeWorkspaceFile> workspaceFiles = new ArrayList<>();
        Set<String> toolNames = new HashSet<>();
        Set<String> workspacePaths = new HashSet<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> knowledge = new ArrayList<>();
        for (CapabilitySelection selection : draft.capabilities()) {
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Capability revision does not exist: " + selection.capabilityCode() + "@" + selection.revision()));
            if (entry.type() == CapabilityBinding.CapabilityType.SKILL
                    || entry.type() == CapabilityBinding.CapabilityType.KNOWLEDGE) {
                if (legacy) throw new IllegalArgumentException("LEGACY_STABLE cannot include skill or knowledge assets");
                CapabilityAssetRevision asset = assets == null ? null
                        : assets.findRevisionByCodeAndRevision(selection.capabilityCode(), selection.revision()).orElse(null);
                if (asset == null || asset.type() != entry.type())
                    throw new IllegalArgumentException("Reviewed asset revision does not exist or has the wrong type: "
                            + selection.capabilityCode() + "@" + selection.revision());
                Map<String, Object> manifestEntry = addAsset(asset, workspaceFiles, workspacePaths);
                (entry.type() == CapabilityBinding.CapabilityType.SKILL ? skills : knowledge).add(manifestEntry);
                continue;
            }
            if (entry.type() != CapabilityBinding.CapabilityType.TOOL
                    && entry.type() != CapabilityBinding.CapabilityType.MCP)
                throw new IllegalArgumentException("Unsupported capability type in runtime bundle: " + selection.capabilityCode());
            if (entry.toolName() == null || entry.toolName().isBlank())
                throw new IllegalArgumentException("Tool name must not be blank: " + selection.capabilityCode());
            if (entry.description() == null || entry.description().isBlank())
                throw new IllegalArgumentException("Tool description must not be blank: " + selection.capabilityCode());
            if (entry.inputSchema() == null || entry.inputSchema().isEmpty()
                    || !"object".equals(entry.inputSchema().get("type")))
                throw new IllegalArgumentException("Tool input schema must be a JSON Schema object: " + selection.capabilityCode());
            if (!toolNames.add(entry.toolName()))
                throw new IllegalArgumentException("Duplicate tool name in draft: " + entry.toolName());
            catalog.add(new PublishedToolSchema(entry.capabilityRevisionId(), entry.capabilityCode(), entry.toolName(),
                    entry.description(), entry.inputSchema(), "mcp.read".equals(entry.businessAction()),
                    entry.requiresConfirmation(), entry.businessAction()));
        }

        workspaceFiles.sort(Comparator.comparing(RuntimeWorkspaceFile::relativePath));
        skills.sort(Comparator.comparing(entry -> entry.get("capabilityCode").toString()));
        knowledge.sort(Comparator.comparing(entry -> entry.get("capabilityCode").toString()));
        List<Map<String, Object>> fileManifest = workspaceFiles.stream().map(DefaultHarnessDefinitionBundleCompiler::fileProjection).toList();

        Map<String, Object> workspaceManifest = new LinkedHashMap<>();
        workspaceManifest.put("schemaVersion", 1);
        workspaceManifest.put("agents", "AGENTS.md");
        workspaceManifest.put("skills", legacy ? List.of() : skills);
        workspaceManifest.put("subagents", List.of());
        workspaceManifest.put("knowledge", legacy ? List.of() : knowledge);
        if (!legacy) {
            workspaceManifest.put("runtimeProfile", configuration.profile().name());
            workspaceManifest.put("files", fileManifest);
        }
        String workspaceManifestJson = CanonicalJson.write(workspaceManifest);
        // Keep the LEGACY_STABLE workspace projection byte-for-byte compatible with V1.
        String workspaceContentHash = legacy
                ? CanonicalJson.sha256(Map.of("instructions", draft.instructions(), "manifestJson", workspaceManifestJson))
                : CanonicalJson.sha256(Map.of("instructions", draft.instructions(), "manifestJson", workspaceManifestJson,
                        "fileManifest", fileManifest));

        String toolCatalogHash = CanonicalJson.sha256(canonicalCatalog(catalog));
        String subAgentManifestJson = subAgentManifest(configuration);
        String policyJson = policy(configuration, maxIterations);

        Map<String, Object> bundleProjection = new LinkedHashMap<>();
        bundleProjection.put("definitionVersionId", definitionVersionId);
        bundleProjection.put("employeeName", employeeName);
        bundleProjection.put("instructions", draft.instructions());
        bundleProjection.put("modelProvider", draft.modelProvider());
        bundleProjection.put("modelName", draft.modelName());
        bundleProjection.put("maxIterations", maxIterations);
        bundleProjection.put("workspaceContentHash", workspaceContentHash);
        bundleProjection.put("toolCatalog", canonicalCatalog(catalog));
        bundleProjection.put("toolCatalogHash", toolCatalogHash);
        bundleProjection.put("subAgentManifestJson", subAgentManifestJson);
        bundleProjection.put("policyJson", policyJson);
        if (!legacy) {
            bundleProjection.put("configuration", configurationProjection(configuration));
            bundleProjection.put("workspaceFiles", fileManifest);
        }
        String bundleHash = CanonicalJson.sha256(bundleProjection);

        return new HarnessDefinitionBundle(0, definitionVersionId, employeeName, draft.instructions(),
                draft.modelProvider(), draft.modelName(), maxIterations, workspaceManifestJson, workspaceContentHash,
                catalog, toolCatalogHash, subAgentManifestJson, policyJson, bundleHash, configuration,
                workspaceFiles, java.time.Instant.now());
    }

    private static Map<String, Object> addAsset(CapabilityAssetRevision asset,
                                                 List<RuntimeWorkspaceFile> workspaceFiles,
                                                 Set<String> occupiedPaths) {
        String root = assetRoot(asset);
        String directory = asset.type() == CapabilityBinding.CapabilityType.SKILL ? "skills" : "knowledge";
        String assetDirectory = directory + "/" + root;
        for (CapabilityAssetFile file : asset.files()) {
            String relative = normalizeAssetPath(file.relativePath());
            String workspacePath = assetDirectory + "/" + relative;
            if (!occupiedPaths.add(workspacePath.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Asset path collides case-insensitively: " + workspacePath);
            byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length != file.byteSize())
                throw new IllegalArgumentException("Asset file byte size changed after review: " + workspacePath);
            if (!sha256(bytes).equalsIgnoreCase(file.sha256()))
                throw new IllegalArgumentException("Asset file hash changed after review: " + workspacePath);
            workspaceFiles.add(new RuntimeWorkspaceFile(workspacePath, file.content(), file.sha256(), file.byteSize(),
                    asset.capabilityCode(), asset.revision(), asset.type().name()));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("capabilityCode", asset.capabilityCode());
        entry.put("revision", asset.revision());
        entry.put("assetHash", asset.assetHash());
        entry.put("path", assetDirectory);
        return entry;
    }

    private static String assetRoot(CapabilityAssetRevision asset) {
        String prefix = asset.type() == CapabilityBinding.CapabilityType.SKILL ? "skill." : "knowledge.";
        if (!asset.capabilityCode().startsWith(prefix))
            throw new IllegalArgumentException("Asset capability code must use " + prefix + " namespace");
        String slug = asset.capabilityCode().substring(prefix.length());
        if (!slug.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Asset capability code has an unsafe workspace name");
        return slug;
    }

    private static String normalizeAssetPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*") || path.contains("\\"))
            throw new IllegalArgumentException("Asset path must be a normalized relative POSIX path");
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment) || segment.contains(":"))
                throw new IllegalArgumentException("Asset path contains an unsafe segment");
        }
        return String.join("/", segments);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static String policy(EmployeeRuntimeConfiguration configuration, int maxIterations) {
        if (configuration.profile() == RuntimeProfile.LEGACY_STABLE)
            return CanonicalJson.write(Map.of("memoryHooksEnabled", false, "memoryToolsEnabled", false,
                    "maxIterations", maxIterations));
        EmployeeRuntimeConfiguration.RuntimePolicy policy = configuration.runtimePolicy();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("memoryHooksEnabled", false);
        values.put("memoryToolsEnabled", false);
        values.put("maxIterations", maxIterations);
        values.put("maxParallelDelegations", policy.maxParallelDelegations());
        values.put("maxExpertInvocationsPerRun", policy.maxExpertInvocationsPerRun());
        values.put("syncTimeoutSeconds", policy.syncTimeoutSeconds());
        values.put("profile", configuration.profile().name());
        return CanonicalJson.write(values);
    }

    private static String subAgentManifest(EmployeeRuntimeConfiguration configuration) {
        if (configuration.profile() == RuntimeProfile.LEGACY_STABLE || configuration.members().isEmpty())
            return CanonicalJson.write(Map.of("schemaVersion", 1, "subagents", List.of()));
        List<Map<String, Object>> members = configuration.members().stream().map(member -> Map.<String, Object>of(
                "roleId", member.roleId(), "employeeId", member.employeeId(),
                "definitionVersionId", member.definitionVersionId(), "steps", member.steps())).toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("subagents", members);
        manifest.put("team", configurationProjection(configuration).get("team"));
        return CanonicalJson.write(manifest);
    }

    private static Map<String, Object> configurationProjection(EmployeeRuntimeConfiguration configuration) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", configuration.schemaVersion());
        projection.put("profile", configuration.profile().name());
        EmployeeRuntimeConfiguration.RuntimePolicy policy = configuration.runtimePolicy();
        projection.put("runtimePolicy", Map.of("maxIterations", policy.maxIterations(),
                "maxParallelDelegations", policy.maxParallelDelegations(),
                "maxExpertInvocationsPerRun", policy.maxExpertInvocationsPerRun(),
                "syncTimeoutSeconds", policy.syncTimeoutSeconds(), "memoryEnabled", policy.memoryEnabled()));
        EmployeeRuntimeConfiguration.TeamConfiguration team = configuration.team();
        if (team == null) {
            projection.put("team", null);
        } else {
            projection.put("team", Map.of("defaultRoleId", team.defaultRoleId() == null ? "" : team.defaultRoleId(),
                    "userSelectableRoles", team.userSelectableRoles(),
                    "allowedDelegations", team.allowedDelegations().stream().map(item -> Map.of(
                            "fromRoleId", item.fromRoleId() == null ? "" : item.fromRoleId(),
                            "toRoleId", item.toRoleId() == null ? "" : item.toRoleId())).toList()));
        }
        projection.put("members", configuration.members().stream().map(member -> Map.of(
                "roleId", member.roleId(), "employeeId", member.employeeId(),
                "definitionVersionId", member.definitionVersionId(), "steps", member.steps())).toList());
        return projection;
    }

    private static Map<String, Object> fileProjection(RuntimeWorkspaceFile file) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("relativePath", file.relativePath());
        item.put("sha256", file.sha256());
        item.put("byteSize", file.byteSize());
        item.put("capabilityCode", file.capabilityCode());
        item.put("capabilityRevision", file.capabilityRevision());
        item.put("capabilityType", file.capabilityType());
        return item;
    }

    private static List<Map<String, Object>> canonicalCatalog(List<PublishedToolSchema> catalog) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PublishedToolSchema tool : catalog) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityRevisionId", tool.capabilityRevisionId());
            item.put("capabilityReferenceId", tool.capabilityReferenceId());
            item.put("toolName", tool.toolName());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            item.put("readOnly", tool.readOnly());
            item.put("requiresConfirmation", tool.requiresConfirmation());
            item.put("businessAction", tool.businessAction());
            entries.add(item);
        }
        return entries;
    }
}
