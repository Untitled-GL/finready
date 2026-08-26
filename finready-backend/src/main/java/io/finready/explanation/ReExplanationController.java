package io.finready.explanation;

import io.finready.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F06 검수 근거 직접조회 기반 재설명 */
@RestController
@RequestMapping("/api/sessions")
public class ReExplanationController {

	private final ReExplanationService reExplanationService;

	public ReExplanationController(ReExplanationService reExplanationService) {
		this.reExplanationService = reExplanationService;
	}

	/**
	 * 멱등하다 — 이미 생성된 재설명이 있으면 LLM 을 다시 부르지 않고 저장된 값을 돌려준다.
	 * 새로고침이 요금을 다시 물지 않게 하려는 것이며, {@code POST /coverage} 와 같은 판단이다.
	 *
	 * <p>응답에 <b>후속 질문(F07용)이 함께 실린다.</b> 왕복을 줄이고 "동일 질문 반복 금지"를
	 * 서버가 한 번에 보장하기 위해서다(계약).
	 */
	@PostMapping("/{sessionId}/reexplain")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public ReExplanationResponse reExplain(@PathVariable String sessionId,
	                                       @RequestBody ReExplainRequest request) {
		return reExplanationService.reExplain(sessionId, request);
	}
}
