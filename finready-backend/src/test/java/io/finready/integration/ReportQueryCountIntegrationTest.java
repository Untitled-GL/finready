package io.finready.integration;

import io.finready.report.ReportResponse;
import io.finready.report.ReportService;
import io.finready.session.SessionService;
import io.finready.session.SessionSnapshotResponse;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRD §14.1 "리전 교차" 회귀 방지 — {@code GET /sessions/{id}}·{@code GET /report}
 * 의 순차 쿼리 개수를 고정한다.
 *
 * <p>앱(Singapore)↔DB(Seoul) 왕복이 실측 70~90ms(§14.1)라, 이 두 엔드포인트처럼
 * 여러 컬렉션(Coverage·Understanding·Override·Revision·Audit)을 순차로 읽으면
 * 쿼리 수만큼 그대로 레이턴시에 쌓인다. <b>로컬에서는 왕복이 1ms 미만이라 안 보이고
 * 배포 후에만 드러나는 게 이 실패의 전형적인 모양이다</b>(TRD가 직접 경고한 지점) —
 * 그래서 쿼리 개수 자체를 어서션으로 고정해 회귀를 잡는다. TRD §14 는 요청당 쿼리
 * 15회 이하를 요구한다.
 */
@DisplayName("리포트·세션 조회 쿼리 개수 (Testcontainers)")
class ReportQueryCountIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final String SESSION_ID = "QCOUNT_SESSION";

	@Autowired
	private SessionService sessionService;

	@Autowired
	private ReportService reportService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private Statistics statistics;

	/**
	 * Coverage·Understanding·Override 섹션이 전부 최소 1행씩 있어야 실제 운영 경로의
	 * 쿼리가 전부 발생한다 — {@code risk_workflow_state} 가 비어 있으면
	 * {@code UnderstandingQueryService.statesOf} 가 조기 반환해(구현) 쿼리 4개가
	 * 숨어버려 이 테스트가 낙관적인 숫자를 고정하게 된다.
	 */
	@BeforeEach
	void seedFullSession() {
		jdbcTemplate.update("delete from gate_override where session_id = ?", SESSION_ID);
		jdbcTemplate.update("delete from risk_workflow_state where session_id = ?", SESSION_ID);
		jdbcTemplate.update("delete from coverage_result where session_id = ?", SESSION_ID);
		jdbcTemplate.update("delete from consultation_revision where session_id = ?", SESSION_ID);
		jdbcTemplate.update("delete from consultation_session where id = ?", SESSION_ID);
		jdbcTemplate.update("delete from product_risk where product_id = 'QCOUNT_PROD'");

		jdbcTemplate.update("""
				insert into product (id, name, archetype, product_risk_version, document_id, document_url,
				                      document_sha256)
				values ('QCOUNT_PROD', 'query count fixture', 'ELS', 'v1', 'QCOUNT_DOC',
				        '/documents/QCOUNT_PROD/v1.pdf', repeat('0', 64))
				on conflict (id) do nothing
				""");
		jdbcTemplate.update("""
				insert into customer_profile (id, label) values ('QCOUNT_CUST', 'query count fixture')
				on conflict (id) do nothing
				""");
		jdbcTemplate.update("""
				insert into product_risk (product_id, risk_id, category, title, fact, coverage_policy,
				                          understanding_check, source_page, source_text, fallback_question,
				                          fallback_recheck_question, fallback_plain_explanation,
				                          verified_at, verified_by)
				values ('QCOUNT_PROD', 'R01', 'PRINCIPAL_LOSS', '원금 손실', '원금 손실 위험 사실',
				        'GATE_REQUIRED', true, 1, '원금은 보장되지 않습니다.', '질문', '재질문', '쉬운 설명',
				        now(), 'tester')
				""");
		jdbcTemplate.update("""
				insert into consultation_session (id, product_id, customer_id, status, product_risk_version)
				values (?, 'QCOUNT_PROD', 'QCOUNT_CUST', 'AWAITING_STAFF_REVIEW', 'v1')
				""", SESSION_ID);
		jdbcTemplate.update("""
				insert into consultation_revision (session_id, revision_no, text, char_count)
				values (?, 1, '원금은 보장되지 않는다고 설명했습니다.', 20)
				""", SESSION_ID);
		Long revisionId = jdbcTemplate.queryForObject(
				"select id from consultation_revision where session_id = ? and revision_no = 1",
				Long.class, SESSION_ID);
		jdbcTemplate.update("""
				insert into coverage_result (session_id, revision_id, risk_id, classifier_status,
				                             coverage_status, provenance_valid)
				values (?, ?, 'R01', 'INSUFFICIENT', 'INSUFFICIENT', false)
				""", SESSION_ID, revisionId);
		jdbcTemplate.update("""
				insert into gate_override (session_id, risk_id, category, reason, actor)
				values (?, 'R01', 'OPERATIONAL_EXCEPTION', '심사용 예외 처리', 'tester')
				""", SESSION_ID);
		jdbcTemplate.update("""
				insert into risk_workflow_state (session_id, risk_id, workflow_status, final_disposition)
				values (?, 'R01', 'COMPLETE', 'SKIPPED_BY_OVERRIDE')
				""", SESSION_ID);

		statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
	}

	@Test
	@DisplayName("GET /sessions/{id} 는 세션당 9회로 조회한다 (TRD §14 상한 15회, revision 중복 제거 후 실측치)")
	void sessionSnapshotStaysUnderQueryBudget() {
		statistics.clear();

		SessionSnapshotResponse snapshot = sessionService.getSnapshot(SESSION_ID);

		assertThat(snapshot.coverage()).isNotNull();
		assertThat(snapshot.understanding()).isNotEmpty();
		// 이 값이 늘어나면 어딘가에서 다시 중복 쿼리가 생겼다는 뜻이다
		assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(9);
	}

	@Test
	@DisplayName("GET /report 는 세션당 11회로 조회한다 (TRD §14 상한 15회, gate_override 중복 제거 후 실측치)")
	void reportStaysUnderQueryBudget() {
		statistics.clear();

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.coverage()).isNotNull();
		assertThat(report.overrides()).isNotEmpty();
		// 중복 쿼리 제거 후 실측치 11 — 늘어나면 회귀다(TRD §14 상한 15와는 별개로 고정)
		assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(11);
	}
}
