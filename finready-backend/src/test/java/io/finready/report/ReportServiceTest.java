package io.finready.report;

import io.finready.audit.ActorRole;
import io.finready.audit.AuditEvent;
import io.finready.audit.AuditEventRepository;
import io.finready.audit.AuditEventType;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.common.StateMachine;
import io.finready.coverage.CoverageQueryService;
import io.finready.coverage.CoverageResponse;
import io.finready.coverage.GateStatus;
import io.finready.product.Product;
import io.finready.product.ProductRepository;
import io.finready.session.CloseEligibilityEvaluator;
import io.finready.session.ConsultationRevisionRepository;
import io.finready.session.ConsultationSession;
import io.finready.session.ConsultationSessionRepository;
import io.finready.session.SessionStatus;
import io.finready.understanding.FinalDisposition;
import io.finready.understanding.RiskUnderstandingState;
import io.finready.understanding.UnderstandingQueryService;
import io.finready.understanding.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F08 리포트 조립.
 *
 * <p>이 클래스가 지키는 것은 <b>"리포트가 스스로 계산하지 않는다"</b>는 것이다.
 * Coverage·Understanding·종료 조건이 각 모듈에서 온 값 그대로 실려야, 상담 중 화면과
 * 리포트가 다른 결론을 보이는 일이 생기지 않는다.
 */
class ReportServiceTest {

	private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

	private ConsultationSessionRepository sessionRepository;
	private ConsultationRevisionRepository revisionRepository;
	private ProductRepository productRepository;
	private AuditEventRepository auditEventRepository;
	private CoverageQueryService coverageQueryService;
	private UnderstandingQueryService understandingQueryService;
	private ReportService reportService;

	@BeforeEach
	void setUp() {
		sessionRepository = mock(ConsultationSessionRepository.class);
		revisionRepository = mock(ConsultationRevisionRepository.class);
		productRepository = mock(ProductRepository.class);
		auditEventRepository = mock(AuditEventRepository.class);
		coverageQueryService = mock(CoverageQueryService.class);
		understandingQueryService = mock(UnderstandingQueryService.class);
		reportService = new ReportService(sessionRepository, revisionRepository, productRepository,
				auditEventRepository, coverageQueryService, understandingQueryService,
				// 종료 조건은 대역이 아니라 진짜다 — 리포트와 종료가 같은 판정을 쓰는지가 요점이다
				new CloseEligibilityEvaluator(new StateMachine()));

		when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
		when(coverageQueryService.reportSectionsOf(any())).thenReturn(
				new CoverageQueryService.CoverageReportSections(Optional.empty(), List.of()));
	}

	private void stubCoverage(CoverageResponse coverage) {
		when(coverageQueryService.reportSectionsOf(any())).thenReturn(
				new CoverageQueryService.CoverageReportSections(Optional.of(coverage), List.of()));
	}

	private ConsultationSession session() {
		ConsultationSession session =
				new ConsultationSession(SESSION_ID, "PROD_A", "CUST_A", "A-2026-08-12-01");
		StateMachine sm = new StateMachine();
		session.transitionTo(SessionStatus.COVERAGE_ANALYZED, sm);
		session.transitionTo(SessionStatus.UNDERSTANDING_IN_PROGRESS, sm);
		session.transitionTo(SessionStatus.AWAITING_STAFF_REVIEW, sm);
		return session;
	}

	private CoverageResponse coverage(List<String> warningRiskIds) {
		return new CoverageResponse(SESSION_ID, 7L, SessionStatus.AWAITING_STAFF_REVIEW,
				GateStatus.READY_WITH_STAFF_OVERRIDE, true,
				List.of(), warningRiskIds, List.of(), null);
	}

	@Test
	@DisplayName("Coverage 섹션은 조회 서비스가 만든 값을 그대로 담는다")
	void coverageSectionMirrorsQueryService() {
		stubCoverage(coverage(List.of()));

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.coverage().finalRevisionId()).isEqualTo(7L);
		assertThat(report.coverage().gateStatus()).isEqualTo(GateStatus.READY_WITH_STAFF_OVERRIDE);
	}

	/** 계약이 coverage 를 required 로 두므로 분석 전이라도 null 을 내보내지 않는다 */
	@Test
	@DisplayName("분석 전이면 coverage 는 null 이 아니라 빈 섹션이다")
	void coverageSectionIsEmptyBeforeAnalysis() {
		// setUp() 의 기본 스텁이 이미 Optional.empty() 다 — 분석 전 상태를 그대로 재사용한다

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.coverage()).isNotNull();
		assertThat(report.coverage().results()).isEmpty();
		assertThat(report.coverage().finalRevisionId()).isNull();
	}

	/**
	 * 시드가 바뀌어도 이 상담의 판정 기준은 변하지 않는다. 상품의 현재 버전을 실으면
	 * 종료된 상담의 리포트가 나중에 다른 근거를 가리키게 된다.
	 */
	@Test
	@DisplayName("productRiskVersion 은 상품의 현재 값이 아니라 세션 snapshot 이다")
	void productRiskVersionComesFromSession() {
		when(productRepository.findById("PROD_A")).thenReturn(Optional.of(new Product(
				"PROD_A", "테스트 상품", "NO_KNOCK_IN_STEP_DOWN", "A-2099-12-31-99",
				"DOC_PROD_A_V1", "/documents/PROD_A/v1.0.pdf", 15,
				"5d355381abe028eb492f3c277236ee35a774150f4dbb24c289d2612ca8c5c47e", null, true)));

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.product().productRiskVersion()).isEqualTo("A-2026-08-12-01");
		assertThat(report.product().name()).isEqualTo("테스트 상품");
	}

	/** 판정 결과는 세션에 이미 저장돼 있다. 카탈로그가 비었다고 리포트를 못 보면 안 된다 */
	@Test
	@DisplayName("상품이 조회되지 않아도 리포트는 나온다")
	void survivesMissingProduct() {
		when(productRepository.findById("PROD_A")).thenReturn(Optional.empty());

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.product().id()).isEqualTo("PROD_A");
		assertThat(report.product().name()).isNull();
	}

	@Test
	@DisplayName("미해결 항목과 종료 조건이 서로 맞는다")
	void unresolvedDrivesCloseEligibility() {
		when(understandingQueryService.statesOf(any())).thenReturn(List.of(
				new RiskUnderstandingState("R01", "제목", List.of(), null,
						WorkflowStatus.COMPLETE, FinalDisposition.UNRESOLVED, null)));
		stubCoverage(coverage(List.of("R06")));

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.unresolvedRiskIds()).containsExactly("R01");
		assertThat(report.closeEligibility().canClose()).isTrue();
		assertThat(report.closeEligibility().requiresUnresolvedReason()).isTrue();
		assertThat(report.closeEligibility().expectedCloseStatus())
				.isEqualTo(SessionStatus.SESSION_CLOSED_WITH_UNRESOLVED);
		assertThat(report.closeEligibility().requiresWarningAcknowledgement())
				.containsExactly("R06");
	}

	/**
	 * 같은 세션 안에서 모델이 판정한 것과 직원이 정한 것이 구분되지 않으면
	 * "AI 원판정을 숨기지 않는다"는 원칙이 리포트에서 무너진다.
	 */
	@Test
	@DisplayName("감사 이벤트에 actorRole 이 그대로 실린다")
	void auditEventsKeepActorRole() {
		when(auditEventRepository.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID))
				.thenReturn(List.of(
						new AuditEvent(SESSION_ID, AuditEventType.COVERAGE_ANALYZED.name(),
								"claude", ActorRole.AI, "gateStatus=GATE_BLOCKED"),
						new AuditEvent(SESSION_ID, AuditEventType.GATE_OVERRIDE_APPLIED.name(),
								"staff-001", ActorRole.STAFF, "riskIds=[R01]")));

		ReportResponse report = reportService.getReport(SESSION_ID);

		assertThat(report.auditEvents())
				.extracting(ReportResponse.AuditEventView::actorRole)
				.containsExactly(ActorRole.AI, ActorRole.STAFF);
		assertThat(report.auditEvents().getFirst().eventType()).isEqualTo("COVERAGE_ANALYZED");
	}

	/** 법적 판정이 아니라는 표시다. 문구가 바뀌면 안 된다 (PRD §14) */
	@Test
	@DisplayName("disclaimer 를 항상 싣는다")
	void alwaysCarriesDisclaimer() {
		assertThat(reportService.getReport(SESSION_ID).disclaimer())
				.isEqualTo(ReportResponse.DISCLAIMER)
				.contains("법적 판정이 아니며");
	}

	@Test
	@DisplayName("없는 세션이면 SESSION_NOT_FOUND")
	void missingSession() {
		when(sessionRepository.findById(anyString())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> reportService.getReport("nope"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).code())
				.isEqualTo(ErrorCode.SESSION_NOT_FOUND);
	}
}
