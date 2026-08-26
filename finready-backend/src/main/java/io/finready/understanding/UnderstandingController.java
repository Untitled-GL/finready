package io.finready.understanding;

import io.finready.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F04 이해확인 질문 발급 / F05 답변 판정 */
@RestController
@RequestMapping("/api/sessions")
public class UnderstandingController {

	private final UnderstandingService understandingService;

	public UnderstandingController(UnderstandingService understandingService) {
		this.understandingService = understandingService;
	}

	/** 멱등 — 이미 발급된 질문이 있으면 그대로 반환한다 (TRD §4.6) */
	@PostMapping("/{sessionId}/questions")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public QuestionsResponse getOrCreateQuestions(@PathVariable String sessionId) {
		return understandingService.getOrCreateQuestions(sessionId);
	}

	/**
	 * attempt 1. 후속 확인은 {@code /recheck} 이며, <b>attempt 는 경로가 정한다</b> —
	 * 클라이언트가 보내지 않으므로 2를 1로 바꿔 재시도할 수 없다.
	 */
	@PostMapping("/{sessionId}/understanding")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public UnderstandingResponse submitAnswer(@PathVariable String sessionId,
	                                          @RequestBody SubmitAnswerRequest request) {
		return understandingService.submitAnswer(sessionId, request);
	}

	/**
	 * F07 attempt 2. Risk 당 1회만 허용된다 — 두 번째 호출은 409 {@code ATTEMPT_LIMIT_EXCEEDED} 다.
	 *
	 * <p>여기서도 MISUNDERSTOOD/UNCERTAIN 이면 {@code workflowStatus} 가
	 * {@code MANUAL_REVIEW_REQUIRED} 가 되고 {@code finalDisposition} 은 <b>아직 null</b> 이다 —
	 * 직원이 처리해야 값이 생긴다.
	 */
	@PostMapping("/{sessionId}/recheck")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public UnderstandingResponse submitRecheckAnswer(@PathVariable String sessionId,
	                                                 @RequestBody SubmitAnswerRequest request) {
		return understandingService.submitRecheckAnswer(sessionId, request);
	}

	/** F07 직원 해결 처리. AI 원판정을 덮어쓰지 않는다(규칙 1) */
	@PostMapping("/{sessionId}/risks/{riskId}/staff-resolution")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public StaffResolutionResponse resolveByStaff(@PathVariable String sessionId,
	                                              @PathVariable String riskId,
	                                              @RequestBody StaffResolutionRequest request) {
		return understandingService.resolveByStaff(sessionId, riskId, request);
	}
}
