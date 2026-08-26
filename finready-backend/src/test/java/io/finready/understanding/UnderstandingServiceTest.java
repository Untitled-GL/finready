package io.finready.understanding;

import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.common.GenerationSource;
import io.finready.common.StateMachine;
import io.finready.coverage.GateOverride;
import io.finready.coverage.GateOverrideRepository;
import io.finready.coverage.OverrideCategory;
import io.finready.product.CoveragePolicy;
import io.finready.product.CustomerProfileRepository;
import io.finready.product.ProductRisk;
import io.finready.product.ProductRiskRepository;
import io.finready.session.ConsultationSession;
import io.finready.session.ConsultationSessionRepository;
import io.finready.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F04/F05 오케스트레이션. LLM 없이 돈다 — 두 포트가 목이다.
 *
 * <p>nextAction 표 자체는 {@code NextActionResolverTest}, 전이표는
 * {@code WorkflowStateMachineTest} 가 갖는다. 여기서는 <b>순서·멱등·상한</b>만 본다.
 */
@DisplayName("UnderstandingService — 질문 발급과 답변 판정")
class UnderstandingServiceTest {

	private static final String SESSION_ID = "S-1";

	private final ConsultationSessionRepository sessionRepository = mock(ConsultationSessionRepository.class);
	private final ProductRiskRepository productRiskRepository = mock(ProductRiskRepository.class);
	private final CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
	private final GateOverrideRepository gateOverrideRepository = mock(GateOverrideRepository.class);
	private final SessionQuestionRepository questionRepository = mock(SessionQuestionRepository.class);
	private final UnderstandingResultRepository resultRepository = mock(UnderstandingResultRepository.class);
	private final RiskWorkflowStateRepository workflowStateRepository = mock(RiskWorkflowStateRepository.class);
	private final StaffResolutionRepository staffResolutionRepository = mock(StaffResolutionRepository.class);
	private final QuestionGenerator questionGenerator = mock(QuestionGenerator.class);
	private final AnswerJudge answerJudge = mock(AnswerJudge.class);
	private final UnderstandingWriter writer = mock(UnderstandingWriter.class);
	private final UnderstandingQueryService understandingQueryService = mock(UnderstandingQueryService.class);

	private final WorkflowStateMachine workflowStateMachine = new WorkflowStateMachine();

	private UnderstandingService service;

	@BeforeEach
	void setUp() {
		service = new UnderstandingService(
				sessionRepository, productRiskRepository, customerProfileRepository,
				gateOverrideRepository, questionRepository, resultRepository, workflowStateRepository,
				staffResolutionRepository, questionGenerator, answerJudge, new NextActionResolver(),
				workflowStateMachine, writer, new StateMachine(), understandingQueryService);

		when(sessionRepository.findById(SESSION_ID))
				.thenReturn(Optional.of(session(SessionStatus.COVERAGE_ANALYZED)));
		when(productRiskRepository.findByProductIdOrderByRiskIdAsc("PROD_A")).thenReturn(risks());
		when(gateOverrideRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID)).thenReturn(List.of());
		when(questionRepository.findBySessionIdOrderByRiskIdAscAttemptAsc(SESSION_ID)).thenReturn(List.of());
		when(workflowStateRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID)).thenReturn(List.of());
		when(customerProfileRepository.findById(anyString())).thenReturn(Optional.empty());
		when(questionGenerator.phrase(anyString(), anyList())).thenReturn(List.of());
		when(writer.saveAnswer(anyString(), any(), any(), any(), anyBooleanArg(), any()))
				.thenReturn(SessionStatus.UNDERSTANDING_IN_PROGRESS);
		when(writer.saveStaffResolution(anyString(), any(), any(), anyBooleanArg(), any()))
				.thenReturn(SessionStatus.AWAITING_STAFF_REVIEW);
		// F07 테스트 대부분은 riskState 의 내용까지는 안 본다 — 필요한 테스트만 개별 재정의한다
		when(understandingQueryService.statesOf(any())).thenReturn(List.of(
				riskState("R01", WorkflowStatus.COMPLETE, FinalDisposition.RESOLVED_BY_STAFF, null)));
	}

	/**
	 * {@code resolveByStaff} 가 응답 조립에 쓰는 {@link UnderstandingQueryService} 는 mock이다 —
	 * 그 조립 로직 자체({@code RiskUnderstandingState} 를 어떻게 만드는지)는
	 * {@code UnderstandingQueryServiceTest} 가 별도로 검증한다. 여기서는 이 서비스가
	 * 받은 값을 그대로 응답에 싣는지만 본다.
	 */
	private RiskUnderstandingState riskState(String riskId, WorkflowStatus workflowStatus,
	                                         FinalDisposition finalDisposition,
	                                         UnderstandingStatus lastAiStatus) {
		List<RiskUnderstandingState.AttemptView> attempts = lastAiStatus == null
				? List.of()
				: List.of(new RiskUnderstandingState.AttemptView(
						1, "q", null, "a", null, lastAiStatus, "reason"));
		return new RiskUnderstandingState(riskId, "제목", attempts, null,
				workflowStatus, finalDisposition, null);
	}

	private static boolean anyBooleanArg() {
		return org.mockito.ArgumentMatchers.anyBoolean();
	}

	@Nested
	@DisplayName("F04 질문 발급")
	class Questions {

		@Test
		@DisplayName("understandingCheck=true 인 Risk 만 대상이다")
		void onlyUnderstandingCheckRisks() {
			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.totalRiskCount()).isEqualTo(3);
			assertThat(response.questions())
					.extracting(QuestionsResponse.QuestionView::riskId)
					.containsExactly("R01", "R02", "R03");
		}

		@Test
		@DisplayName("orderIndex 는 1부터 매겨진다 — UI progress 분자")
		void orderIndexStartsAtOne() {
			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.questions())
					.extracting(QuestionsResponse.QuestionView::orderIndex)
					.containsExactly(1, 2, 3);
		}

		@Test
		@DisplayName("생성기가 비면 검수 문항을 그대로 쓰고 FALLBACK 으로 표시한다")
		void fallsBackToVettedQuestion() {
			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.questions())
					.allSatisfy(view -> assertThat(view.source()).isEqualTo(GenerationSource.FALLBACK));
			assertThat(response.questions().getFirst().question()).isEqualTo("R01 질문");
		}

		@Test
		@DisplayName("생성기가 다듬은 Risk 는 LLM 으로 표시된다")
		void usesPhrasedQuestionWhenAvailable() {
			when(questionGenerator.phrase(anyString(), anyList())).thenReturn(List.of(
					new QuestionGenerator.PhrasedQuestion("R01", "쉽게 바꾼 질문")));

			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			QuestionsResponse.QuestionView r01 = view(response, "R01");
			assertThat(r01.question()).isEqualTo("쉽게 바꾼 질문");
			assertThat(r01.source()).isEqualTo(GenerationSource.LLM);
			// 나머지는 검수 문항으로 메운다 — 부분 성공을 허용한다
			assertThat(view(response, "R02").source()).isEqualTo(GenerationSource.FALLBACK);
		}

		@Test
		@DisplayName("생성기가 터져도 흐름을 막지 않는다 — 검수 문항이 있다")
		void generatorFailureDoesNotBlock() {
			when(questionGenerator.phrase(anyString(), anyList()))
					.thenThrow(new IllegalStateException("LLM 미설정"));

			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.questions()).hasSize(3);
		}

		@Test
		@DisplayName("이미 발급된 질문이 있으면 다시 만들지 않는다 (멱등)")
		void reusesIssuedQuestions() {
			when(questionRepository.findBySessionIdOrderByRiskIdAscAttemptAsc(SESSION_ID))
					.thenReturn(List.of(
							question("R01", (short) 1, "이미 나간 R01 질문"),
							question("R02", (short) 1, "이미 나간 R02 질문"),
							question("R03", (short) 1, "이미 나간 R03 질문")));

			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			verify(questionGenerator, never()).phrase(anyString(), anyList());
			verify(writer, never()).saveIssuedQuestions(anyString(), anyList(), anyList(), any());
			assertThat(view(response, "R01").question()).isEqualTo("이미 나간 R01 질문");
		}

		@Test
		@DisplayName("Gate 가 안 열렸으면 GATE_NOT_OPEN")
		void gateBlockedRejected() {
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.GATE_BLOCKED)));

			assertThat(errorCodeOf(() -> service.getOrCreateQuestions(SESSION_ID)))
					.isEqualTo(ErrorCode.GATE_NOT_OPEN);
		}

		@Test
		@DisplayName("DRAFT 도 마찬가지다 — 분석 전에는 질문을 발급하지 않는다")
		void draftRejected() {
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.DRAFT)));

			assertThat(errorCodeOf(() -> service.getOrCreateQuestions(SESSION_ID)))
					.isEqualTo(ErrorCode.GATE_NOT_OPEN);
		}

		@Test
		@DisplayName("staffExplanationConfirmed=false 로 override 된 Risk 는 질문에서 빠진다")
		void overrideSkippedRiskExcluded() {
			when(gateOverrideRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(override("R02", false)));

			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.questions())
					.extracting(QuestionsResponse.QuestionView::riskId)
					.containsExactly("R01", "R03");
			assertThat(response.totalRiskCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("staffExplanationConfirmed=true 면 질문 대상에 남는다 — 설명했다는 것과 이해했다는 건 다르다")
		void confirmedOverrideStaysInScope() {
			when(gateOverrideRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(override("R02", true)));

			QuestionsResponse response = service.getOrCreateQuestions(SESSION_ID);

			assertThat(response.questions())
					.extracting(QuestionsResponse.QuestionView::riskId)
					.contains("R02");
		}

		@Test
		@DisplayName("제외된 Risk 도 SKIPPED_BY_OVERRIDE 로 기록된다 — 왜 안 물었는지 리포트에 남아야 한다")
		void skippedRiskIsRecorded() {
			when(gateOverrideRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(override("R02", false)));

			service.getOrCreateQuestions(SESSION_ID);

			ArgumentCaptor<List<RiskWorkflowState>> captor = captor();
			verify(writer).saveIssuedQuestions(anyString(), anyList(), captor.capture(), any());

			assertThat(captor.getValue())
					.filteredOn(state -> state.getRiskId().equals("R02"))
					.singleElement()
					.satisfies(state -> {
						assertThat(state.getWorkflowStatus()).isEqualTo(WorkflowStatus.COMPLETE);
						assertThat(state.getFinalDisposition())
								.isEqualTo(FinalDisposition.SKIPPED_BY_OVERRIDE);
					});
		}
	}

	@Nested
	@DisplayName("F05 답변 판정")
	class Answers {

		@BeforeEach
		void issuedQuestionAndState() {
			when(questionRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 1))
					.thenReturn(Optional.of(question("R01", (short) 1, "R01 질문")));
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(new RiskWorkflowState(SESSION_ID, "R01")));
			when(resultRepository.findBySessionIdAndRiskIdAndAttempt(anyString(), anyString(), any(Short.class)))
					.thenReturn(Optional.empty());
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.UNDERSTANDING_IN_PROGRESS)));
		}

		@Test
		@DisplayName("UNDERSTOOD 면 COMPLETE / AUTO_RESOLVED 로 종결된다")
		void understoodCompletesRisk() {
			judgeReturns(UnderstandingStatus.UNDERSTOOD, null);

			UnderstandingResponse response = service.submitAnswer(SESSION_ID, answer("R01"));

			assertThat(response.aiStatus()).isEqualTo(UnderstandingStatus.UNDERSTOOD);
			assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETE);
			assertThat(response.finalDisposition()).isEqualTo(FinalDisposition.AUTO_RESOLVED);
			assertThat(response.remainingAttempts()).isEqualTo(1);
		}

		@Test
		@DisplayName("UNCERTAIN 이면 후속 질문을 함께 발급한다 — 재설명을 거치지 않는 유일한 경로다")
		void uncertainIssuesRecheckQuestion() {
			judgeReturns(UnderstandingStatus.UNCERTAIN, "무엇을 묻는지 모르겠다는 답변");

			UnderstandingResponse response = service.submitAnswer(SESSION_ID, answer("R01"));

			assertThat(response.nextAction()).isEqualTo(NextAction.RECHECK);
			assertThat(response.recheckQuestion()).isEqualTo("R01 후속 질문");
			assertThat(response.recheckQuestionSource()).isEqualTo(GenerationSource.FALLBACK);
			assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.IN_PROGRESS);
		}

		@Test
		@DisplayName("MISUNDERSTOOD 는 재설명으로 가며 후속 질문을 여기서 발급하지 않는다")
		void misunderstoodDefersRecheckQuestion() {
			judgeReturns(UnderstandingStatus.MISUNDERSTOOD, "반대로 이해한 답변");

			UnderstandingResponse response = service.submitAnswer(SESSION_ID, answer("R01"));

			assertThat(response.nextAction()).isEqualTo(NextAction.REEXPLAIN);
			assertThat(response.recheckQuestion()).isNull();
			assertThat(response.recheckQuestionSource()).isNull();
		}

		@Test
		@DisplayName("같은 attempt 에 두 번 답하면 ATTEMPT_LIMIT_EXCEEDED")
		void duplicateAttemptRejected() {
			when(resultRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 1))
					.thenReturn(Optional.of(mock(UnderstandingResult.class)));

			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.ATTEMPT_LIMIT_EXCEEDED);
			verify(answerJudge, never()).judge(anyString(), any());
		}

		@Test
		@DisplayName("이미 종결된 Risk 는 RISK_ALREADY_FINALIZED")
		void finalizedRiskRejected() {
			RiskWorkflowState completed = new RiskWorkflowState(SESSION_ID, "R01");
			completed.transitionTo(WorkflowStatus.COMPLETE, FinalDisposition.AUTO_RESOLVED,
					workflowStateMachine);
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(completed));

			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.RISK_ALREADY_FINALIZED);
			verify(answerJudge, never()).judge(anyString(), any());
		}

		@Test
		@DisplayName("질문이 발급되지 않은 Risk 에는 답할 수 없다")
		void answerBeforeQuestionRejected() {
			when(questionRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 1))
					.thenReturn(Optional.empty());

			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
		}

		@Test
		@DisplayName("이해확인 대상이 아닌 Risk 는 RISK_NOT_FOUND")
		void nonTargetRiskRejected() {
			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R05"))))
					.isEqualTo(ErrorCode.RISK_NOT_FOUND);
		}

		@Test
		@DisplayName("UNDERSTOOD 가 아닌데 사유가 없으면 파싱 실패 — ck_reason_required 를 앞당겨 막는다")
		void missingReasonIsParsingFailure() {
			judgeReturns(UnderstandingStatus.MISUNDERSTOOD, null);

			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.AI_PARSING_FAILED);
			verify(writer, never()).saveAnswer(anyString(), any(), any(), any(), anyBooleanArg(), any());
		}

		@Test
		@DisplayName("빈 답변은 INVALID_REQUEST — LLM 을 부르지 않는다")
		void blankAnswerRejected() {
			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID,
					new SubmitAnswerRequest("R01", "  ", AnswerSource.CUSTOMER_DIRECT_DEMO))))
					.isEqualTo(ErrorCode.INVALID_REQUEST);
			verify(answerJudge, never()).judge(anyString(), any());
		}

		@Test
		@DisplayName("이해확인 단계가 아니면 거절한다")
		void wrongSessionStatusRejected() {
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.COVERAGE_ANALYZED)));

			assertThat(errorCodeOf(() -> service.submitAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
		}

		@Test
		@DisplayName("마지막 Risk 를 끝내면 이해확인이 끝났다고 writer 에 알린다")
		void lastRiskFinishesUnderstanding() {
			RiskWorkflowState r01 = new RiskWorkflowState(SESSION_ID, "R01");
			RiskWorkflowState r02 = completed("R02");
			RiskWorkflowState r03 = completed("R03");
			when(workflowStateRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(r01, r02, r03));
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(r01));
			judgeReturns(UnderstandingStatus.UNDERSTOOD, null);

			UnderstandingResponse response = service.submitAnswer(SESSION_ID, answer("R01"));

			assertThat(response.nextAction()).isEqualTo(NextAction.GO_TO_REPORT);
			verify(writer).saveAnswer(eq(SESSION_ID), any(), any(), any(), eq(true), any());
		}
	}

	@Nested
	@DisplayName("F07 후속 확인 (attempt 2)")
	class Recheck {

		@BeforeEach
		void issuedRecheckQuestionAndState() {
			when(questionRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 2))
					.thenReturn(Optional.of(question("R01", (short) 2, "R01 후속 질문")));
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(inProgress("R01")));
			when(resultRepository.findBySessionIdAndRiskIdAndAttempt(anyString(), anyString(), any(Short.class)))
					.thenReturn(Optional.empty());
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.UNDERSTANDING_IN_PROGRESS)));
		}

		@Test
		@DisplayName("attempt 2 로 기록되고 남은 시도는 0 이다")
		void recordsSecondAttempt() {
			judgeReturns(UnderstandingStatus.UNDERSTOOD, null);

			UnderstandingResponse response = service.submitRecheckAnswer(SESSION_ID, answer("R01"));

			assertThat(response.attempt()).isEqualTo(2);
			assertThat(response.remainingAttempts()).isZero();
		}

		@Test
		@DisplayName("후속에서도 안 풀리면 MANUAL_REVIEW_REQUIRED 이고 처분은 아직 null 이다")
		void unresolvedGoesToStaff() {
			judgeReturns(UnderstandingStatus.MISUNDERSTOOD, "여전히 반대로 이해");

			UnderstandingResponse response = service.submitRecheckAnswer(SESSION_ID, answer("R01"));

			assertThat(response.workflowStatus()).isEqualTo(WorkflowStatus.MANUAL_REVIEW_REQUIRED);
			// 계약이 명시한다 — MANUAL_REVIEW_REQUIRED 는 workflowStatus 의 값이지 처분이 아니다
			assertThat(response.finalDisposition()).isNull();
			assertThat(response.nextAction()).isEqualTo(NextAction.STAFF_RESOLUTION_REQUIRED);
		}

		@Test
		@DisplayName("UNCERTAIN 도 attempt 2 에서는 직원에게 넘어간다")
		void uncertainAlsoGoesToStaff() {
			judgeReturns(UnderstandingStatus.UNCERTAIN, "여전히 모르겠다는 답변");

			UnderstandingResponse response = service.submitRecheckAnswer(SESSION_ID, answer("R01"));

			assertThat(response.nextAction()).isEqualTo(NextAction.STAFF_RESOLUTION_REQUIRED);
		}

		@Test
		@DisplayName("Risk 당 1회만 허용된다 — 두 번째는 ATTEMPT_LIMIT_EXCEEDED")
		void onlyOnce() {
			when(resultRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 2))
					.thenReturn(Optional.of(mock(UnderstandingResult.class)));

			assertThat(errorCodeOf(() -> service.submitRecheckAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.ATTEMPT_LIMIT_EXCEEDED);
		}

		@Test
		@DisplayName("attempt 2 질문이 없으면 답할 수 없다 — 재설명이나 UNCERTAIN 을 거쳐야 발급된다")
		void requiresIssuedRecheckQuestion() {
			when(questionRepository.findBySessionIdAndRiskIdAndAttempt(SESSION_ID, "R01", (short) 2))
					.thenReturn(Optional.empty());

			assertThat(errorCodeOf(() -> service.submitRecheckAnswer(SESSION_ID, answer("R01"))))
					.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
		}
	}

	@Nested
	@DisplayName("F07 직원 해결 처리")
	class StaffResolutionHandling {

		@BeforeEach
		void manualReviewState() {
			when(sessionRepository.findById(SESSION_ID))
					.thenReturn(Optional.of(session(SessionStatus.AWAITING_STAFF_REVIEW)));
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(manualReview("R01")));
			when(staffResolutionRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.empty());
			when(resultRepository.findBySessionIdAndRiskIdOrderByAttemptAsc(SESSION_ID, "R01"))
					.thenReturn(List.of());
		}

		@Test
		@DisplayName("RESOLVED_BY_STAFF 면 COMPLETE / RESOLVED_BY_STAFF 로 종결된다")
		void resolvedByStaff() {
			when(understandingQueryService.statesOf(any())).thenReturn(List.of(
					riskState("R01", WorkflowStatus.COMPLETE, FinalDisposition.RESOLVED_BY_STAFF, null)));

			StaffResolutionResponse response = service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.RESOLVED_BY_STAFF, "구두로 다시 설명함", "staff-1"));

			assertThat(response.riskState().workflowStatus()).isEqualTo(WorkflowStatus.COMPLETE);
			assertThat(response.riskState().finalDisposition()).isEqualTo(FinalDisposition.RESOLVED_BY_STAFF);
		}

		@Test
		@DisplayName("UNRESOLVED 여도 다음 Risk 로 진행한다 (PRD §7.5)")
		void unresolvedStillProceeds() {
			RiskWorkflowState r01 = manualReview("R01");
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(r01));
			when(workflowStateRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(r01, new RiskWorkflowState(SESSION_ID, "R02")));
			when(understandingQueryService.statesOf(any())).thenReturn(List.of(
					riskState("R01", WorkflowStatus.COMPLETE, FinalDisposition.UNRESOLVED, null)));

			StaffResolutionResponse response = service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.UNRESOLVED, "고객이 이해를 거부함", "staff-1"));

			assertThat(response.riskState().finalDisposition()).isEqualTo(FinalDisposition.UNRESOLVED);
			assertThat(response.nextAction()).isEqualTo(NextAction.NEXT_RISK);
		}

		@Test
		@DisplayName("AI 원판정을 덮어쓰지 않는다 — 응답에 그대로 실린다 (규칙 1)")
		void aiStatusIsNotOverwritten() {
			UnderstandingResult second = mock(UnderstandingResult.class);
			when(second.getAiStatus()).thenReturn(UnderstandingStatus.MISUNDERSTOOD);
			when(resultRepository.findBySessionIdAndRiskIdOrderByAttemptAsc(SESSION_ID, "R01"))
					.thenReturn(List.of(second));
			when(understandingQueryService.statesOf(any())).thenReturn(List.of(
					riskState("R01", WorkflowStatus.COMPLETE, FinalDisposition.RESOLVED_BY_STAFF,
							UnderstandingStatus.MISUNDERSTOOD)));

			StaffResolutionResponse response = service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.RESOLVED_BY_STAFF, "구두로 다시 설명함", "staff-1"));

			// 직원이 해결해도 AI 는 여전히 MISUNDERSTOOD 다. 리포트에 둘 다 표시된다
			assertThat(response.riskState().attempts().getLast().aiStatus())
					.isEqualTo(UnderstandingStatus.MISUNDERSTOOD);
			assertThat(response.riskState().finalDisposition()).isEqualTo(FinalDisposition.RESOLVED_BY_STAFF);
			verify(resultRepository, never()).save(any());
		}

		@Test
		@DisplayName("같은 Risk 를 두 번 처리할 수 없다 — uq_staff_resolution 을 앞당겨 막는다")
		void duplicateRejected() {
			when(staffResolutionRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(mock(StaffResolution.class)));

			assertThat(errorCodeOf(() -> service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.RESOLVED_BY_STAFF, "다시 설명함", "staff-1"))))
					.isEqualTo(ErrorCode.RISK_ALREADY_FINALIZED);
		}

		@Test
		@DisplayName("사유가 5자 미만이면 거절한다 — ck_resolution_reason_len 을 앞당겨 막는다")
		void shortReasonRejected() {
			assertThat(errorCodeOf(() -> service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.UNRESOLVED, "짧음", "staff-1"))))
					.isEqualTo(ErrorCode.UNRESOLVED_REASON_REQUIRED);
			verify(writer, never()).saveStaffResolution(anyString(), any(), any(), anyBooleanArg(), any());
		}

		@Test
		@DisplayName("마지막 Risk 를 처리하면 리포트로 간다")
		void lastRiskGoesToReport() {
			RiskWorkflowState r01 = manualReview("R01");
			when(workflowStateRepository.findBySessionIdAndRiskId(SESSION_ID, "R01"))
					.thenReturn(Optional.of(r01));
			when(workflowStateRepository.findBySessionIdOrderByRiskIdAsc(SESSION_ID))
					.thenReturn(List.of(r01, completed("R02")));

			StaffResolutionResponse response = service.resolveByStaff(SESSION_ID, "R01",
					new StaffResolutionRequest(StaffDisposition.RESOLVED_BY_STAFF, "구두로 다시 설명함", "staff-1"));

			assertThat(response.nextAction()).isEqualTo(NextAction.GO_TO_REPORT);
			verify(writer).saveStaffResolution(eq(SESSION_ID), any(), any(), eq(true), any());
		}
	}

	// ------------------------------------------------------------------

	private RiskWorkflowState inProgress(String riskId) {
		RiskWorkflowState state = new RiskWorkflowState(SESSION_ID, riskId);
		state.transitionTo(WorkflowStatus.IN_PROGRESS, null, workflowStateMachine);
		return state;
	}

	private RiskWorkflowState manualReview(String riskId) {
		RiskWorkflowState state = inProgress(riskId);
		state.transitionTo(WorkflowStatus.MANUAL_REVIEW_REQUIRED, null, workflowStateMachine);
		return state;
	}

	private void judgeReturns(UnderstandingStatus status, String reason) {
		when(answerJudge.judge(anyString(), any())).thenReturn(new AnswerJudge.Verdict(status, reason));
	}

	private SubmitAnswerRequest answer(String riskId) {
		return new SubmitAnswerRequest(riskId, "고객 답변입니다.", AnswerSource.CUSTOMER_DIRECT_DEMO);
	}

	private QuestionsResponse.QuestionView view(QuestionsResponse response, String riskId) {
		return response.questions().stream()
				.filter(item -> item.riskId().equals(riskId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("응답에 " + riskId + " 가 없다"));
	}

	private ErrorCode errorCodeOf(Runnable action) {
		ApiException thrown = catchThrowableOfType(action::run, ApiException.class);
		assertThat(thrown).as("ApiException 이 던져지지 않았다").isNotNull();
		return thrown.code();
	}

	@SuppressWarnings("unchecked")
	private <T> ArgumentCaptor<List<T>> captor() {
		return ArgumentCaptor.forClass(List.class);
	}

	private RiskWorkflowState completed(String riskId) {
		RiskWorkflowState state = new RiskWorkflowState(SESSION_ID, riskId);
		state.transitionTo(WorkflowStatus.COMPLETE, FinalDisposition.AUTO_RESOLVED, workflowStateMachine);
		return state;
	}

	private SessionQuestion question(String riskId, short attempt, String text) {
		return new SessionQuestion(SESSION_ID, riskId, attempt, text, GenerationSource.FALLBACK);
	}

	private GateOverride override(String riskId, boolean staffExplanationConfirmed) {
		return new GateOverride(SESSION_ID, riskId, OverrideCategory.AI_MISCLASSIFICATION,
				"구두로 충분히 설명했습니다.", staffExplanationConfirmed, "staff-001");
	}

	private ConsultationSession session(SessionStatus status) {
		ConsultationSession session =
				new ConsultationSession(SESSION_ID, "PROD_A", "CUST_A", "A-2026-08-12-01");
		if (status != SessionStatus.DRAFT) {
			overwrite(session, "status", status);
		}
		return session;
	}

	private List<ProductRisk> risks() {
		return new ArrayList<>(List.of(
				risk("R01", CoveragePolicy.GATE_REQUIRED, true),
				risk("R02", CoveragePolicy.GATE_REQUIRED, true),
				risk("R03", CoveragePolicy.GATE_REQUIRED, true),
				risk("R05", CoveragePolicy.WARN_ONLY, false)));
	}

	private ProductRisk risk(String riskId, CoveragePolicy policy, boolean understandingCheck) {
		return new ProductRisk("PROD_A", riskId, "카테고리", riskId + " 제목", riskId + " 사실",
				policy, understandingCheck, 1, "출처 문장",
				riskId + " 질문", riskId + " 후속 질문", riskId + " 쉬운 설명",
				OffsetDateTime.parse("2026-08-12T00:00:00Z"), "reviewer");
	}

	/** 엔티티가 status setter 를 열지 않으므로(의도된 설계) 테스트에서만 강제로 채운다 */
	private void overwrite(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("테스트 픽스처를 만들지 못했다: " + fieldName, ex);
		}
	}
}
