package io.finready.understanding;

import io.finready.audit.AuditEntry;
import io.finready.audit.AuditEventType;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.common.GenerationSource;
import io.finready.common.StateMachine;
import io.finready.coverage.GateOverride;
import io.finready.coverage.GateOverrideRepository;
import io.finready.product.CustomerProfile;
import io.finready.product.CustomerProfileRepository;
import io.finready.product.ProductRisk;
import io.finready.product.ProductRiskRepository;
import io.finready.session.ConsultationSession;
import io.finready.session.ConsultationSessionRepository;
import io.finready.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F04 이해확인 질문 발급 / F05 답변 판정(attempt 1).
 *
 * <p>{@code CoverageAnalysisService} 와 같은 이유로 <b>{@code @Transactional} 이 없다</b>(규칙 6) —
 * 읽기 → LLM 호출(트랜잭션 밖) → 쓰기이고, 쓰기만 {@link UnderstandingWriter} 가 묶는다.
 *
 * <p>workflow 상태 갱신은 전부 이 모듈을 통과한다(TRD §4.2). Coverage 의 Override 가
 * "질문에서 제외"를 만들지만, 그 사실을 {@code risk_workflow_state} 에 기록하는 것도
 * 여기다 — 상태를 만드는 곳이 둘이면 일원화가 아니다.
 */
@Service
public class UnderstandingService {

	private static final Logger log = LoggerFactory.getLogger(UnderstandingService.class);

	/** ck_resolution_reason_len 과 계약(minLength: 5)이 같은 값이다 */
	private static final int MIN_RESOLUTION_REASON = 5;

	private final ConsultationSessionRepository sessionRepository;
	private final ProductRiskRepository productRiskRepository;
	private final CustomerProfileRepository customerProfileRepository;
	private final GateOverrideRepository gateOverrideRepository;
	private final SessionQuestionRepository questionRepository;
	private final UnderstandingResultRepository resultRepository;
	private final RiskWorkflowStateRepository workflowStateRepository;
	private final StaffResolutionRepository staffResolutionRepository;
	private final QuestionGenerator questionGenerator;
	private final AnswerJudge answerJudge;
	private final NextActionResolver nextActionResolver;
	private final WorkflowStateMachine workflowStateMachine;
	private final UnderstandingWriter writer;
	private final StateMachine stateMachine;
	private final UnderstandingQueryService understandingQueryService;

	public UnderstandingService(ConsultationSessionRepository sessionRepository,
	                            ProductRiskRepository productRiskRepository,
	                            CustomerProfileRepository customerProfileRepository,
	                            GateOverrideRepository gateOverrideRepository,
	                            SessionQuestionRepository questionRepository,
	                            UnderstandingResultRepository resultRepository,
	                            RiskWorkflowStateRepository workflowStateRepository,
	                            StaffResolutionRepository staffResolutionRepository,
	                            QuestionGenerator questionGenerator,
	                            AnswerJudge answerJudge,
	                            NextActionResolver nextActionResolver,
	                            WorkflowStateMachine workflowStateMachine,
	                            UnderstandingWriter writer,
	                            StateMachine stateMachine,
	                            UnderstandingQueryService understandingQueryService) {
		this.sessionRepository = sessionRepository;
		this.productRiskRepository = productRiskRepository;
		this.customerProfileRepository = customerProfileRepository;
		this.gateOverrideRepository = gateOverrideRepository;
		this.questionRepository = questionRepository;
		this.resultRepository = resultRepository;
		this.workflowStateRepository = workflowStateRepository;
		this.staffResolutionRepository = staffResolutionRepository;
		this.questionGenerator = questionGenerator;
		this.answerJudge = answerJudge;
		this.nextActionResolver = nextActionResolver;
		this.workflowStateMachine = workflowStateMachine;
		this.writer = writer;
		this.stateMachine = stateMachine;
		this.understandingQueryService = understandingQueryService;
	}

	/**
	 * F04 — 이해확인 질문 발급 또는 조회.
	 *
	 * <p><b>멱등하다.</b> 이미 발급된 질문이 있으면 그대로 돌려준다 — 데모 중 새로고침이나
	 * 뒤로가기로 문구가 바뀌면 고객이 다른 질문을 받은 것처럼 보인다(TRD §4.6).
	 */
	public QuestionsResponse getOrCreateQuestions(String sessionId) {
		ConsultationSession session = loadOpenSession(sessionId);
		assertGateOpen(session);

		List<ProductRisk> targets = questionTargets(session);
		if (targets.isEmpty()) {
			throw new ApiException(ErrorCode.GATE_NOT_OPEN,
					"이해 확인이 필요한 항목이 없습니다.");
		}

		Map<String, SessionQuestion> issued = issuedQuestions(sessionId, UnderstandingPolicy.FIRST_ATTEMPT);
		if (issued.keySet().containsAll(targets.stream().map(ProductRisk::getRiskId).toList())) {
			return toQuestionsResponse(sessionId, targets, issued);
		}

		Map<String, String> phrased = phraseQuestions(session, targets);

		List<SessionQuestion> toSave = new ArrayList<>();
		for (ProductRisk risk : targets) {
			if (issued.containsKey(risk.getRiskId())) {
				continue;
			}
			String phrasedText = phrased.get(risk.getRiskId());
			// 다듬기에 실패한 Risk 는 검수 문항을 그대로 쓴다 — 계약이 정한 정상 경로다
			boolean usedFallback = phrasedText == null || phrasedText.isBlank();
			SessionQuestion question = new SessionQuestion(
					sessionId,
					risk.getRiskId(),
					UnderstandingPolicy.FIRST_ATTEMPT,
					usedFallback ? risk.getFallbackQuestion() : phrasedText,
					usedFallback ? GenerationSource.FALLBACK : GenerationSource.LLM);
			toSave.add(question);
			issued.put(risk.getRiskId(), question);
		}

		// 문구는 남기지 않는다 — session_question 에 이미 있고, 감사 로그가 볼 것은
		// "무엇에 대해 물었나"와 "몇 개가 검수 문항으로 대체됐나"다
		writer.saveIssuedQuestions(sessionId, toSave, initialWorkflowStates(session, targets),
				AuditEntry.system(AuditEventType.QUESTIONS_ISSUED,
						"riskIds=" + toSave.stream().map(SessionQuestion::getRiskId).toList()
								+ ", fallbackCount=" + toSave.stream()
								.filter(q -> q.getGenerationSource() == GenerationSource.FALLBACK)
								.count()));

		return toQuestionsResponse(sessionId, targets, issued);
	}

	/**
	 * F05 — attempt 1 답변 판정.
	 *
	 * <p>attempt 상한은 <b>서버가 강제한다</b>. 프론트가 attempt 를 보내지 않는 이유가 이것이다 —
	 * 경로가 attempt 를 정하므로 클라이언트가 2를 1로 바꿔 재시도할 수 없다.
	 */
	public UnderstandingResponse submitAnswer(String sessionId, SubmitAnswerRequest request) {
		return judge(sessionId, request, UnderstandingPolicy.FIRST_ATTEMPT);
	}

	/**
	 * F07 — attempt 2 답변 판정.
	 *
	 * <p>attempt 만 다르고 나머지는 attempt 1 과 같다. <b>경로가 attempt 를 정한다</b> —
	 * 클라이언트가 보내지 않으므로 2를 1로 바꿔 재시도할 수 없다. Risk 당 1회는
	 * {@code judge} 안의 중복 검사가 강제하고, DB 의 {@code ck_understanding_attempt} 가 2를 넘기지 못하게 한다.
	 */
	public UnderstandingResponse submitRecheckAnswer(String sessionId, SubmitAnswerRequest request) {
		return judge(sessionId, request, UnderstandingPolicy.RECHECK_ATTEMPT);
	}

	/**
	 * F07 — 직원 해결 처리.
	 *
	 * <p><b>{@code ai_status} 를 덮어쓰지 않는다</b>(규칙 1). {@code staff_resolution} 행을 더할 뿐이며,
	 * AI 판정은 리포트에 원래 값으로 남는다. 처분이 {@code UNRESOLVED} 여도 다음 Risk 로
	 * 진행한다 — 미해결 여부는 최종 Close 상태에서 구분된다(PRD §7.5).
	 */
	public StaffResolutionResponse resolveByStaff(String sessionId,
	                                              String riskId,
	                                              StaffResolutionRequest request) {
		ConsultationSession session = loadOpenSession(sessionId);
		require(riskId, "riskId");

		if (request == null || request.disposition() == null) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "처리 구분을 선택해 주세요.", riskId);
		}
		String reason = request.reason() == null ? "" : request.reason().strip();
		if (reason.length() < MIN_RESOLUTION_REASON) {
			// ck_resolution_reason_len 을 여기서 먼저 막는다. DB 까지 가면 400 이 아니라 500 이 된다
			throw new ApiException(ErrorCode.UNRESOLVED_REASON_REQUIRED,
					"처리 사유를 %d자 이상 입력해 주세요.".formatted(MIN_RESOLUTION_REASON), riskId);
		}
		String actor = require(request.actor(), "actor");

		RiskWorkflowState workflowState = workflowStateRepository
				.findBySessionIdAndRiskId(sessionId, riskId)
				.orElseThrow(() -> new ApiException(ErrorCode.RISK_NOT_FOUND,
						"이해 확인 대상이 아닌 항목입니다.", riskId));

		if (staffResolutionRepository.findBySessionIdAndRiskId(sessionId, riskId).isPresent()) {
			throw new ApiException(ErrorCode.RISK_ALREADY_FINALIZED,
					"이미 처리가 끝난 항목입니다.", riskId);
		}

		FinalDisposition disposition = request.disposition() == StaffDisposition.RESOLVED_BY_STAFF
				? FinalDisposition.RESOLVED_BY_STAFF
				: FinalDisposition.UNRESOLVED;
		workflowState.transitionTo(WorkflowStatus.COMPLETE, disposition, workflowStateMachine);

		StaffResolution resolution = new StaffResolution(
				sessionId, riskId, request.disposition(), reason, actor);

		NextAction nextAction = nextActionResolver
				.afterStaffResolution(hasRemainingRisk(sessionId, riskId));

		SessionStatus sessionStatus = writer.saveStaffResolution(sessionId, resolution, workflowState,
				nextAction == NextAction.GO_TO_REPORT,
				AuditEntry.staff(AuditEventType.STAFF_RESOLUTION_RECORDED, actor,
						"riskId=" + riskId
								+ ", disposition=" + request.disposition()
								+ ", finalDisposition=" + disposition));

		// 쓰기가 이미 커밋된 뒤라(UnderstandingWriter가 별도 트랜잭션) 방금 저장한
		// staffResolution·workflowState까지 반영된 상태를 그대로 다시 읽는다 — 조립 규칙이
		// GET /sessions/{id}와 두 곳으로 갈라지지 않는다
		RiskUnderstandingState riskState = understandingQueryService.statesOf(session).stream()
				.filter(state -> state.riskId().equals(riskId))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"방금 처리한 riskId 를 다시 못 찾았다: " + riskId));

		return new StaffResolutionResponse(
				riskState,
				nextAction,
				progressOf(questionTargets(session), riskId),
				sessionStatus);
	}

	/**
	 * F06 진입 조건 확인 — 재설명은 {@code aiStatus=MISUNDERSTOOD} 인 Risk 에만 적용된다.
	 *
	 * <p>이 검사가 {@code explanation} 패키지가 아니라 여기 있는 이유는, 판정 이력을 읽는
	 * 규칙이 두 곳으로 갈라지지 않게 하기 위해서다. UNCERTAIN 은 재설명 없이 바로 후속
	 * 확인으로 간다(PRD §7.5) — 그 경로를 여기서 한 번만 막는다.
	 *
	 * @return 고객이 반대로 이해한 답변. S06 좌측에 그대로 표시된다
	 * @throws ApiException 해당 Risk 가 MISUNDERSTOOD 가 아니면 {@code INVALID_STATE_TRANSITION}(409)
	 */
	public String requireMisunderstoodAnswer(String sessionId, String riskId) {
		UnderstandingResult first = resultRepository
				.findBySessionIdAndRiskIdAndAttempt(sessionId, riskId, UnderstandingPolicy.FIRST_ATTEMPT)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
						"아직 답변이 기록되지 않았습니다.", riskId));

		if (first.getAiStatus() != UnderstandingStatus.MISUNDERSTOOD) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"재설명이 필요한 항목이 아닙니다.", riskId);
		}
		return first.getAnswer();
	}

	/**
	 * F06 이 부른다 — 재설명 응답에 실을 후속 질문(attempt 2)을 발급하고 영속화한다.
	 *
	 * <p><b>질문 발급은 이 모듈에만 둔다</b>(TRD §4.2·§4.6). {@code explanation} 이 직접
	 * {@code session_question} 을 쓰면 "발급 규칙"이 두 곳이 되고, 멱등성도 각자 구현하게 된다.
	 *
	 * <p>멱등하다 — 재호출해도 문구가 바뀌지 않으며, 새로고침 후
	 * {@code GET /sessions/{id}} 의 {@code pendingQuestion} 으로 같은 문구가 복구된다.
	 */
	public IssuedQuestion issueRecheckQuestion(String sessionId, String riskId) {
		ConsultationSession session = loadOpenSession(sessionId);

		ProductRisk risk = questionTargets(session).stream()
				.filter(candidate -> candidate.getRiskId().equals(riskId))
				.findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.RISK_NOT_FOUND,
						"이해 확인 대상이 아닌 항목입니다.", riskId));

		SessionQuestion first = questionRepository
				.findBySessionIdAndRiskIdAndAttempt(sessionId, riskId, UnderstandingPolicy.FIRST_ATTEMPT)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
						"질문이 아직 발급되지 않았습니다.", riskId));

		SessionQuestion recheck = issueRecheckQuestion(sessionId, risk, first);
		if (recheck.getId() == null) {
			writer.saveRecheckQuestion(recheck);
		}
		return new IssuedQuestion(recheck.getQuestion(), recheck.getGenerationSource());
	}

	/** {@link #issueRecheckQuestion(String, String)} 의 반환값 — 엔티티를 패키지 밖으로 내보내지 않는다 */
	public record IssuedQuestion(String question, GenerationSource source) {
	}

	// ------------------------------------------------------------------

	private UnderstandingResponse judge(String sessionId, SubmitAnswerRequest request, short attempt) {
		ConsultationSession session = loadOpenSession(sessionId);
		if (session.getStatus() != SessionStatus.UNDERSTANDING_IN_PROGRESS) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"이해 확인 단계가 아닙니다. 화면을 새로고침해 주세요.");
		}

		String riskId = require(request.riskId(), "riskId");
		String answer = request.answer();
		if (answer == null || answer.isBlank()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "답변을 입력해 주세요.", riskId);
		}
		if (request.answerSource() == null) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "답변 방식을 선택해 주세요.", riskId);
		}

		List<ProductRisk> targets = questionTargets(session);
		ProductRisk risk = targets.stream()
				.filter(candidate -> candidate.getRiskId().equals(riskId))
				.findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.RISK_NOT_FOUND,
						"이해 확인 대상이 아닌 항목입니다.", riskId));

		RiskWorkflowState workflowState = workflowStateRepository
				.findBySessionIdAndRiskId(sessionId, riskId)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
						"질문이 아직 발급되지 않았습니다.", riskId));

		if (workflowStateMachine.isFinal(workflowState.getWorkflowStatus())) {
			throw new ApiException(ErrorCode.RISK_ALREADY_FINALIZED,
					"이미 처리가 끝난 항목입니다.", riskId);
		}
		if (resultRepository.findBySessionIdAndRiskIdAndAttempt(sessionId, riskId, attempt).isPresent()) {
			throw new ApiException(ErrorCode.ATTEMPT_LIMIT_EXCEEDED,
					"이 항목은 이미 답변이 기록되었습니다.", riskId);
		}

		SessionQuestion question = questionRepository
				.findBySessionIdAndRiskIdAndAttempt(sessionId, riskId, attempt)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
						"질문이 아직 발급되지 않았습니다.", riskId));

		// --- LLM 호출. 여기는 트랜잭션 밖이다 (규칙 6) ---
		AnswerJudge.Verdict verdict = answerJudge.judge(sessionId, new AnswerJudge.JudgeRequest(
				riskId, risk.getTitle(), risk.getFact(), question.getQuestion(), answer, attempt));

		validate(verdict, riskId);

		UnderstandingResult result = new UnderstandingResult(
				sessionId, riskId, attempt,
				question.getQuestion(), question.getGenerationSource(),
				answer, request.answerSource(),
				verdict.status(), verdict.reason());

		Outcome outcome = outcomeOf(verdict.status(), attempt);
		workflowState.transitionTo(outcome.status(), outcome.disposition(), workflowStateMachine);

		NextAction nextAction = nextActionResolver.afterAnswer(
				verdict.status(), attempt, hasRemainingRisk(sessionId, riskId));

		SessionQuestion recheckQuestion = nextAction == NextAction.RECHECK
				? issueRecheckQuestion(sessionId, risk, question)
				: null;

		// 답변 본문은 넣지 않는다 — 고객이 한 말이고, append-only 라 지울 수 없다.
		// 판정 결과와 근거 위치만 남긴다
		writer.saveAnswer(sessionId, result, workflowState, recheckQuestion,
				nextAction == NextAction.GO_TO_REPORT,
				AuditEntry.ai(AuditEventType.ANSWER_JUDGED,
						"riskId=" + riskId
								+ ", attempt=" + attempt
								+ ", aiStatus=" + verdict.status()
								+ ", answerSource=" + request.answerSource()
								+ ", nextAction=" + nextAction));

		return new UnderstandingResponse(
				riskId,
				question.getQuestion(),
				answer,
				request.answerSource(),
				attempt,
				UnderstandingPolicy.MAX_ATTEMPTS - attempt,
				verdict.status(),
				verdict.reason(),
				workflowState.getWorkflowStatus(),
				workflowState.getFinalDisposition(),
				nextAction,
				recheckQuestion == null ? null : recheckQuestion.getQuestion(),
				recheckQuestion == null ? null : recheckQuestion.getGenerationSource(),
				progressOf(targets, riskId));
	}

	/**
	 * UNCERTAIN(attempt 1)은 재설명을 거치지 않으므로 이 응답이 후속 질문을 제공하는 유일한
	 * 지점이다 (PRD §7.5). 검수된 {@code fallbackRecheckQuestion} 을 원천으로 발급한다.
	 */
	private SessionQuestion issueRecheckQuestion(String sessionId, ProductRisk risk, SessionQuestion first) {
		Optional<SessionQuestion> existing = questionRepository
				.findBySessionIdAndRiskIdAndAttempt(sessionId, risk.getRiskId(),
						UnderstandingPolicy.RECHECK_ATTEMPT);
		if (existing.isPresent()) {
			return existing.get();   // 멱등 — 재호출해도 문구가 바뀌지 않는다
		}

		String recheck = risk.getFallbackRecheckQuestion();
		if (normalize(recheck).equals(normalize(first.getQuestion()))) {
			// 시드는 ck_recheck_question_differs 로 두 문항이 다름을 보장하므로, 여기가 걸렸다면
			// attempt 1 을 LLM 이 다듬은 결과가 후속 문항과 같아진 경우다. 검수되지 않은 문구를
			// 지어내느니 검수된 문항을 그대로 쓰고 로그로 남긴다 — 데이터로 고칠 문제다
			log.warn("후속 질문이 최초 질문과 동일하다 (riskId={}). 시드 문항 검토 필요", risk.getRiskId());
		}
		return new SessionQuestion(sessionId, risk.getRiskId(),
				UnderstandingPolicy.RECHECK_ATTEMPT, recheck, GenerationSource.FALLBACK);
	}

	/** 판정 결과 → workflow 전이 목표. COMPLETE 일 때만 처분이 붙는다 (TRD §6.3) */
	private Outcome outcomeOf(UnderstandingStatus status, int attempt) {
		if (status == UnderstandingStatus.UNDERSTOOD) {
			return new Outcome(WorkflowStatus.COMPLETE, FinalDisposition.AUTO_RESOLVED);
		}
		if (attempt >= UnderstandingPolicy.MAX_ATTEMPTS) {
			// 처분은 아직 없다. 직원이 처리해야 값이 생긴다
			return new Outcome(WorkflowStatus.MANUAL_REVIEW_REQUIRED, null);
		}
		return new Outcome(WorkflowStatus.IN_PROGRESS, null);
	}

	private record Outcome(WorkflowStatus status, FinalDisposition disposition) {
	}

	private void validate(AnswerJudge.Verdict verdict, String riskId) {
		if (verdict == null || verdict.status() == null) {
			throw new ApiException(ErrorCode.AI_PARSING_FAILED,
					"답변을 판정하지 못했습니다. 잠시 후 다시 시도해 주세요.", riskId);
		}
		// ck_reason_required — UNDERSTOOD 가 아니면 사유가 필수다 (PRD §9 F05).
		// DB 가 막아주지만 INSERT 까지 가면 원인이 LLM 응답이라는 게 안 보인다
		if (verdict.status() != UnderstandingStatus.UNDERSTOOD
				&& (verdict.reason() == null || verdict.reason().isBlank())) {
			throw new ApiException(ErrorCode.AI_PARSING_FAILED,
					"답변을 판정하지 못했습니다. 잠시 후 다시 시도해 주세요.", riskId);
		}
	}

	/**
	 * 질문 대상: {@code understandingCheck=true} 이면서 Override 로 제외되지 않은 Risk.
	 *
	 * <p>{@code staffExplanationConfirmed=true} 로 override 된 Risk 는 <b>대상에 남는다</b> —
	 * "직원이 설명은 했다"는 뜻이지 "고객이 이해했다"는 뜻이 아니기 때문이다.
	 */
	private List<ProductRisk> questionTargets(ConsultationSession session) {
		Map<String, GateOverride> overrides = gateOverrideRepository
				.findBySessionIdOrderByRiskIdAsc(session.getId()).stream()
				.collect(Collectors.toMap(GateOverride::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));

		return productRiskRepository.findByProductIdOrderByRiskIdAsc(session.getProductId()).stream()
				.filter(ProductRisk::isUnderstandingCheck)
				.filter(risk -> !isSkippedByOverride(overrides.get(risk.getRiskId())))
				.toList();
	}

	/**
	 * 계약의 {@code progress} — {@code toQuestionsResponse}의 {@code orderIndex}와 같은
	 * 1-based 순번 규칙을 쓴다. FE가 "Risk N/전체"를 직접 세지 않고 서버 값을 그대로 쓴다.
	 */
	private Progress progressOf(List<ProductRisk> targets, String riskId) {
		int index = 1;
		for (ProductRisk candidate : targets) {
			if (candidate.getRiskId().equals(riskId)) {
				return new Progress(index, targets.size());
			}
			index++;
		}
		return new Progress(0, targets.size());
	}

	private boolean isSkippedByOverride(GateOverride override) {
		return override != null && Boolean.FALSE.equals(override.getStaffExplanationConfirmed());
	}

	/**
	 * workflow 상태를 만드는 유일한 지점이다 (TRD §4.2). Override 로 제외된 Risk 도 여기서
	 * COMPLETE/SKIPPED_BY_OVERRIDE 로 기록한다 — 기록이 없으면 리포트에서 "왜 안 물었나"가 안 보인다.
	 */
	private List<RiskWorkflowState> initialWorkflowStates(ConsultationSession session,
	                                                      List<ProductRisk> targets) {
		Map<String, RiskWorkflowState> existing = workflowStateRepository
				.findBySessionIdOrderByRiskIdAsc(session.getId()).stream()
				.collect(Collectors.toMap(RiskWorkflowState::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));

		Map<String, GateOverride> overrides = gateOverrideRepository
				.findBySessionIdOrderByRiskIdAsc(session.getId()).stream()
				.collect(Collectors.toMap(GateOverride::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));

		List<RiskWorkflowState> created = new ArrayList<>();
		for (ProductRisk risk : targets) {
			if (!existing.containsKey(risk.getRiskId())) {
				created.add(new RiskWorkflowState(session.getId(), risk.getRiskId()));
			}
		}
		for (ProductRisk risk : productRiskRepository
				.findByProductIdOrderByRiskIdAsc(session.getProductId())) {
			if (!risk.isUnderstandingCheck()
					|| existing.containsKey(risk.getRiskId())
					|| !isSkippedByOverride(overrides.get(risk.getRiskId()))) {
				continue;
			}
			RiskWorkflowState skipped = new RiskWorkflowState(session.getId(), risk.getRiskId());
			skipped.transitionTo(WorkflowStatus.COMPLETE, FinalDisposition.SKIPPED_BY_OVERRIDE,
					workflowStateMachine);
			created.add(skipped);
		}
		return created;
	}

	/** 방금 처리한 Risk 를 뺀 나머지에 아직 안 끝난 게 있는지 */
	private boolean hasRemainingRisk(String sessionId, String settledRiskId) {
		return workflowStateRepository.findBySessionIdOrderByRiskIdAsc(sessionId).stream()
				.filter(state -> !state.getRiskId().equals(settledRiskId))
				.anyMatch(state -> state.getWorkflowStatus() != WorkflowStatus.COMPLETE);
	}

	private Map<String, SessionQuestion> issuedQuestions(String sessionId, short attempt) {
		return questionRepository.findBySessionIdOrderByRiskIdAscAttemptAsc(sessionId).stream()
				.filter(question -> question.getAttempt() == attempt)
				.collect(Collectors.toMap(SessionQuestion::getRiskId, Function.identity(),
						(first, second) -> first, LinkedHashMap::new));
	}

	private Map<String, String> phraseQuestions(ConsultationSession session, List<ProductRisk> targets) {
		CustomerProfile customer = customerProfileRepository.findById(session.getCustomerId())
				.orElse(null);

		List<QuestionGenerator.QuestionSeed> seeds = targets.stream()
				.map(risk -> new QuestionGenerator.QuestionSeed(
						risk.getRiskId(), risk.getTitle(), risk.getFact(), risk.getFallbackQuestion(),
						customer == null ? null : customer.getLabel(),
						customer == null || customer.getExplanationLevel() == null
								? null : customer.getExplanationLevel().name()))
				.toList();

		try {
			List<QuestionGenerator.PhrasedQuestion> phrased = questionGenerator.phrase(session.getId(), seeds);
			return phrased == null ? Map.of() : phrased.stream()
					.filter(item -> item.question() != null && !item.question().isBlank())
					.collect(Collectors.toMap(QuestionGenerator.PhrasedQuestion::riskId,
							QuestionGenerator.PhrasedQuestion::question,
							(first, second) -> first, LinkedHashMap::new));
		} catch (RuntimeException ex) {
			// 생성 실패는 정상 경로다. 검수 문항이 있으므로 흐름을 막지 않는다
			log.warn("질문 생성 실패. 검수 문항으로 대체한다 (sessionId={})", session.getId(), ex);
			return Map.of();
		}
	}

	private QuestionsResponse toQuestionsResponse(String sessionId,
	                                              List<ProductRisk> targets,
	                                              Map<String, SessionQuestion> issued) {
		List<QuestionsResponse.QuestionView> views = new ArrayList<>(targets.size());
		int orderIndex = 1;
		for (ProductRisk risk : targets) {
			SessionQuestion question = issued.get(risk.getRiskId());
			views.add(new QuestionsResponse.QuestionView(
					risk.getRiskId(),
					risk.getTitle(),
					question.getQuestion(),
					question.getGenerationSource(),
					question.getAttempt(),
					orderIndex++));
		}
		return new QuestionsResponse(sessionId, targets.size(), views);
	}

	private ConsultationSession loadOpenSession(String sessionId) {
		ConsultationSession session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
						"상담 세션을 찾을 수 없습니다."));
		if (stateMachine.isClosed(session.getStatus())) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"종료된 상담은 수정할 수 없습니다.");
		}
		return session;
	}

	/** Gate 가 열리지 않았으면 계약이 정한 전용 코드로 거절한다 */
	private void assertGateOpen(ConsultationSession session) {
		if (session.getStatus() == SessionStatus.DRAFT
				|| session.getStatus() == SessionStatus.GATE_BLOCKED) {
			throw new ApiException(ErrorCode.GATE_NOT_OPEN,
					"먼저 상담 내용의 설명 누락을 해결해 주세요.");
		}
	}

	private String require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, field + " 값이 필요합니다.");
		}
		return value;
	}

	/** 공백·대소문자 차이만 있는 문구를 "다른 질문"으로 세지 않기 위한 비교용 정규화 */
	private String normalize(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").strip();
	}
}
