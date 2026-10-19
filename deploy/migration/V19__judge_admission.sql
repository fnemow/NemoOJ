-- A transaction-scoped mutex shared by every producer of formal judge jobs.
CREATE TABLE judge_admission_lock (
    id TINYINT NOT NULL PRIMARY KEY
) COMMENT='全局正式提交准入事务锁';
INSERT INTO judge_admission_lock(id) VALUES (1);
CREATE INDEX idx_submit_admission ON question_submit(status,isDelete,createTime);
