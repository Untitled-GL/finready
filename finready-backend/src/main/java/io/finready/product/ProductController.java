package io.finready.product;

import io.finready.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01 — GET /api/products/demo.
 *
 * <p>경로에 /api 를 직접 쓴다. server.servlet.context-path 를 /api 로 잡으면
 * actuator 도 /api/actuator/health 로 옮겨가서 <b>이미 배포된 Render 헬스체크가 깨진다.</b>
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

	private final ProductQueryService productQueryService;

	public ProductController(ProductQueryService productQueryService) {
		this.productQueryService = productQueryService;
	}

	@GetMapping("/demo")
	// springdoc은 @ApiResponse 가 하나라도 있으면 200 을 반환 타입에서 더 이상 자동 추론하지
	// 않는다(@ResponseStatus 가 없는 한). 그래서 성공 응답도 명시해야 한다
	@ApiResponse(responseCode = "200")
	@ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	public DemoProductResponse getDemoProduct() {
		return productQueryService.loadDemoProduct();
	}
}
