package io.finready.coverage;

import io.finready.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F03 Coverage 4상태 분석 / Gate 판정 / 직원 Override */
@RestController
@RequestMapping("/api/sessions")
public class CoverageController {

	private final CoverageAnalysisService coverageAnalysisService;

	public CoverageController(CoverageAnalysisService coverageAnalysisService) {
		this.coverageAnalysisService = coverageAnalysisService;
	}

	/**
	 * 계약이 requestBody 를 {@code required: false} 로 두므로 본문 없이도 호출된다 —
	 * 그때는 최신 revision 을 분석한다.
	 */
	@PostMapping("/{sessionId}/coverage")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public CoverageResponse analyzeCoverage(@PathVariable String sessionId,
	                                        @RequestBody(required = false) AnalyzeCoverageRequest request) {
		return coverageAnalysisService.analyze(sessionId,
				request == null ? AnalyzeCoverageRequest.latest() : request);
	}

	@PostMapping("/{sessionId}/gate-override")
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public CoverageResponse overrideGate(@PathVariable String sessionId,
	                                     @RequestBody GateOverrideRequest request) {
		return coverageAnalysisService.override(sessionId, request);
	}
}
