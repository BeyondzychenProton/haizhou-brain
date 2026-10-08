package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理员资产输入校验与服务端 manifest/hash 生成；不执行资产中的任何脚本。 */
public class CapabilityAssetManagementService {
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FILES = 100;
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern SKILL_FRONT_MATTER = Pattern.compile("(?s)\\A---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|\\z)");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");
    private final CapabilityAssetRepository repository;
    private final Clock clock;

    public CapabilityAssetManagementService(CapabilityAssetRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<CapabilityAssetSummary> listAssets() { return repository.listAssetSummaries(); }

    public CapabilityAssetDraft getDraft(String capabilityCode) {
        return repository.findDraft(validateCode(capabilityCode)).orElseThrow(() ->
                new IllegalArgumentException("Capability asset draft was not found"));
    }

    public CapabilityAssetRevision getRevision(String capabilityCode, long capabilityRevisionId) {
        if (capabilityRevisionId <= 0) throw new IllegalArgumentException("capabilityRevisionId must be positive");
        return repository.findRevision(validateCode(capabilityCode), capabilityRevisionId).orElseThrow(() ->
                new IllegalArgumentException("Capability asset revision was not found"));
    }

    public CapabilityAssetDraft saveDraft(String capabilityCode, int expectedDraftRevision,
                                          CapabilityBinding.CapabilityType type, String displayName,
                                          String description, List<CapabilityAssetFileInput> inputs,
                                          long actorId, String reason) {
        String code = validateCode(capabilityCode);
        if (expectedDraftRevision < 0) fail("expectedDraftRevision", "草稿修订号不能为负数");
        if (actorId <= 0) fail("actorId", "操作者身份无效");
        requireReason(reason);
        if (type != CapabilityBinding.CapabilityType.SKILL && type != CapabilityBinding.CapabilityType.KNOWLEDGE)
            fail("type", "资产类型只能是 SKILL 或 KNOWLEDGE");
        if (type == CapabilityBinding.CapabilityType.SKILL && !assetName(code).matches("[a-z0-9][a-z0-9-]{0,63}"))
            fail("capabilityCode", "SKILL 编码名称必须是 1 到 64 位小写字母、数字或连字符");
        String name = requiredText(displayName, 128, "displayName");
        String summary = requiredText(description, 1000, "description");
        List<CapabilityAssetFile> files = validateFiles(type, code, inputs);
        String entrypoint = type == CapabilityBinding.CapabilityType.SKILL ? "SKILL.md" : "KNOWLEDGE.md";
        List<Map<String, Object>> fileManifest = files.stream().sorted(Comparator.comparing(CapabilityAssetFile::relativePath))
                .map(CapabilityAssetManagementService::fileManifest).toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("capabilityCode", code);
        manifest.put("kind", type.name());
        manifest.put("entrypoint", entrypoint);
        manifest.put("files", fileManifest);
        String manifestJson = CanonicalJson.write(manifest);
        String assetHash = CanonicalJson.sha256(Map.of("capabilityCode", code, "kind", type.name(),
                "displayName", name, "description", summary, "manifest", manifest));
        CapabilityAssetDraft draft = new CapabilityAssetDraft(code, type, expectedDraftRevision + 1,
                name, summary, files, manifestJson, assetHash, actorId, clock.instant());
        return repository.saveDraft(draft, expectedDraftRevision, reason.trim());
    }

    public CapabilityAssetRevision publish(String capabilityCode, int expectedDraftRevision,
                                           String requestId, long actorId, String reason) {
        String code = validateCode(capabilityCode);
        if (expectedDraftRevision <= 0) fail("expectedDraftRevision", "草稿修订号必须为正数");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            fail("requestId", "发布请求标识长度必须为 1 到 128 个字符");
        if (actorId <= 0) fail("actorId", "操作者身份无效");
        requireReason(reason);
        return repository.publish(code, expectedDraftRevision, requestId.trim(), actorId, reason.trim());
    }

    private static List<CapabilityAssetFile> validateFiles(CapabilityBinding.CapabilityType type, String code,
                                                            List<CapabilityAssetFileInput> inputs) {
        if (inputs == null || inputs.isEmpty()) fail("files", "至少提交一个文本文件");
        if (inputs.size() > MAX_FILES) fail("files", "单个资产最多包含 100 个文件");
        Set<String> foldedPaths = new HashSet<>();
        List<CapabilityAssetFile> files = new ArrayList<>();
        int totalBytes = 0;
        for (int index = 0; index < inputs.size(); index++) {
            CapabilityAssetFileInput input = inputs.get(index);
            String prefix = "files[" + index + "]";
            if (input == null) fail(prefix, "文件不能为空");
            String path = normalizePath(input.relativePath(), prefix + ".relativePath");
            if (!foldedPaths.add(path.toLowerCase(Locale.ROOT)))
                fail(prefix + ".relativePath", "文件路径重复或仅大小写不同");
            String mediaType = mediaType(path, prefix + ".relativePath");
            if (input.content() == null || input.content().isBlank()) fail(prefix + ".content", "文件内容不能为空");
            byte[] bytes = input.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) fail(prefix + ".content", "单个文件不能超过 256 KiB");
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) fail("files", "资产总大小不能超过 2 MiB");
            files.add(new CapabilityAssetFile(path, mediaType, input.content(), sha256(bytes), bytes.length));
        }
        String requiredEntry = type == CapabilityBinding.CapabilityType.SKILL ? "SKILL.md" : "KNOWLEDGE.md";
        CapabilityAssetFile entry = files.stream().filter(file -> file.relativePath().equals(requiredEntry)).findFirst()
                .orElseThrow(() -> new CapabilityAssetValidationException("files", "必须包含根目录 " + requiredEntry));
        if (type == CapabilityBinding.CapabilityType.SKILL) validateSkillEntry(code, entry.content());
        return List.copyOf(files);
    }

    private static String normalizePath(String raw, String field) {
        if (raw == null || raw.isBlank() || raw.length() > 512) fail(field, "文件路径长度必须为 1 到 512 个字符");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (normalized.startsWith("/") || normalized.startsWith("\\") || normalized.matches("^[A-Za-z]:.*")
                || normalized.contains("\\") || normalized.indexOf(':') >= 0 || normalized.indexOf('\0') >= 0)
            fail(field, "只允许使用规范的相对路径和 / 分隔符");
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.chars().anyMatch(Character::isISOControl)
                    || WINDOWS_DEVICE.matcher(segment).matches())
                fail(field, "文件路径包含非法或 Windows 保留路径段");
        }
        return normalized;
    }

    private static String mediaType(String path, String field) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml; charset=utf-8";
        fail(field, "首期只允许 Markdown、TXT、CSV、JSON 和 YAML 文本文件");
        return "";
    }

    private static void validateSkillEntry(String code, String content) {
        Matcher matcher = SKILL_FRONT_MATTER.matcher(content);
        if (!matcher.find()) fail("files[SKILL.md].content", "SKILL.md 必须包含 YAML front matter");
        String front = matcher.group(1);
        String name = frontMatterValue(front, "name");
        String description = frontMatterValue(front, "description");
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,63}"))
            fail("files[SKILL.md].content", "front matter 必须包含合法的 name");
        if (!name.equals(assetName(code))) fail("files[SKILL.md].content", "front matter name 必须与资产编码名称一致");
        if (description == null || description.isBlank() || description.length() > 1024)
            fail("files[SKILL.md].content", "front matter 必须包含不超过 1024 字的 description");
    }

    private static String frontMatterValue(String frontMatter, String key) {
        for (String line : frontMatter.split("\\R")) {
            String prefix = key + ":";
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length() - 1).trim();
                return value;
            }
        }
        return null;
    }

    private static String assetName(String code) {
        if (code.startsWith("skill.")) return code.substring("skill.".length());
        if (code.startsWith("knowledge.")) return code.substring("knowledge.".length());
        return code;
    }

    private static Map<String, Object> fileManifest(CapabilityAssetFile file) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", file.relativePath());
        value.put("mediaType", file.mediaType());
        value.put("byteSize", file.byteSize());
        value.put("sha256", file.sha256());
        return value;
    }

    private static String validateCode(String code) {
        if (code == null || !CODE.matcher(code).matches() || code.endsWith(".") || code.endsWith("-") || code.endsWith("_"))
            fail("capabilityCode", "能力编码只能包含小写字母、数字、点、下划线和连字符");
        return code;
    }

    private static String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            fail(field, field + " 不能为空且最多 " + max + " 个字符");
        return value.trim();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            fail("reason", "操作原因长度必须为 1 到 500 个字符");
    }

    private static String sha256(byte[] content) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static void fail(String field, String message) {
        throw new CapabilityAssetValidationException(field, message);
    }
}
