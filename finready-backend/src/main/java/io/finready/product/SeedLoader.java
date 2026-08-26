package io.finready.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * TRD §4.5 시드 로더. 기동 시 시드를 검증하고 upsert 한다.
 *
 * <p>검증 실패 시 finready.seed.fail-fast=true 면 예외를 던져 기동을 중단시킨다.
 * PRD §19 "정확히 일치" 요건을 서류가 아니라 코드로 지키는 지점이다.
 *
 * <p>test 프로파일에서는 돌지 않는다. 테스트가 매번 실제 Supabase 에 붙는 사고를 막는다.
 */
@Component
@Profile("!test")
public class SeedLoader implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

	private final ProductRepository productRepository;
	private final ProductRiskRepository productRiskRepository;
	private final CustomerProfileRepository customerProfileRepository;
	private final SeedValidator seedValidator;
	private final ResourceLoader resourceLoader;
	private final ObjectMapper objectMapper;

	private final List<String> productSeedPaths;
	private final String customerSeedPath;
	private final boolean failFast;

	/**
	 * {@code finready.seed.paths} 는 콤마 구분 단일 문자열이다. {@code @Value} 가 YAML
	 * 시퀀스를 못 받으므로(인덱스 키로 쪼개져 단일 placeholder 로 해석 불가) 리스트
	 * 대신 이 형태를 쓴다. 첫 번째 경로가 관례상 {@code isLiveDemo} 상품(PROD_A)이다.
	 */
	public SeedLoader(ProductRepository productRepository,
	                  ProductRiskRepository productRiskRepository,
	                  CustomerProfileRepository customerProfileRepository,
	                  SeedValidator seedValidator,
	                  ResourceLoader resourceLoader,
	                  ObjectMapper objectMapper,
	                  @Value("${finready.seed.paths}") String productSeedPathsCsv,
	                  @Value("${finready.seed.customer-path}") String customerSeedPath,
	                  @Value("${finready.seed.fail-fast}") boolean failFast) {
		this.productRepository = productRepository;
		this.productRiskRepository = productRiskRepository;
		this.customerProfileRepository = customerProfileRepository;
		this.seedValidator = seedValidator;
		this.resourceLoader = resourceLoader;
		this.objectMapper = objectMapper;
		this.productSeedPaths = Arrays.stream(productSeedPathsCsv.split(","))
				.map(String::trim)
				.filter(path -> !path.isEmpty())
				.toList();
		this.customerSeedPath = customerSeedPath;
		this.failFast = failFast;
	}

	/**
	 * 시드 적재는 한 트랜잭션이다. Risk 를 지우고 다시 넣는 중간 상태가 남으면 안 된다.
	 *
	 * <p>@Transactional 을 여기에 둔 이유: 같은 클래스의 메서드를 직접 호출하면 프록시를
	 * 거치지 않아 어노테이션이 무효가 된다. 파일 읽기·검증도 트랜잭션 안에 들어오지만
	 * 수백 KB 해시 계산이라 밀리초 단위이고, LLM 호출이 없어 규칙 6과 충돌하지 않는다.
	 *
	 * <p>상품 시드는 {@link #productSeedPaths} 순서대로 전부 적재한다(TRD §18 Step 13 —
	 * PROD_B 합성 대조군 추가). {@code failFast=false} 일 때 한 파일이 실패하면 <b>그
	 * 이후 파일은 시도하지 않는다</b> — 기존 단일 파일 때와 같은 실패 단위(파일 하나
	 * 실패 시 적재 전체를 건너뜀)를 그대로 유지한 것이며, "PROD_A 는 성공, PROD_B 만
	 * 실패"인 경우에도 PROD_A 가 건너뛰어질 수 있다는 뜻이다.
	 */
	@Override
	@Transactional
	public void run(String... args) {
		try {
			CustomerProfileSeedDocument customerSeed = read(customerSeedPath, CustomerProfileSeedDocument.class);
			int customerCount = upsertCustomerProfiles(customerSeed);

			for (String seedPath : productSeedPaths) {
				ProductSeedDocument seed = read(seedPath, ProductSeedDocument.class);
				seedValidator.validate(seed);

				Product product = upsertProduct(seed.product());
				int riskCount = replaceRisks(product.getId(), seed.risks());

				log.info("시드 적재 완료 — product={} ({}), risk {}건",
						product.getId(), product.getProductRiskVersion(), riskCount);
			}
			log.info("customerProfile {}건 적재", customerCount);
		}
		catch (SeedValidationException ex) {
			if (failFast) {
				throw ex;
			}
			log.error("시드 검증에 실패했지만 finready.seed.fail-fast=false 라 적재를 건너뛴다.\n{}", ex.getMessage());
		}
	}

	/**
	 * id 가 시드 지정 문자열이라 save() 가 merge 로 간다. Hibernate 가 필드 접근으로
	 * 값을 덮으므로 엔티티에 setter 를 열지 않고도 upsert 가 된다.
	 */
	private Product upsertProduct(ProductSeedData data) {
		Product product = new Product(
				data.id(),
				data.name(),
				data.archetype(),
				data.productRiskVersion(),
				data.documentId(),
				data.documentUrl(),
				data.documentPageCount(),
				data.documentSha256(),
				data.syntheticNotice(),
				Boolean.TRUE.equals(data.isLiveDemo()));
		return productRepository.save(product);
	}

	/**
	 * Risk 는 개별 갱신이 아니라 통째로 교체한다. 시드에서 Risk 가 빠진 경우까지
	 * 한 방식으로 처리되고, product_risk.id 를 참조하는 테이블이 없어 안전하다.
	 */
	private int replaceRisks(String productId, List<RiskSeedData> risks) {
		productRiskRepository.deleteByProductId(productId);
		productRiskRepository.flush();

		List<ProductRisk> entities = risks.stream()
				.map(data -> new ProductRisk(
						productId,
						data.riskId(),
						data.category(),
						data.title(),
						data.fact(),
						CoveragePolicy.valueOf(data.coveragePolicy()),
						Boolean.TRUE.equals(data.understandingCheck()),
						data.sourcePage(),
						data.sourceText(),
						data.fallbackQuestion(),
						data.fallbackRecheckQuestion(),
						data.fallbackPlainExplanation(),
						toStartOfDayUtc(data.verifiedAt()),
						data.verifiedBy()))
				.toList();

		productRiskRepository.saveAll(entities);
		return entities.size();
	}

	private int upsertCustomerProfiles(CustomerProfileSeedDocument customerSeed) {
		if (customerSeed == null || customerSeed.customerProfiles() == null) {
			return 0;
		}
		List<CustomerProfile> entities = customerSeed.customerProfiles().stream()
				.map(data -> new CustomerProfile(
						data.id(),
						data.label(),
						data.ageGroup(),
						enumOrNull(InvestmentExperience.class, data.investmentExperience()),
						enumOrNull(FinancialLiteracy.class, data.financialLiteracy()),
						enumOrNull(ExplanationLevel.class, data.explanationLevel())))
				.toList();

		customerProfileRepository.saveAll(entities);
		return entities.size();
	}

	private <T> T read(String location, Class<T> type) {
		Resource resource = resourceLoader.getResource(location);
		if (!resource.exists()) {
			throw new SeedValidationException("시드 파일이 없다: " + location);
		}
		try (InputStream in = resource.getInputStream()) {
			return objectMapper.readValue(in, type);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("시드 파일을 읽지 못했다: " + location, ex);
		}
	}

	/** 시드는 "2026-08-12" 형태의 날짜만 준다. 컬럼은 timestamptz 라 UTC 자정으로 고정한다 */
	private OffsetDateTime toStartOfDayUtc(String isoDate) {
		return LocalDate.parse(isoDate).atStartOfDay().atOffset(ZoneOffset.UTC);
	}

	/**
	 * customer_profile 의 enum 3종은 DDL 이 nullable 이다. 값이 없으면 null 로 둔다.
	 * 값이 있는데 목록에 없으면 임의 매핑하지 않고 터뜨린다 (규칙 9).
	 */
	private <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw);
		}
		catch (IllegalArgumentException ex) {
			throw new SeedValidationException(
					type.getSimpleName() + " 에 없는 값이다: '" + raw + "' — TRD §6 목록 밖의 값은 매핑하지 않는다");
		}
	}
}
