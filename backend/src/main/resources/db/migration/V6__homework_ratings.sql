ALTER TABLE homework_submission ADD COLUMN parent_rating VARCHAR(8);
UPDATE homework_submission SET parent_rating = CASE
    WHEN parent_score >= 90 THEN 'GOOD'
    WHEN parent_score >= 60 THEN 'AVERAGE'
    WHEN parent_score IS NOT NULL THEN 'POOR'
    ELSE NULL
END;
ALTER TABLE homework_submission ADD CONSTRAINT ck_homework_parent_rating
    CHECK (parent_rating IS NULL OR parent_rating IN ('GOOD', 'AVERAGE', 'POOR'));

ALTER TABLE homework_score_rule RENAME TO homework_rating_rule;
ALTER TABLE homework_rating_rule RENAME COLUMN excellent_item_id TO good_item_id;
ALTER TABLE homework_rating_rule RENAME COLUMN pass_item_id TO average_item_id;
ALTER TABLE homework_rating_rule RENAME COLUMN needs_work_item_id TO poor_item_id;
