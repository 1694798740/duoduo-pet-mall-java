package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductImportRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductImportResultView;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.support.ProductCsv;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-17 · CSV 导入分组与部分成功。
 *
 * <p>三条不能错的规则：
 * <ul>
 *   <li><b>同一分组键 = 一个商品</b>：多行规格要聚合到一次 create，而不是建出多个商品；</li>
 *   <li><b>同组商品字段必须一致</b>：第二行改了标题却静默取第一行，等于用户的修改没生效；</li>
 *   <li><b>一组失败不影响其它组</b>：部分成功是导入的正常形态，逐组返回原因。</li>
 * </ul>
 */
class SellerProductCsvServiceTest {

    private final SellerProductService sellerProductService = mock(SellerProductService.class);
    private final SellerProductCsvService service = new SellerProductCsvService(sellerProductService);

    @Test
    void 两个分组_各自创建一次且规格聚合正确() {
        when(sellerProductService.create(any())).thenAnswer(invocation -> {
            SellerProductCreateRequest request = invocation.getArgument(0);
            return view(1L, request.title());
        });

        SellerProductImportResultView result = service.importCsv(new SellerProductImportRequest(
                csv(line("g1", "猫粮 A", "500", "99.00", "10", "2kg"),
                        line("g1", "猫粮 A", "500", "199.00", "5", "5kg"),
                        line("g2", "猫砂 B", "500", "39.90", "20", "10L"))));

        assertThat(result.totalGroups()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.items().get(0).title()).isEqualTo("猫粮 A");
        assertThat(result.items().get(0).lineNo()).isEqualTo(2);

        ArgumentCaptor<SellerProductCreateRequest> captor =
                ArgumentCaptor.forClass(SellerProductCreateRequest.class);
        verify(sellerProductService, times(2)).create(captor.capture());
        assertThat(captor.getAllValues().get(0).skus()).hasSize(2);
        assertThat(captor.getAllValues().get(0).skus().get(0).price()).isEqualTo(9900L);
        assertThat(captor.getAllValues().get(1).skus()).hasSize(1);
        assertThat(captor.getAllValues().get(1).title()).isEqualTo("猫砂 B");
    }

    @Test
    void 一组失败_其它组仍然成功() {
        when(sellerProductService.create(any())).thenAnswer(invocation -> {
            SellerProductCreateRequest request = invocation.getArgument(0);
            if ("失败品".equals(request.title())) {
                throw BizException.validation("商品类目不存在");
            }
            return view(2L, request.title());
        });

        SellerProductImportResultView result = service.importCsv(new SellerProductImportRequest(
                csv(line("g1", "好商品", "500", "10.00", "1", "默认"),
                        line("g2", "失败品", "500", "10.00", "1", "默认"))));

        assertThat(result.totalGroups()).isEqualTo(2);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().get(1).success()).isFalse();
        assertThat(result.items().get(1).message()).contains("类目不存在");
    }

    @Test
    void 同组商品字段不一致_整组失败且不建商品() {
        SellerProductImportResultView result = service.importCsv(new SellerProductImportRequest(
                csv(line("g1", "猫粮 A", "500", "10.00", "1", "2kg"),
                        line("g1", "猫粮 A 改过的标题", "500", "20.00", "2", "5kg"))));

        assertThat(result.successCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().get(0).message()).contains("不一致");
        verify(sellerProductService, never()).create(any());
    }

    @Test
    void 分组键为空_该组失败() {
        SellerProductImportResultView result = service.importCsv(new SellerProductImportRequest(
                csv(line("", "无分组商品", "500", "10.00", "1", "默认"))));

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().get(0).message()).contains("分组键不能为空");
        verify(sellerProductService, never()).create(any());
    }

    @Test
    void 表头不匹配_整体400() {
        assertThatThrownBy(() -> service.importCsv(new SellerProductImportRequest(
                "商品标题,售价\r\n猫粮,10.00\r\n")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("表头");
    }

    @Test
    void 空内容_整体400() {
        assertThatThrownBy(() -> service.importCsv(new SellerProductImportRequest("  ")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("导入内容为空");
    }

    private static String csv(String... rows) {
        return ProductCsv.HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n";
    }

    private static String line(String group, String title, String categoryId,
                               String price, String stock, String specName) {
        return ProductCsv.row(List.of(group, title, "", categoryId, "", "https://img/main.png",
                "", "", "", "", "", "", specName, price, stock, ""));
    }

    private static SellerProductView view(long id, String title) {
        return new SellerProductView(id, title, "https://img/main.png", "ON_SALE", "在售",
                "APPROVED", "已通过", null, 101L, 1000L, 1000L, 0, 0d, 0,
                1, 1, null, null);
    }
}
