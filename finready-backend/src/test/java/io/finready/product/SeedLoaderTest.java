package io.finready.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRD §18 Step 13 — 시드 로더가 다중 상품(PROD_A + 합성 대조군 PROD_B)을 적재하는지.
 *
 * <p>{@link SeedLoader} 는 {@code @Profile("!test")} 라 스프링 부트스트랩 테스트에서
 * 아예 안 돈다 — 지금까지 자동 테스트가 0건이었다. 여기서는 스프링 컨텍스트 없이
 * {@code new SeedLoader(...)} 로 직접 호출한다. {@link SeedValidator} 와
 * {@link ResourceLoader} · {@link ObjectMapper} 는 진짜 인스턴스를 쓴다 — PROD_B
 * placeholder PDF의 SHA-256 검증까지 이 테스트가 실제로 태운다. 3개 리포지토리만
 * Mockito mock이다.
 */
class SeedLoaderTest {

	private static final String PROD_A_PATH = "classpath:seed/product_a_risk_schema.json";
	private static final String PROD_B_PATH = "classpath:seed/product_b_risk_schema.json";
	private static final String CUSTOMER_PATH = "classpath:seed/customer_profiles.json";

	private ProductRepository productRepository;
	private ProductRiskRepository productRiskRepository;
	private CustomerProfileRepository customerProfileRepository;
	private ResourceLoader resourceLoader;
	private ObjectMapper objectMapper;
	private SeedValidator seedValidator;

	@BeforeEach
	void setUp() {
		productRepository = mock(ProductRepository.class);
		productRiskRepository = mock(ProductRiskRepository.class);
		customerProfileRepository = mock(CustomerProfileRepository.class);
		resourceLoader = new DefaultResourceLoader();
		objectMapper = new ObjectMapper();
		seedValidator = new SeedValidator(resourceLoader, "classpath:static/documents");

		// upsertProduct() 는 save() 의 반환값을 그대로 쓴다 — echo해주지 않으면
		// product.getId() 가 다음 로그 줄에서 NullPointerException.
		when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private SeedLoader loader(String productSeedPathsCsv) {
		return new SeedLoader(productRepository, productRiskRepository, customerProfileRepository,
				seedValidator, resourceLoader, objectMapper,
				productSeedPathsCsv, CUSTOMER_PATH, true);
	}

	@Test
	@DisplayName("PROD_A + PROD_B 두 시드가 모두 적재된다")
	void loadsBothProducts() {
		loader(PROD_A_PATH + "," + PROD_B_PATH).run();

		var products = org.mockito.ArgumentCaptor.forClass(Product.class);
		verify(productRepository, times(2)).save(products.capture());

		assertThat(products.getAllValues())
				.extracting(Product::getId)
				.containsExactly("PROD_A", "PROD_B");
		assertThat(products.getAllValues())
				.extracting(Product::getArchetype)
				.containsExactly("NO_KNOCK_IN_STEP_DOWN", "KNOCK_IN_STEP_DOWN");
	}

	@Test
	@DisplayName("PROD_A만 isLiveDemo=true — GET /products/demo가 PROD_B로 갈라지지 않는다")
	void onlyProdAIsLiveDemo() {
		loader(PROD_A_PATH + "," + PROD_B_PATH).run();

		var products = org.mockito.ArgumentCaptor.forClass(Product.class);
		verify(productRepository, times(2)).save(products.capture());

		assertThat(products.getAllValues())
				.filteredOn(Product::isLiveDemo)
				.extracting(Product::getId)
				.containsExactly("PROD_A");
	}

	@Test
	@DisplayName("각 상품의 Risk 9건이 통째로 교체된다")
	void replacesNineRisksPerProduct() {
		loader(PROD_A_PATH + "," + PROD_B_PATH).run();

		verify(productRiskRepository, times(1)).deleteByProductId("PROD_A");
		verify(productRiskRepository, times(1)).deleteByProductId("PROD_B");

		var risks = org.mockito.ArgumentCaptor.forClass(List.class);
		verify(productRiskRepository, times(2)).saveAll(risks.capture());
		assertThat(risks.getAllValues())
				.allSatisfy(list -> assertThat(list).hasSize(9));
	}

	@Test
	@DisplayName("두 번째 경로가 없는 파일이면 SeedValidationException — 첫 번째는 이미 처리된 채 예외가 난다")
	void missingSecondFileFailsFast() {
		String missingPath = "classpath:seed/does_not_exist.json";

		assertThatThrownBy(() -> loader(PROD_A_PATH + "," + missingPath).run())
				.isInstanceOf(SeedValidationException.class)
				.hasMessageContaining("시드 파일이 없다");

		// PROD_A는 두 번째 파일을 읽기 전에 이미 save() 가 호출된 상태로 남는다(트랜잭션은
		// 실제 DB에서만 롤백된다 — 이 테스트는 리포지토리가 mock이라 호출 자체는 관측된다).
		verify(productRepository, times(1)).save(argThat(p -> "PROD_A".equals(p.getId())));
	}
}
