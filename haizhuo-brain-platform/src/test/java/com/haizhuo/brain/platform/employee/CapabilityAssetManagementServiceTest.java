package com.haizhuo.brain.platform.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CapabilityAssetManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void validatesAndHashesApprovedSkillFilesOnTheServer() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetManagementService service = service(repository);

        CapabilityAssetDraft draft = service.saveDraft("skill.customer-analysis", 0,
                CapabilityBinding.CapabilityType.SKILL, "客户分析", "客户分析流程",
                List.of(new CapabilityAssetFileInput("SKILL.md",
                                "---\nname: customer-analysis\ndescription: Analyze customer needs\n---\n按字段分析客户。"),
                        new CapabilityAssetFileInput("references/fields.md", "客户字段说明。")),
                7, "创建经过审核的技能");

        assertEquals(1, draft.draftRevision());
        assertEquals(2, draft.files().size());
        assertEquals(64, draft.assetHash().length());
        assertEquals("text/markdown; charset=utf-8", draft.files().get(0).mediaType());
        assertNotNull(draft.files().get(1).sha256());
        assertEquals("SKILL.md", repository.saved.files().get(0).relativePath());
    }

    @Test
    void rejectsTraversalAndWindowsPathAliasesBeforeRepositoryWrite() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetValidationException error = assertThrows(CapabilityAssetValidationException.class,
                () -> service(repository).saveDraft("skill.customer-analysis", 0,
                        CapabilityBinding.CapabilityType.SKILL, "客户分析", "客户分析流程",
                        List.of(skillFile(), new CapabilityAssetFileInput("../outside.md", "逃逸")),
                        7, "测试非法路径"));

        assertEquals("files[1].relativePath", error.fieldPath());
        assertEquals(0, repository.saves);
    }

    @Test
    void rejectsCaseInsensitivePathCollisionsAndSkillNameMismatch() {
        CapabilityAssetValidationException collision = assertThrows(CapabilityAssetValidationException.class,
                () -> service(new FakeRepository()).saveDraft("skill.customer-analysis", 0,
                        CapabilityBinding.CapabilityType.SKILL, "客户分析", "客户分析流程",
                        List.of(skillFile(), new CapabilityAssetFileInput("references/Guide.md", "A"),
                                new CapabilityAssetFileInput("references/guide.md", "B")),
                        7, "测试大小写冲突"));
        assertEquals("files[2].relativePath", collision.fieldPath());

        CapabilityAssetValidationException mismatchedName = assertThrows(CapabilityAssetValidationException.class,
                () -> service(new FakeRepository()).saveDraft("skill.customer-analysis", 0,
                        CapabilityBinding.CapabilityType.SKILL, "客户分析", "客户分析流程",
                        List.of(new CapabilityAssetFileInput("SKILL.md",
                                "---\nname: proposal-writing\ndescription: Other skill\n---\n正文")),
                        7, "测试 Skill 名称"));
        assertEquals("files[SKILL.md].content", mismatchedName.fieldPath());
    }

    @Test
    void rejectsUnmaterializableSkillNamesAndWhitespaceOnlyFiles() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetValidationException invalidName = assertThrows(CapabilityAssetValidationException.class,
                () -> service(repository).saveDraft("skill.customer.analysis", 0,
                        CapabilityBinding.CapabilityType.SKILL, "客户分析", "客户分析流程",
                        List.of(skillFile()), 7, "测试技能编码"));
        assertEquals("capabilityCode", invalidName.fieldPath());

        CapabilityAssetValidationException blankFile = assertThrows(CapabilityAssetValidationException.class,
                () -> service(repository).saveDraft("knowledge.product-catalog", 0,
                        CapabilityBinding.CapabilityType.KNOWLEDGE, "产品目录", "产品资料入口",
                        List.of(new CapabilityAssetFileInput("KNOWLEDGE.md", "  \n\t")), 7, "测试空白文件"));
        assertEquals("files[0].content", blankFile.fieldPath());
        assertEquals(0, repository.saves);
    }

    @Test
    void knowledgeRequiresAReadOnlyEntrypointAndTextFileTypes() {
        CapabilityAssetDraft draft = service(new FakeRepository()).saveDraft("knowledge.product-catalog", 0,
                CapabilityBinding.CapabilityType.KNOWLEDGE, "产品目录", "产品资料入口",
                List.of(new CapabilityAssetFileInput("KNOWLEDGE.md", "# 产品目录\n只读资料。")),
                7, "创建知识资料");
        assertEquals(CapabilityBinding.CapabilityType.KNOWLEDGE, draft.type());

        CapabilityAssetValidationException script = assertThrows(CapabilityAssetValidationException.class,
                () -> service(new FakeRepository()).saveDraft("knowledge.product-catalog", 0,
                        CapabilityBinding.CapabilityType.KNOWLEDGE, "产品目录", "产品资料入口",
                        List.of(new CapabilityAssetFileInput("KNOWLEDGE.md", "# 目录"),
                                new CapabilityAssetFileInput("run.ps1", "Write-Host unsafe")),
                        7, "测试禁止脚本"));
        assertEquals("files[1].relativePath", script.fieldPath());
    }

    @Test
    void revisionHistoryRequiresAnExistingAssetAndAValidPageSize() {
        FakeRepository repository = new FakeRepository();
        CapabilityAssetManagementService service = service(repository);
        assertThrows(CapabilityAssetNotFoundException.class, () -> service.listRevisions("skill.customer-analysis", null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.listRevisions("skill.customer-analysis", null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listRevisions("skill.customer-analysis", "x".repeat(2049), 20));

        service.saveDraft("skill.customer-analysis", 0, CapabilityBinding.CapabilityType.SKILL,
                "客户分析", "客户分析流程", List.of(skillFile()), 7, "创建资产");
        var page = service.listRevisions("skill.customer-analysis", null, 20);
        assertEquals(List.of(), page.items());
        assertEquals(null, page.nextCursor());
        assertEquals(false, page.hasMore());
    }

    private static CapabilityAssetFileInput skillFile() {
        return new CapabilityAssetFileInput("SKILL.md",
                "---\nname: customer-analysis\ndescription: Analyze customer needs\n---\n按字段分析客户。");
    }

    private static CapabilityAssetManagementService service(FakeRepository repository) {
        return new CapabilityAssetManagementService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository implements CapabilityAssetRepository {
        private CapabilityAssetDraft saved;
        private int saves;
        @Override public List<CapabilityAssetSummary> listAssetSummaries() { return List.of(); }
        @Override public boolean assetExists(String code) { return saved != null && saved.capabilityCode().equals(code); }
        @Override public CapabilityAssetRevisionPage listRevisionSummaries(String code, String cursor, int limit) {
            return new CapabilityAssetRevisionPage(List.of(), null, false);
        }
        @Override public Optional<CapabilityAssetDraft> findDraft(String code) { return Optional.ofNullable(saved); }
        @Override public Optional<CapabilityAssetRevision> findRevision(String code, long id) { return Optional.empty(); }
        @Override public CapabilityAssetDraft saveDraft(CapabilityAssetDraft draft, int expected, String reason) {
            saves++;
            saved = draft;
            return draft;
        }
        @Override public CapabilityAssetRevision publish(String code, int expected, String requestId, long actor, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
