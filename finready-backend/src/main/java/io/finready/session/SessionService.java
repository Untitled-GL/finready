package io.finready.session;

import io.finready.audit.AuditEventType;
import io.finready.audit.AuditRecorder;
import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import io.finready.common.StateMachine;
import io.finready.coverage.CoverageQueryService;
import io.finready.coverage.CoverageResponse;
import io.finready.product.CustomerProfileRepository;
import io.finready.product.Product;
import io.finready.product.ProductRepository;
import io.finready.understanding.NextAction;
import io.finready.understanding.RiskUnderstandingState;
import io.finready.understanding.UnderstandingQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F01 세션 생성 / F02 Revision 저장 / 세션 상태 복구 / F08 종료.
 */
@Service
public class SessionService {

	/** consultation_revision.ck_char_count 상한. 계약의 maxLength 와 같다 */
	private static final int MAX_TRANSCRIPT_LENGTH = 8000;

	/** consultation_session.unresolved_reason 은 varchar(500). 계약의 maxLength 와 같다 */
	private static final int MAX_UNRESOLVED_REASON_LENGTH = 500;

	private final ConsultationSessionRepository sessionRepository;
	private final ConsultationRevisionRepository revisionRepository;
	private final ProductRepository productRepository;
	private final CustomerProfileRepository customerProfileRepository;
	private final CoverageQueryService coverageQueryService;
	private final UnderstandingQueryService understandingQueryService;
	private final CloseEligibilityEvaluator closeEligibilityEvaluator;
	private final AuditRecorder auditRecorder;
	private final StateMachine stateMachine;

	public SessionService(ConsultationSessionRepository sessionRepository,
	                      ConsultationRevisionRepository revisionRepository,
	                      ProductRepository productRepository,
	                      CustomerProfileRepository customerProfileRepository,
	                      CoverageQueryService coverageQueryService,
	                      UnderstandingQueryService understandingQueryService,
	                      CloseEligibilityEvaluator closeEligibilityEvaluator,
	                      AuditRecorder auditRecorder,
	                      StateMachine stateMachine) {
		this.sessionRepository = sessionRepository;
		this.revisionRepository = revisionRepository;
		this.productRepository = productRepository;
		this.customerProfileRepository = customerProfileRepository;
		this.coverageQueryService = coverageQueryService;
		this.understandingQueryService = understandingQueryService;
		this.closeEligibilityEvaluator = closeEligibilityEvaluator;
		this.auditRecorder = auditRecorder;
		this.stateMachine = stateMachine;
	}

	/**
	 * 생성 시점의 productRiskVersion 을 snapshot 으로 고정한다.
	 * 시드가 바뀌어도 진행 중 세션의 판정 기준은 변하지 않는다 (TRD §4.1).
	 */
	@Transactional
	public SessionResponse createSession(CreateSessionRequest request) {
		String productId = require(request.productId(), "productId");
		String customerId = require(request.customerId(), "customerId");

		Product product = productRepository.findById(productId)
				.orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
						"상품을 찾을 수 없습니다."));

		// 계약 ErrorCode 에 고객용 404 가 없다. 존재하지 않는 customerId 는 잘못된 요청으로 다룬다
		if (!customerProfileRepository.existsById(customerId)) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "고객 정보를 찾을 수 없습니다.");
		}

		// sessionId 는 varchar(40). UUID 36자가 들어간다. 접두사를 붙이면 넘친다
		ConsultationSession session = new ConsultationSession(
				UUID.randomUUID().toString(),
				product.getId(),
				customerId,
				product.getProductRiskVersion());

		ConsultationSession saved = sessionRepository.save(session);

		// actorRole=SYSTEM 이다. 인증이 없어 이 화면을 조작한 사람이 누구인지 알 수 없고,
		// 모르면서 STAFF 로 적으면 감사 로그가 없는 신원을 지어내게 된다
		auditRecorder.recordSystem(saved.getId(), AuditEventType.SESSION_CREATED,
				"productId=" + saved.getProductId()
						+ ", customerId=" + saved.getCustomerId()
						+ ", productRiskVersion=" + saved.getProductRiskVersion());

		return SessionResponse.from(saved);
	}

	/**
	 * Revision 은 immutable 이다. 보완 설명도 전체 텍스트로 새 행을 만든다 (TRD §5.2).
	 *
	 * <p>직전 revision 과 텍스트가 완전히 같으면 새로 만들지 않고 기존 것을 돌려준다(계약 명시).
	 * 계약이 201 만 정의하므로 이때도 201 로 나간다.
	 */
	@Transactional
	public RevisionResponse createRevision(String sessionId, CreateRevisionRequest request) {
		ConsultationSession session = loadSession(sessionId);

		if (stateMachine.isClosed(session.getStatus())) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"종료된 상담에는 내용을 추가할 수 없습니다.");
		}

		String text = request.text();
		if (text == null || text.isBlank()) {
			throw new ApiException(ErrorCode.TRANSCRIPT_EMPTY, "상담 내용을 입력해 주세요.");
		}
		if (text.length() > MAX_TRANSCRIPT_LENGTH) {
			throw new ApiException(ErrorCode.TRANSCRIPT_TOO_LONG,
					"상담 내용은 " + MAX_TRANSCRIPT_LENGTH + "자를 넘을 수 없습니다.");
		}

		Optional<ConsultationRevision> latest =
				revisionRepository.findTopBySessionIdOrderByRevisionNoDesc(sessionId);

		if (latest.isPresent() && latest.get().getText().equals(text)) {
			return RevisionResponse.from(latest.get());
		}

		int nextRevisionNo = latest.map(r -> r.getRevisionNo() + 1).orElse(1);

		// text 를 다듬지 않고 그대로 저장한다. evidence offset 이 이 문자열 기준으로 계산되므로
		// trim 하면 나중에 서버가 재계산한 offset 이 화면과 어긋난다 (규칙 4)
		ConsultationRevision saved = revisionRepository.save(
				new ConsultationRevision(sessionId, nextRevisionNo, text));

		// 동일 텍스트 재전송은 위에서 이미 돌아갔다. 여기까지 온 것만 기록하므로
		// 새로고침으로 감사 로그가 부풀지 않는다.
		// 본문은 넣지 않는다 — 길이만으로 충분하고, append-only 테이블에 상담 원문을
		// 복제하면 지울 방법이 없다
		auditRecorder.recordSystem(sessionId, AuditEventType.REVISION_SAVED,
				"revisionNo=" + saved.getRevisionNo() + ", charCount=" + text.length());

		return RevisionResponse.from(saved);
	}

	/**
	 * PRD §13 새로고침 대응. 현재 단계를 다시 그리는 데 필요한 것을 한 번에 준다.
	 *
	 * <p><b>LLM 을 부르지 않는다.</b> 전부 저장된 값을 읽어 조립할 뿐이므로, 새로고침을 몇 번
	 * 하든 요금이 들지 않고 판정도 바뀌지 않는다.
	 *
	 * <p>Coverage 와 Understanding 은 각 모듈의 조회 서비스가 만든다. 여기서 직접 조립하면
	 * Gate 재판정과 질문 대상 규칙이 이 클래스에도 생겨, 분석 직후 응답과 새로고침 응답이
	 * 서로 다른 코드로 만들어진다.
	 */
	@Transactional(readOnly = true)
	public SessionSnapshotResponse getSnapshot(String sessionId) {
		ConsultationSession session = loadSession(sessionId);

		// Coverage 조회도 같은 최신 revision 이 필요하다. 여기서 한 번만 읽고 넘겨
		// 왕복을 하나 아낀다(TRD §14.1) — CoverageQueryService.latestFor(session)
		// 단독 호출이었으면 같은 쿼리를 또 날렸을 것이다.
		Optional<ConsultationRevision> currentRevisionEntity = revisionRepository
				.findTopBySessionIdOrderByRevisionNoDesc(sessionId);
		RevisionResponse currentRevision = currentRevisionEntity
				.map(RevisionResponse::from)
				.orElse(null);

		List<RiskUnderstandingState> understanding = understandingQueryService.statesOf(session);

		return new SessionSnapshotResponse(
				session.getId(),
				session.getProductId(),
				session.getCustomerId(),
				session.getProductRiskVersion(),
				session.getStatus(),
				session.getCreatedAt(),
				session.getClosedAt(),
				resumePointOf(session.getStatus()),
				nextActionOf(session, understanding),
				currentRevision,
				coverageQueryService.latestFor(session, currentRevisionEntity).orElse(null),
				understanding);
	}

	/**
	 * F08 직원 세션 종료. <b>세션을 종료하는 유일한 경로다</b>(계약) — 다른 어떤 엔드포인트도
	 * {@code SESSION_CLOSED_*} 로 전이시키지 않는다.
	 *
	 * <p>AI 가 세션을 종료할 수 없다는 보장은 요청 거부가 아니라 <b>경로 부재</b>에서 나온다.
	 * AI Gateway 에는 자사 API 를 호출하는 클라이언트가 없고, LLM 출력은 enum 으로 파싱되어
	 * 판정값으로만 소비된다 (TRD §13.1).
	 *
	 * <p><b>멱등하다.</b> 이미 닫힌 세션에 다시 호출해도 같은 응답이 나간다 — 종료 버튼을
	 * 두 번 누르거나 새로고침 후 다시 누르는 것이 409 로 끝나면 안 된다(계약 명시).
	 */
	@Transactional
	public SessionResponse closeSession(String sessionId, CloseSessionRequest request) {
		ConsultationSession session = loadSession(sessionId);
		String actor = require(request == null ? null : request.actor(), "actor");

		if (stateMachine.isClosed(session.getStatus())) {
			return SessionResponse.from(session);
		}

		List<RiskUnderstandingState> understanding = understandingQueryService.statesOf(session);
		List<String> unresolvedRiskIds = closeEligibilityEvaluator.unresolvedRiskIds(understanding);
		CloseEligibility eligibility = closeEligibilityEvaluator.evaluate(
				session.getStatus(), warningRiskIdsOf(session), unresolvedRiskIds);

		if (!eligibility.canClose()) {
			throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
					"이해 확인이 끝난 뒤에 상담을 종료할 수 있습니다.");
		}

		String unresolvedReason = normalize(request.unresolvedReason());
		if (eligibility.requiresUnresolvedReason() && unresolvedReason == null) {
			throw new ApiException(ErrorCode.UNRESOLVED_REASON_REQUIRED,
					"확인되지 않은 항목이 있습니다. 사유를 입력해 주세요.",
					unresolvedRiskIds.getFirst());
		}
		if (unresolvedReason != null && unresolvedReason.length() > MAX_UNRESOLVED_REASON_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_REQUEST,
					"미해결 사유는 " + MAX_UNRESOLVED_REASON_LENGTH + "자를 넘을 수 없습니다.");
		}

		List<String> unacknowledged = unacknowledged(
				eligibility.requiresWarningAcknowledgement(), request.acknowledgedWarnings());
		if (!unacknowledged.isEmpty()) {
			throw new ApiException(ErrorCode.WARNING_ACKNOWLEDGEMENT_REQUIRED,
					"확인이 필요한 경고 항목이 남아 있습니다.", unacknowledged.getFirst());
		}

		// 미해결이 없으면 사유를 저장하지 않는다. 정상 종료인데 사유가 남아 있으면
		// 리포트에서 "무언가 미해결이었다"로 읽힌다
		session.close(eligibility.expectedCloseStatus(), actor,
				eligibility.requiresUnresolvedReason() ? unresolvedReason : null,
				stateMachine);

		// 사유 본문은 넣지 않는다 — consultation_session 에 이미 저장돼 있고,
		// append-only 테이블에 자유 입력을 복제할 이유가 없다
		auditRecorder.recordStaff(sessionId, AuditEventType.SESSION_CLOSED, actor,
				"status=" + session.getStatus()
						+ ", unresolvedRiskIds=" + unresolvedRiskIds
						+ ", acknowledgedWarnings=" + eligibility.requiresWarningAcknowledgement());

		return SessionResponse.from(session);
	}

	/** Coverage 분석 전이면 확인할 경고 자체가 없다 — 그 상태에서는 종료 조건에도 못 간다 */
	private List<String> warningRiskIdsOf(ConsultationSession session) {
		return coverageQueryService.latestFor(session)
				.map(CoverageResponse::warningRiskIds)
				.orElseGet(List::of);
	}

	/**
	 * 계약이 "해당 riskId를 모두 포함해야 한다"고 정한다. <b>개수만 세지 않는다</b> —
	 * 다른 Risk 를 같은 개수만큼 보내면 통과해 버린다.
	 */
	private List<String> unacknowledged(List<String> required, List<String> acknowledged) {
		if (required.isEmpty()) {
			return List.of();
		}
		Set<String> confirmed = acknowledged == null ? Set.of() : Set.copyOf(acknowledged);
		return required.stream().filter(riskId -> !confirmed.contains(riskId)).toList();
	}

	private String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}

	/**
	 * 계약: "Understanding 단계 진행 중이면 현재 시점의 분기값. Coverage 단계이거나 세션 종료
	 * 후에는 null이며, 이때는 resumePoint를 쓴다."
	 *
	 * <p>종료된 세션에서 null 로 두는 것이 중요하다 — 값이 남아 있으면 프론트가 끝난 상담에서
	 * 다음 행동 버튼을 그린다.
	 */
	private NextAction nextActionOf(ConsultationSession session,
	                                List<RiskUnderstandingState> understanding) {
		if (stateMachine.isClosed(session.getStatus())) {
			return null;
		}
		return understandingQueryService.resumeActionOf(understanding);
	}

	private ConsultationSession loadSession(String sessionId) {
		return sessionRepository.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
						"상담 세션을 찾을 수 없습니다."));
	}

	private String require(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, field + " 값이 필요합니다.");
		}
		return value;
	}

	/**
	 * SessionStatus → 복귀 화면.
	 *
	 * <p><b>TRD에 규정이 없다.</b> §6.6은 Understanding 단계의 nextAction → 화면만 정한다.
	 * 아래는 프론트 화면 정의(S01 상품·고객 선택 / S02 상담 입력 / S03 Coverage·Gate /
	 * S04~S06 이해확인·재설명 / S07 직원 처리 / S08 리포트)를 전제로 한 매핑이며,
	 * 프론트와 대조해 확정해야 한다.
	 */
	private ResumePoint resumePointOf(SessionStatus status) {
		return switch (status) {
			case DRAFT -> ResumePoint.S02;
			case COVERAGE_ANALYZED, GATE_BLOCKED -> ResumePoint.S03;
			case UNDERSTANDING_IN_PROGRESS -> ResumePoint.S04;
			case AWAITING_STAFF_REVIEW -> ResumePoint.S07;
			case SESSION_CLOSED_BY_STAFF, SESSION_CLOSED_WITH_UNRESOLVED -> ResumePoint.S08;
		};
	}
}
