-- Read-only reconciliation. Empty result means counters match all active submissions.
SELECT ids.questionId, COALESCE(s.submitNum,0) AS storedSubmissions,
       COALESCE(a.submitNum,0) AS actualSubmissions,
       COALESCE(s.acceptedNum,0) AS storedAccepted, COALESCE(a.acceptedNum,0) AS actualAccepted
FROM (SELECT questionId FROM question_submission_stats UNION SELECT questionId FROM question_submit WHERE questionId IS NOT NULL) ids
LEFT JOIN question_submission_stats s ON s.questionId=ids.questionId
LEFT JOIN (SELECT questionId, COUNT(*) AS submitNum,
    SUM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo,'$.message'))='accepted',0)) AS acceptedNum
    FROM question_submit WHERE isDelete=0 GROUP BY questionId) a ON a.questionId=ids.questionId
WHERE COALESCE(s.submitNum,0)<>COALESCE(a.submitNum,0)
   OR COALESCE(s.acceptedNum,0)<>COALESCE(a.acceptedNum,0);
