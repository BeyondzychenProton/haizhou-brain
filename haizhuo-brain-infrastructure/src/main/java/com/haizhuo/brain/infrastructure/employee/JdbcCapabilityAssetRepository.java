package com.haizhuo.brain.infrastructure.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.CapabilityAssetDraft;
import com.haizhuo.brain.platform.employee.CapabilityAssetFile;
import com.haizhuo.brain.platform.employee.CapabilityAssetRepository;
import com.haizhuo.brain.platform.employee.CapabilityAssetRevision;
import com.haizhuo.brain.platform.employee.CapabilityAssetSummary;
import com.haizhuo.brain.platform.employee.CapabilityAssetDraftConflictException;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 仓储：草稿 CAS、资产 revision、文件和管理审计在各自事务内提交。 */
@Repository
@Profile("!test")
public class JdbcCapabilityAssetRepository implements CapabilityAssetRepository {
    private static final TypeReference<List<CapabilityAssetFile>> FILES = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate publishTx;
    private final ObjectMapper json;

    public JdbcCapabilityAssetRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.publishTx = new TransactionTemplate(transactions);
        this.publishTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.json = json;
    }

    @Override public List<CapabilityAssetSummary> listAssetSummaries() {
        List<CapabilityAssetSummary> result = new ArrayList<>();
        List<DraftSummary> drafts = jdbc.query("SELECT capability_code,capability_type,display_name,draft_revision,updated_at "
                        + "FROM capability_asset_draft ORDER BY capability_code",
                (rs, n) -> new DraftSummary(rs.getString(1), CapabilityBinding.CapabilityType.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant()));
        for (DraftSummary draft : drafts) {
            List<PublishedSummary> published = jdbc.query("SELECT a.capability_revision_id,r.revision,a.asset_hash "
                            + "FROM capability_asset_revision a JOIN capability_revision r ON r.capability_revision_id=a.capability_revision_id "
                            + "WHERE a.capability_code=? ORDER BY a.reviewed_at DESC,a.capability_revision_id DESC LIMIT 1",
                    (rs, n) -> new PublishedSummary(rs.getLong(1), rs.getString(2), rs.getString(3)), draft.code());
            PublishedSummary latest = published.isEmpty() ? null : published.get(0);
            result.add(new CapabilityAssetSummary(draft.code(), draft.type(), draft.displayName(), draft.draftRevision(),
                    latest == null ? null : latest.revision(), latest == null ? 0 : latest.id(),
                    latest == null ? null : latest.assetHash(), draft.updatedAt()));
        }
        return List.copyOf(result);
    }

    @Override public Optional<CapabilityAssetDraft> findDraft(String capabilityCode) {
        List<CapabilityAssetDraft> rows = readDraftRows(capabilityCode, false);
        return rows.stream().findFirst();
    }

    @Override public Optional<CapabilityAssetRevision> findRevision(String capabilityCode, long capabilityRevisionId) {
        List<RevisionHead> rows = jdbc.query("SELECT a.capability_revision_id,a.capability_code,a.capability_type,r.revision,"
                        + "r.display_name,r.description,a.manifest_json,a.asset_hash,a.publish_request_id,a.reviewed_by,a.reviewed_at "
                        + "FROM capability_asset_revision a JOIN capability_revision r ON r.capability_revision_id=a.capability_revision_id "
                        + "WHERE a.capability_code=? AND a.capability_revision_id=?",
                (rs, n) -> new RevisionHead(rs.getLong(1), rs.getString(2), CapabilityBinding.CapabilityType.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getLong(10), rs.getTimestamp(11).toInstant()), capabilityCode, capabilityRevisionId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(toRevision(rows.get(0)));
    }

    @Override public Optional<CapabilityAssetRevision> findRevisionByCodeAndRevision(String capabilityCode, String revision) {
        List<RevisionHead> rows = jdbc.query("SELECT a.capability_revision_id,a.capability_code,a.capability_type,r.revision,"
                        + "r.display_name,r.description,a.manifest_json,a.asset_hash,a.publish_request_id,a.reviewed_by,a.reviewed_at "
                        + "FROM capability_asset_revision a JOIN capability_revision r ON r.capability_revision_id=a.capability_revision_id "
                        + "WHERE a.capability_code=? AND r.revision=?",
                (rs, n) -> new RevisionHead(rs.getLong(1), rs.getString(2), CapabilityBinding.CapabilityType.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getLong(10), rs.getTimestamp(11).toInstant()), capabilityCode, revision);
        return rows.stream().findFirst().map(this::toRevision);
    }

    @Override public CapabilityAssetDraft saveDraft(CapabilityAssetDraft draft, int expectedDraftRevision, String reason) {
        return tx.execute(status -> {
            Instant now = draft.updatedAt();
            ensureDefinition(draft.capabilityCode(), draft.type(), draft.updatedBy(), now, reason);
            List<Integer> current = jdbc.query("SELECT draft_revision FROM capability_asset_draft WHERE capability_code=? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), draft.capabilityCode());
            String previousHash = null;
            if (current.isEmpty()) {
                if (expectedDraftRevision != 0) throw new CapabilityAssetDraftConflictException("Asset draft revision is stale");
                jdbc.update("INSERT INTO capability_asset_draft(capability_code,capability_type,draft_revision,display_name,description,"
                                + "files_json,manifest_json,asset_hash,updated_by,updated_at,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        draft.capabilityCode(), draft.type().name(), draft.draftRevision(), draft.displayName(), draft.description(),
                        writeFiles(draft.files()), draft.manifestJson(), draft.assetHash(), draft.updatedBy(), Timestamp.from(now), reason);
            } else {
                int actual = current.get(0);
                if (actual != expectedDraftRevision) throw new CapabilityAssetDraftConflictException("Asset draft revision is stale");
                previousHash = jdbc.queryForObject("SELECT asset_hash FROM capability_asset_draft WHERE capability_code=?",
                        String.class, draft.capabilityCode());
                jdbc.update("UPDATE capability_asset_draft SET capability_type=?,draft_revision=?,display_name=?,description=?,files_json=?,"
                                + "manifest_json=?,asset_hash=?,updated_by=?,updated_at=?,reason=? WHERE capability_code=?",
                        draft.type().name(), draft.draftRevision(), draft.displayName(), draft.description(), writeFiles(draft.files()),
                        draft.manifestJson(), draft.assetHash(), draft.updatedBy(), Timestamp.from(now), reason, draft.capabilityCode());
            }
            appendAudit(draft.capabilityCode(), "DRAFT_SAVED", draft.updatedBy(), null, reason,
                    previousHash, draft.assetHash(), now);
            return findDraft(draft.capabilityCode()).orElseThrow();
        });
    }

    @Override public CapabilityAssetRevision publish(String capabilityCode, int expectedDraftRevision, String requestId,
                                                       long actorId, String reason) {
        return publishTx.execute(status -> {
            List<Long> replay = jdbc.query("SELECT capability_revision_id FROM capability_asset_revision "
                            + "WHERE capability_code=? AND publish_request_id=?",
                    (rs, n) -> rs.getLong(1), capabilityCode, requestId);
            if (!replay.isEmpty()) return findRevision(capabilityCode, replay.get(0)).orElseThrow();

            List<Integer> locked = jdbc.query("SELECT draft_revision FROM capability_asset_draft WHERE capability_code=? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), capabilityCode);
            if (locked.isEmpty()) throw new IllegalArgumentException("Capability asset draft was not found");
            // Another publish with this request ID may have committed while this transaction waited for the draft lock.
            replay = jdbc.query("SELECT capability_revision_id FROM capability_asset_revision "
                            + "WHERE capability_code=? AND publish_request_id=? FOR UPDATE",
                    (rs, n) -> rs.getLong(1), capabilityCode, requestId);
            if (!replay.isEmpty()) return findRevision(capabilityCode, replay.get(0)).orElseThrow();
            if (locked.get(0) != expectedDraftRevision) throw new CapabilityAssetDraftConflictException("Asset draft revision is stale");
            CapabilityAssetDraft draft = findDraft(capabilityCode).orElseThrow();
            ensureDefinition(draft.capabilityCode(), draft.type(), actorId, Instant.now(), reason);
            String previousHash = jdbc.query("SELECT asset_hash FROM capability_asset_revision WHERE capability_code=? "
                            + "ORDER BY reviewed_at DESC,capability_revision_id DESC LIMIT 1",
                    (rs, n) -> rs.getString(1), capabilityCode).stream().findFirst().orElse(null);
            int nextRevision = jdbc.query("SELECT revision FROM capability_revision WHERE capability_code=?",
                            (rs, n) -> rs.getString(1), capabilityCode).stream()
                    .mapToInt(value -> {
                        try { return Integer.parseInt(value); }
                        catch (NumberFormatException error) { throw new IllegalStateException("Asset revision is not numeric", error); }
                    }).max().orElse(0) + 1;
            String revision = Integer.toString(nextRevision);
            Instant now = Instant.now();
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO capability_revision(capability_code,revision,"
                        + "display_name,description,tool_name,implementation_key,business_action,input_schema_json,content_hash,requires_confirmation) "
                        + "VALUES(?,?,?,?,NULL,NULL,NULL,'{}',?,FALSE)", new String[]{"capability_revision_id"});
                statement.setString(1, capabilityCode);
                statement.setString(2, revision);
                statement.setString(3, draft.displayName());
                statement.setString(4, draft.description());
                statement.setString(5, draft.assetHash());
                return statement;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return capability revision id");
            long revisionId = generated.longValue();
            jdbc.update("INSERT INTO capability_asset_revision(capability_revision_id,capability_code,revision,capability_type,manifest_json,"
                            + "asset_hash,draft_revision,publish_request_id,reviewed_by,reviewed_at,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    revisionId, capabilityCode, revision, draft.type().name(), draft.manifestJson(), draft.assetHash(),
                    draft.draftRevision(), requestId, actorId, Timestamp.from(now), reason);
            for (CapabilityAssetFile file : draft.files()) {
                jdbc.update("INSERT INTO capability_asset_file(capability_revision_id,relative_path,media_type,content,sha256,byte_size) "
                                + "VALUES(?,?,?,?,?,?)", revisionId, file.relativePath(), file.mediaType(), file.content(),
                        file.sha256(), file.byteSize());
            }
            jdbc.update("UPDATE capability_definition SET status='ACTIVE',updated_by=?,updated_at=?,status_reason=? WHERE capability_code=?",
                    actorId, Timestamp.from(now), reason, capabilityCode);
            appendAudit(capabilityCode, "PUBLISHED", actorId, requestId, reason, previousHash, draft.assetHash(), now);
            return new CapabilityAssetRevision(revisionId, capabilityCode, draft.type(), revision, draft.displayName(),
                    draft.description(), draft.files(), draft.manifestJson(), draft.assetHash(), requestId, actorId, now);
        });
    }

    private void ensureDefinition(String code, CapabilityBinding.CapabilityType type, long actorId, Instant now, String reason) {
        jdbc.update("INSERT INTO capability_definition(capability_code,capability_type,status,updated_by,updated_at,status_reason) "
                        + "VALUES(?,?,'DISABLED',?,?,?) ON DUPLICATE KEY UPDATE capability_code=VALUES(capability_code)",
                code, type.name(), actorId, Timestamp.from(now), reason);
        List<String> types = jdbc.query("SELECT capability_type FROM capability_definition WHERE capability_code=? FOR UPDATE",
                (rs, n) -> rs.getString(1), code);
        if (types.isEmpty() || !type.name().equals(types.get(0)))
            throw new IllegalArgumentException("Capability code is already registered with another type");
    }

    private List<CapabilityAssetDraft> readDraftRows(String code, boolean lock) {
        String sql = "SELECT capability_code,capability_type,draft_revision,display_name,description,files_json,manifest_json,asset_hash,updated_by,updated_at "
                + "FROM capability_asset_draft WHERE capability_code=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> new CapabilityAssetDraft(rs.getString(1),
                CapabilityBinding.CapabilityType.valueOf(rs.getString(2)), rs.getInt(3), rs.getString(4), rs.getString(5),
                readFiles(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getTimestamp(10).toInstant()), code);
    }

    private List<CapabilityAssetFile> readRevisionFiles(long revisionId) {
        return jdbc.query("SELECT relative_path,media_type,content,sha256,byte_size FROM capability_asset_file "
                        + "WHERE capability_revision_id=? ORDER BY relative_path",
                (rs, n) -> new CapabilityAssetFile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)), revisionId);
    }

    private CapabilityAssetRevision toRevision(RevisionHead head) {
        return new CapabilityAssetRevision(head.id(), head.code(), head.type(), head.revision(),
                head.displayName(), head.description(), readRevisionFiles(head.id()), head.manifestJson(), head.assetHash(),
                head.requestId(), head.reviewedBy(), head.reviewedAt());
    }

    private List<CapabilityAssetFile> readFiles(String value) {
        try { return json.readValue(value, FILES); }
        catch (Exception error) { throw new IllegalStateException("Invalid capability asset draft JSON", error); }
    }

    private String writeFiles(List<CapabilityAssetFile> files) {
        try { return json.writeValueAsString(files); }
        catch (Exception error) { throw new IllegalStateException("Unable to serialize capability asset files", error); }
    }

    private void appendAudit(String code, String action, long actorId, String requestId, String reason,
                             String previousHash, String newHash, Instant at) {
        jdbc.update("INSERT INTO capability_asset_audit(capability_code,action,actor_user_id,request_id,reason,previous_hash,new_hash,occurred_at) "
                        + "VALUES(?,?,?,?,?,?,?,?)", code, action, actorId, requestId, reason, previousHash, newHash, Timestamp.from(at));
    }

    private record DraftSummary(String code, CapabilityBinding.CapabilityType type, String displayName,
                                int draftRevision, Instant updatedAt) {}
    private record PublishedSummary(long id, String revision, String assetHash) {}
    private record RevisionHead(long id, String code, CapabilityBinding.CapabilityType type, String revision,
                                String displayName, String description, String manifestJson, String assetHash,
                                String requestId, long reviewedBy, Instant reviewedAt) {}
}
