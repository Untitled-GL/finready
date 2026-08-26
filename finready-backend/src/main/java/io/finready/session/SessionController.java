package io.finready.session;

import io.finready.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F01 세션 생성 / F02 Revision 저장 / 세션 복구 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	private final SessionService sessionService;

	public SessionController(SessionService sessionService) {
		this.sessionService = sessionService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public SessionResponse createSession(@RequestBody CreateSessionRequest request) {
		return sessionService.createSession(request);
	}

	@GetMapping("/{sessionId}")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public SessionSnapshotResponse getSession(@PathVariable String sessionId) {
		return sessionService.getSnapshot(sessionId);
	}

	/** 동일 텍스트 재전송이면 기존 revision 을 돌려주지만, 계약이 201 만 정의하므로 상태는 그대로다 */
	@PostMapping("/{sessionId}/revisions")
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public RevisionResponse createRevision(@PathVariable String sessionId,
	                                       @RequestBody CreateRevisionRequest request) {
		return sessionService.createRevision(sessionId, request);
	}

	/**
	 * F08 종료. <b>세션을 종료하는 유일한 경로다.</b>
	 *
	 * <p>이미 닫힌 세션에 재호출해도 200 + 같은 응답이다(멱등). 200 인 이유는 계약이
	 * 201 을 정의하지 않기 때문이며, 새 자원을 만드는 요청도 아니다.
	 */
	@PostMapping("/{sessionId}/close")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public SessionResponse closeSession(@PathVariable String sessionId,
	                                    @RequestBody CloseSessionRequest request) {
		return sessionService.closeSession(sessionId, request);
	}
}
