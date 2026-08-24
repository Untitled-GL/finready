package io.finready.product;

import io.finready.common.ApiException;
import io.finready.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F01 — Product A 와 검수된 Risk 목록 조회.
 *
 * <p>읽기 3건을 한 readOnly 트랜잭션으로 묶는다. 앱(Singapore) ↔ DB(Seoul) 왕복이
 * 70~90ms 라 커넥션을 세 번 잡았다 놓는 것부터가 비용이다 (TRD §14.1).
 */
@Service
public class ProductQueryService {

	private final ProductRepository productRepository;
	private final ProductRiskRepository productRiskRepository;
	private final CustomerProfileRepository customerProfileRepository;
	private final DemoPresetCatalog demoPresetCatalog;

	public ProductQueryService(ProductRepository productRepository,
	                           ProductRiskRepository productRiskRepository,
	                           CustomerProfileRepository customerProfileRepository,
	                           DemoPresetCatalog demoPresetCatalog) {
		this.productRepository = productRepository;
		this.productRiskRepository = productRiskRepository;
		this.customerProfileRepository = customerProfileRepository;
		this.demoPresetCatalog = demoPresetCatalog;
	}

	@Transactional(readOnly = true)
	public DemoProductResponse loadDemoProduct() {
		Product product = productRepository.findFirstByLiveDemoTrue()
				.orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
						"데모 상품이 준비되지 않았습니다."));

		// NOT_APPLICABLE 은 Coverage 분석 대상이 아니므로 화면에도 내리지 않는다 (openapi CoveragePolicy)
		List<ProductRisk> risks = productRiskRepository.findByProductIdOrderByRiskIdAsc(product.getId())
				.stream()
				.filter(risk -> risk.getCoveragePolicy() != CoveragePolicy.NOT_APPLICABLE)
				.toList();

		// understandingCheck 는 coveragePolicy 와 독립된 정책이다 (PRD §5). 필터 결과에서 다시 뽑는다
		List<String> understandingCheckRiskIds = risks.stream()
				.filter(ProductRisk::isUnderstandingCheck)
				.map(ProductRisk::getRiskId)
				.toList();

		List<DemoProductResponse.CustomerView> customers = customerProfileRepository.findAll()
				.stream()
				.sorted((a, b) -> a.getId().compareTo(b.getId()))
				.map(DemoProductResponse.CustomerView::from)
				.toList();

		return new DemoProductResponse(
				DemoProductResponse.ProductView.from(product),
				risks.stream().map(DemoProductResponse.RiskView::from).toList(),
				understandingCheckRiskIds,
				customers,
				// 리소스에서 온 값이라 쿼리를 늘리지 않는다. §14.1 리전 교차 예산과 무관하다
				demoPresetCatalog.presets(),
				demoPresetCatalog.answers());
	}
}
