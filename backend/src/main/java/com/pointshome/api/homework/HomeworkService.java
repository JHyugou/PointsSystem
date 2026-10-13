package com.pointshome.api.homework;

import com.pointshome.api.ai.AiKeyCipher;
import com.pointshome.api.ai.AiSettingStore;
import com.pointshome.api.auth.Account;
import com.pointshome.api.auth.AccountStore;
import com.pointshome.api.auth.CurrentUser;
import com.pointshome.api.common.ApiProblem;
import com.pointshome.api.points.PointService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class HomeworkService {
    public record Summary(String id, String childId, String subject, String status,
                          String parentRating, int photoCount,
                          long createdAt, Long analyzedAt, Long reviewedAt) {}
    public record ChatMessage(String role, String content, long createdAt) {}
    public record Rule(String goodItemId, String averageItemId, String poorItemId) {}
    public record Detail(String id, String childId, String subject, String instructions,
                         String status, String modelId, HomeworkAiGateway.Review analysis,
                         String aiError, String parentRating, String parentNote,
                         String pointItemId, String pointActionId, int photoCount,
                         long createdAt, Long analyzedAt, Long reviewedAt,
                         List<ChatMessage> chat) {}

    private record Submission(String id, String childId, String subject, String instructions,
                              String status, String chatToken, Long chatStartedAt,
                              String modelId, String aiResult, String aiError,
                              String parentRating, String parentNote, String pointItemId,
                              String pointActionId, int photoCount, long createdAt,
                              Long analyzedAt, Long reviewedAt) {}

    private static final RowMapper<Submission> SUBMISSION = (rs, row) -> new Submission(
            rs.getString("id"), rs.getString("child_id"), rs.getString("subject"),
            rs.getString("instructions"), rs.getString("status"), rs.getString("chat_token"),
            rs.getObject("chat_started_at", Long.class),
            rs.getString("model_id"),
            rs.getString("ai_result"), rs.getString("ai_error"),
            rs.getString("parent_rating"), rs.getString("parent_note"),
            rs.getString("point_item_id"), rs.getString("point_action_id"), rs.getInt("photo_count"),
            rs.getLong("created_at"), rs.getObject("analyzed_at", Long.class),
            rs.getObject("reviewed_at", Long.class));

    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final AiSettingStore settings;
    private final AiKeyCipher cipher;
    private final HomeworkImageStore images;
    private final HomeworkAiGateway ai;
    private final PointService points;
    private final ObjectMapper json;

    public HomeworkService(JdbcTemplate jdbc, AccountStore accounts, AiSettingStore settings,
                           AiKeyCipher cipher, HomeworkImageStore images,
                           HomeworkAiGateway ai, PointService points, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.settings = settings;
        this.cipher = cipher;
        this.images = images;
        this.ai = ai;
        this.points = points;
        this.json = json;
    }

    @Transactional
    public Detail submit(CurrentUser user, String childId, String subject, String instructions,
                         List<MultipartFile> photos) {
        requireGuardian(user, childId);
        String cleanSubject = subject == null ? "" : subject.strip();
        String cleanInstructions = instructions == null ? "" : instructions.strip();
        if (cleanSubject.isBlank() || cleanSubject.length() > 40 || cleanInstructions.length() > 500) {
            throw badInput();
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM homework_submission WHERE child_id = ?", Integer.class, childId);
        if (count != null && count >= 40) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_STORAGE_LIMIT",
                    "已保存 40 份作业，请先删除不再需要的旧记录");
        }
        Long storedBytes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(p.byte_size), 0) FROM homework_photo p
                JOIN homework_submission s ON s.id = p.submission_id WHERE s.child_id = ?
                """, Long.class, childId);
        long incomingBytes = photos == null ? 0 : photos.stream().mapToLong(MultipartFile::getSize).sum();
        if ((storedBytes == null ? 0 : storedBytes) + incomingBytes > 100_000_000L) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_STORAGE_LIMIT",
                    "作业照片已占用较多空间，请先删除不再需要的旧记录");
        }
        long now = System.currentTimeMillis();
        Integer today = jdbc.queryForObject("""
                SELECT COUNT(*) FROM homework_submission WHERE child_id = ? AND created_at >= ?
                """, Integer.class, childId, now - 86_400_000L);
        if (today != null && today >= 8) {
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "HOMEWORK_DAILY_LIMIT",
                    "每个孩子 24 小时内最多提交 8 份作业");
        }
        String id = UUID.randomUUID().toString();
        List<HomeworkImageStore.Photo> savedPhotos = images.save(id, photos);
        try {
            jdbc.update("""
                    INSERT INTO homework_submission
                    (id, child_id, submitted_by, subject, instructions, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?)
                    """, id, childId, user.id(), cleanSubject, cleanInstructions, now);
            for (int ordinal = 0; ordinal < savedPhotos.size(); ordinal++) {
                HomeworkImageStore.Photo photo = savedPhotos.get(ordinal);
                jdbc.update("""
                        INSERT INTO homework_photo (submission_id, ordinal, media_type, byte_size)
                        VALUES (?, ?, ?, ?)
                        """, id, ordinal, photo.mediaType(), photo.byteSize());
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) images.delete(id);
                }
            });
        } catch (RuntimeException error) {
            images.delete(id);
            throw error;
        }
        return detail(user, id);
    }

    public List<Summary> list(CurrentUser user, String childId) {
        requireGuardian(user, childId);
        return jdbc.query("""
                SELECT s.id, s.child_id, s.subject, s.status, s.parent_rating,
                    (SELECT COUNT(*) FROM homework_photo p WHERE p.submission_id = s.id) AS photo_count,
                    s.created_at, s.analyzed_at, s.reviewed_at
                FROM homework_submission s WHERE s.child_id = ?
                ORDER BY s.created_at DESC, s.id DESC LIMIT 40
                """, (rs, row) -> new Summary(rs.getString("id"), rs.getString("child_id"),
                rs.getString("subject"), rs.getString("status"),
                rs.getString("parent_rating"),
                rs.getInt("photo_count"), rs.getLong("created_at"),
                rs.getObject("analyzed_at", Long.class), rs.getObject("reviewed_at", Long.class)), childId);
    }

    public Detail detail(CurrentUser user, String submissionId) {
        Submission submission = submission(user, submissionId, false);
        HomeworkAiGateway.Review analysis = null;
        if (submission.aiResult() != null) {
            analysis = json.readValue(submission.aiResult(), HomeworkAiGateway.Review.class);
        }
        return new Detail(submission.id(), submission.childId(), submission.subject(),
                submission.instructions(), submission.status(), submission.modelId(), analysis,
                submission.aiError(), submission.parentRating(), submission.parentNote(),
                submission.pointItemId(), submission.pointActionId(), submission.photoCount(),
                submission.createdAt(), submission.analyzedAt(), submission.reviewedAt(),
                chat(submission.id()));
    }

    public HomeworkImageStore.Image photo(CurrentUser user, String submissionId, int ordinal) {
        Submission submission = submission(user, submissionId, false);
        String mediaType = jdbc.query("""
                SELECT media_type FROM homework_photo WHERE submission_id = ? AND ordinal = ?
                """, (rs, row) -> rs.getString(1), submission.id(), ordinal)
                .stream().findFirst().orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                        "HOMEWORK_IMAGE_MISSING", "作业照片不存在"));
        return images.read(submission.id(), ordinal, mediaType);
    }

    public Detail analyze(CurrentUser user, String submissionId) {
        Submission submission = submission(user, submissionId, false);
        AiSettingStore.Setting setting = setting(user.id());
        long now = System.currentTimeMillis();
        String attempt = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE homework_submission SET status = 'ANALYZING', analyzing_at = ?,
                    analysis_token = ?, ai_error = NULL
                WHERE id = ? AND (status IN ('SUBMITTED', 'FAILED', 'ANALYZED')
                    OR (status = 'ANALYZING' AND analyzing_at < ?))
                    AND (chat_token IS NULL OR chat_started_at < ?)
                """, now, attempt, submission.id(), now - 300_000L, now - 300_000L);
        if (claimed == 0) throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_BUSY_OR_REVIEWED",
                "作业正在分析，或家长已确认结果");
        try {
            var result = ai.review(cipher.decrypt(setting.encryptedApiKey()), setting.modelId(),
                    submission.subject(), submission.instructions(), photos(submission));
            int updated = jdbc.update("""
                    UPDATE homework_submission SET status = 'ANALYZED', ai_result = ?,
                        model_id = ?, analyzed_at = ?, analysis_token = NULL, analyzing_at = NULL,
                        ai_error = NULL
                    WHERE id = ? AND analysis_token = ?
                    """, json.writeValueAsString(result), setting.modelId(),
                    System.currentTimeMillis(), submission.id(), attempt);
            if (updated == 0) throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_ANALYSIS_REPLACED",
                    "这份作业已有更新的分析，请刷新查看");
            jdbc.update("DELETE FROM homework_chat WHERE submission_id = ?", submission.id());
            return detail(user, submission.id());
        } catch (RuntimeException error) {
            jdbc.update("""
                    UPDATE homework_submission SET status = 'FAILED', analysis_token = NULL,
                        analyzing_at = NULL, ai_error = ? WHERE id = ? AND analysis_token = ?
                    """, error instanceof ApiProblem ? error.getMessage() : "分析失败，请稍后重试",
                    submission.id(), attempt);
            throw error;
        }
    }

    public Detail followUp(CurrentUser user, String submissionId, String question) {
        Submission submission = submission(user, submissionId, false);
        String cleanQuestion = question == null ? "" : question.strip();
        if (cleanQuestion.isBlank() || cleanQuestion.length() > 500) throw badInput();
        if (!List.of("ANALYZED", "REVIEWED").contains(submission.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_NOT_ANALYZED", "请先完成 AI 分析");
        }
        Integer used = jdbc.queryForObject("""
                SELECT COUNT(*) FROM homework_chat WHERE submission_id = ? AND role = 'USER'
                """, Integer.class, submission.id());
        if (used != null && used >= 5) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_CHAT_LIMIT", "每份作业最多追问 5 次");
        }
        AiSettingStore.Setting setting = setting(user.id());
        String attempt = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        int claimed = jdbc.update("""
                UPDATE homework_submission SET chat_token = ?, chat_started_at = ?
                WHERE id = ? AND status IN ('ANALYZED', 'REVIEWED')
                    AND analysis_token IS NULL
                    AND (chat_token IS NULL OR chat_started_at < ?)
                """, attempt, now, submission.id(), now - 300_000L);
        if (claimed == 0) throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_CHAT_BUSY", "上一条追问尚未完成");
        try {
            List<ChatMessage> existing = chat(submission.id());
            if (existing.stream().filter(turn -> "USER".equals(turn.role())).count() >= 5) {
                throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_CHAT_LIMIT", "每份作业最多追问 5 次");
            }
            HomeworkAiGateway.Review review = json.readValue(submission.aiResult(),
                    HomeworkAiGateway.Review.class);
            List<HomeworkAiGateway.Turn> history = existing.stream()
                    .map(turn -> new HomeworkAiGateway.Turn(turn.role(), turn.content())).toList();
            String answer = ai.followUp(cipher.decrypt(setting.encryptedApiKey()), setting.modelId(),
                    submission.subject(), review, photos(submission), history, cleanQuestion);
            long savedAt = System.currentTimeMillis();
            jdbc.update("""
                    INSERT INTO homework_chat (id, submission_id, role, content, created_at)
                    VALUES (?, ?, 'USER', ?, ?)
                    """, UUID.randomUUID().toString(), submission.id(), cleanQuestion, savedAt);
            jdbc.update("""
                    INSERT INTO homework_chat (id, submission_id, role, content, created_at)
                    VALUES (?, ?, 'ASSISTANT', ?, ?)
                    """, UUID.randomUUID().toString(), submission.id(), answer, savedAt + 1);
            return detail(user, submission.id());
        } finally {
            jdbc.update("""
                    UPDATE homework_submission SET chat_token = NULL, chat_started_at = NULL
                    WHERE id = ? AND chat_token = ?
                    """, submission.id(), attempt);
        }
    }

    @Transactional
    public Detail review(CurrentUser user, String submissionId, String rating, String note,
                         String pointItemId) {
        Submission submission = submission(user, submissionId, true);
        if (rating == null || !List.of("GOOD", "AVERAGE", "POOR").contains(rating)
                || (note != null && note.length() > 500)) throw badInput();
        if ("REVIEWED".equals(submission.status())) return detail(user, submission.id());
        if (!"ANALYZED".equals(submission.status())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_NOT_ANALYZED", "请先完成 AI 分析");
        }
        String cleanNote = note == null ? "" : note.strip();
        String actionId = null;
        if (pointItemId != null && !pointItemId.isBlank()) {
            validateItem(submission.childId(), pointItemId);
            PointService.Action action = points.grant(user, pointItemId,
                    "作业检查：" + submission.subject() + "，家长评价“" + ratingName(rating) + "”",
                    submission.id());
            actionId = action.id();
        }
        jdbc.update("""
                UPDATE homework_submission SET status = 'REVIEWED', parent_rating = ?, parent_note = ?,
                    reviewed_by = ?, reviewed_at = ?, point_item_id = ?, point_action_id = ?
                WHERE id = ?
                """, rating, cleanNote, user.id(), System.currentTimeMillis(),
                pointItemId, actionId, submission.id());
        return detail(user, submission.id());
    }

    public Rule rule(CurrentUser user, String childId) {
        requireGuardian(user, childId);
        return jdbc.query("""
                SELECT good_item_id, average_item_id, poor_item_id
                FROM homework_rating_rule WHERE child_id = ?
                """, (rs, row) -> new Rule(rs.getString(1), rs.getString(2), rs.getString(3)),
                childId).stream().findFirst().orElse(new Rule(null, null, null));
    }

    @Transactional
    public Rule saveRule(CurrentUser user, String childId, Rule rule) {
        requireGuardian(user, childId);
        if (rule == null) throw badInput();
        validateItem(childId, rule.goodItemId());
        validateItem(childId, rule.averageItemId());
        validateItem(childId, rule.poorItemId());
        long now = System.currentTimeMillis();
        int changed = jdbc.update("""
                UPDATE homework_rating_rule SET good_item_id = ?, average_item_id = ?,
                    poor_item_id = ?, updated_by = ?, updated_at = ? WHERE child_id = ?
                """, rule.goodItemId(), rule.averageItemId(), rule.poorItemId(),
                user.id(), now, childId);
        if (changed == 0) jdbc.update("""
                INSERT INTO homework_rating_rule (child_id, good_item_id, average_item_id,
                    poor_item_id, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                """, childId, rule.goodItemId(), rule.averageItemId(),
                rule.poorItemId(), user.id(), now);
        return rule(user, childId);
    }

    @Transactional
    public void delete(CurrentUser user, String submissionId) {
        Submission submission = submission(user, submissionId, true);
        boolean chatActive = submission.chatToken() != null && submission.chatStartedAt() != null
                && submission.chatStartedAt() >= System.currentTimeMillis() - 300_000L;
        if ("ANALYZING".equals(submission.status()) || chatActive) {
            throw new ApiProblem(HttpStatus.CONFLICT, "HOMEWORK_BUSY", "分析或追问期间无法删除作业");
        }
        jdbc.update("DELETE FROM homework_chat WHERE submission_id = ?", submission.id());
        jdbc.update("DELETE FROM homework_photo WHERE submission_id = ?", submission.id());
        jdbc.update("DELETE FROM homework_submission WHERE id = ?", submission.id());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { images.delete(submission.id()); }
        });
    }

    private List<HomeworkImageStore.Image> photos(Submission submission) {
        List<HomeworkImageStore.Image> result = new ArrayList<>();
        List<String> mediaTypes = jdbc.query("""
                SELECT media_type FROM homework_photo WHERE submission_id = ? ORDER BY ordinal
                """, (rs, row) -> rs.getString(1), submission.id());
        for (int index = 0; index < mediaTypes.size(); index++) {
            result.add(images.read(submission.id(), index, mediaTypes.get(index)));
        }
        return result;
    }

    private List<ChatMessage> chat(String submissionId) {
        return jdbc.query("""
                SELECT role, content, created_at FROM homework_chat
                WHERE submission_id = ? ORDER BY created_at, id
                """, (rs, row) -> new ChatMessage(rs.getString(1), rs.getString(2), rs.getLong(3)),
                submissionId);
    }

    private Submission submission(CurrentUser user, String id, boolean lock) {
        List<Submission> rows = jdbc.query("""
                SELECT s.*, (SELECT COUNT(*) FROM homework_photo p WHERE p.submission_id = s.id) AS photo_count
                FROM homework_submission s WHERE s.id = ?
                """ + (lock ? " FOR UPDATE" : ""), SUBMISSION, id);
        Submission submission = rows.stream().findFirst().orElseThrow(() -> notFound());
        requireGuardian(user, submission.childId());
        return submission;
    }

    private AiSettingStore.Setting setting(String guardianId) {
        return settings.find(guardianId).orElseThrow(() -> new ApiProblem(HttpStatus.BAD_REQUEST,
                "HOMEWORK_AI_NOT_CONFIGURED", "请先到 AI 设置保存 DeepSeek 密钥和图片模型"));
    }

    private void validateItem(String childId, String itemId) {
        if (itemId == null) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_item WHERE id = ? AND child_id = ?
                """, Integer.class, itemId, childId);
        if (count == null || count == 0) throw badInput();
    }

    private void requireGuardian(CurrentUser user, String childId) {
        if (user.role() != Account.Role.GUARDIAN || !accounts.isLinked(user.id(), childId)) throw notFound();
    }

    private ApiProblem badInput() {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "HOMEWORK_INVALID_INPUT", "请检查作业信息");
    }

    private String ratingName(String rating) {
        return switch (rating) {
            case "GOOD" -> "好";
            case "AVERAGE" -> "一般";
            case "POOR" -> "差";
            default -> throw badInput();
        };
    }

    private ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "HOMEWORK_NOT_FOUND", "作业记录不存在");
    }
}
