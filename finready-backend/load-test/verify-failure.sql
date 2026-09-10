-- Run immediately after a single failure scenario, with no other test in progress.
DO $$
DECLARE
  target_session varchar;
  result_count integer;
  log_count integer;
BEGIN
  SELECT id INTO target_session FROM finready.consultation_session ORDER BY created_at DESC LIMIT 1;
  IF target_session IS NULL THEN RAISE EXCEPTION 'No test session exists'; END IF;
  SELECT count(*) INTO result_count FROM finready.coverage_result WHERE session_id = target_session;
  SELECT count(*) INTO log_count FROM finready.llm_call_log WHERE session_id = target_session;
  IF result_count <> 0 THEN RAISE EXCEPTION 'Partial coverage results persisted: %', result_count; END IF;
  IF log_count <> 4 THEN RAISE EXCEPTION 'Expected 4 gateway attempt logs, found %', log_count; END IF;
  RAISE NOTICE 'PASS session=%: zero coverage rows, four gateway attempt logs', target_session;
END $$;
