package io.finready.product;

import java.util.List;

/**
 * openapi.yml v1.4.2 DemoProductResponse.
 * required 가 [product, risks, customers] 다 — 셋 중 하나라도 비면 계약 위반이다.
 *
 * <p>엔티티를 그대로 내보내지 않는다. product 테이블의 document_sha256·is_live_demo 처럼
 * 계약에 없는 컬럼이 응답에 새는 것을 막는다.
 */
public record DemoProductResponse(
		ProductView product,
		List<RiskView> risks,
		List<String> understandingCheckRiskIds,
		List<CustomerView> customers,
		List<DemoPresetView> demoPresets,
		List<DemoAnswerView> demoAnswers
) {

	public record ProductView(
			String id,
			String name,
			String archetype,
			String productRiskVersion,
			String documentUrl,
			Integer documentPageCount,
			String syntheticNotice
	) {
		static ProductView from(Product product) {
			return new ProductView(
					product.getId(),
					product.getName(),
					product.getArchetype(),
					product.getProductRiskVersion(),
					product.getDocumentUrl(),
					product.getDocumentPageCount(),
					product.getSyntheticNotice());
		}
	}

	public record RiskView(
			String riskId,
			String category,
			String title,
			String fact,
			CoveragePolicy coveragePolicy,
			boolean understandingCheck,
			int sourcePage,
			String sourceText
	) {
		static RiskView from(ProductRisk risk) {
			return new RiskView(
					risk.getRiskId(),
					risk.getCategory(),
					risk.getTitle(),
					risk.getFact(),
					risk.getCoveragePolicy(),
					risk.isUnderstandingCheck(),
					risk.getSourcePage(),
					risk.getSourceText());
		}
	}

	/**
	 * TRD §18 Step 11 데모 preset. S02 '샘플 상담 내용 채우기' 가 쓴다.
	 *
	 * <p>{@code supplementTranscript} 는 nullable 이다 — 채점된 보완문이 있는 시나리오만 갖는다.
	 * 없으면 프론트가 보완 채우기를 내려야 하며, 그 자리를 지어낸 문장으로 메우면 안 된다.
	 */
	public record DemoPresetView(
			String id,
			String label,
			String transcript,
			String supplementTranscript
	) {
	}

	/**
	 * S04 답변 채우기 원문. 한 riskId 에 여러 건이 오고 {@code expectedLabel} 로 구분한다.
	 *
	 * <p>expectedLabel 은 openapi UnderstandingStatus 값이지만 타입은 String 이다. 그 enum 이
	 * understanding 패키지에 있고 그쪽이 이미 product 를 참조하므로, 여기서 되받으면 패키지 순환이
	 * 된다. 값 검증은 빌드 타임에 {@code DemoPresetSeedParityTest} 가 한다 — 시드는 고정 데이터라
	 * 런타임 검증이 필요 없다.
	 *
	 * <p>1회차/재확인 구분이 없다. eval 데이터셋에 재확인 전용 답변이 없기 때문이고, 지어내면
	 * 채점 이력 없는 문장이 데모에 섞인다.
	 */
	public record DemoAnswerView(
			String riskId,
			String expectedLabel,
			String answer
	) {
	}

	public record CustomerView(
			String id,
			String label,
			String ageGroup,
			InvestmentExperience investmentExperience,
			FinancialLiteracy financialLiteracy,
			ExplanationLevel explanationLevel
	) {
		static CustomerView from(CustomerProfile profile) {
			return new CustomerView(
					profile.getId(),
					profile.getLabel(),
					profile.getAgeGroup(),
					profile.getInvestmentExperience(),
					profile.getFinancialLiteracy(),
					profile.getExplanationLevel());
		}
	}
}
