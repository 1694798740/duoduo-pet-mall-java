package com.duoduo.product.support;

import com.duoduo.product.entity.BrandEntity;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.BrandMapper;
import com.duoduo.product.mapper.CategoryMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>名称批量查询器对 null id 的契约</b>（E27 · 2026-10-09 由一处真实 500 反推出来）。
 *
 * <h2>为什么要为一个「没查到」的返回值专门写测试</h2>
 * 品牌是<b>可选</b>字段（第三方卖家上架不填品牌时 {@code brand_id} 就是 NULL），
 * 所以调用方拿 {@code null} 去 {@code brandNames.get(null)} 是<b>正常路径</b>，不是异常。
 * 一旦「没有任何 id 可查」时返回 {@code Map.of()}（不可变 Map），
 * 那次 {@code get(null)} 会直接抛 {@code NullPointerException} —— 不是返回 null，是抛异常。
 *
 * <p>后果不是某个字段空着，而是<b>整条读路径 500</b>：商品列表 / 商品详情 /
 * AI 候选召回合用的 {@code toCards} 都会炸。而且它有个极强的伪装：
 * 只要同一页里有<b>任意一条</b>商品带品牌，返回的就是允许 null key 的 HashMap，
 * 一切正常 —— 于是「种子数据里每条商品都有品牌」的环境<b>永远测不出来</b>。
 *
 * <p>本测试同时钉住两件事：
 * <ol>
 *   <li>「一个 id 都没查到」时返回的 Map，{@code get(null)} 必须安全返回 null（不抛）；</li>
 *   <li>{@code null} id 在批量查询前就被过滤掉，不会变成 {@code WHERE id IN (null)} 这种脏查询。</li>
 * </ol>
 */
class CatalogLookupTest {

    /**
     * {@code Map.of()} 在 {@code get(null)} 上是抛异常而不是返回 null ——
     * 这是本测试存在的**唯一**理由，先把这条 JDK 行为钉住。
     * 将来若有人把 {@code names()} 的空结果改回 {@code Map.of()}，
     * 上面那条断言会红，并且能一眼看懂「为什么不能这么写」。
     */
    @Test
    void 不可变空Map的get_null_会抛异常() {
        assertThatCode(() -> Map.of().get(null))
                .as("Map.of() 对 null key 抛 NPE —— 这就是那次线上 500 的机制")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 全部为null的id_查品牌名不得抛异常() {
        BrandMapper brandMapper = mock(BrandMapper.class);
        CatalogLookup lookup = new CatalogLookup(mock(CategoryMapper.class), brandMapper);

        // 一页商品全都没有品牌 —— 正是「纯第三方卖家、都没填品牌」的真实形态
        List<Long> ids = new ArrayList<>(Arrays.asList(null, null));

        assertThatCode(() -> lookup.brandNames(ids).get(null))
                .as("无品牌是可选字段的正常取值，不能因为「一个都没查到」就抛 NPE")
                .doesNotThrowAnyException();
        assertThat(lookup.brandNames(ids).get(null)).isNull();

        // 一个 id 都没剩下，就不该去查库（脏查询 IN (null) 只会白跑一趟）
        org.mockito.Mockito.verify(brandMapper, org.mockito.Mockito.never()).selectBatchIds(any());
    }

    @Test
    void null_id被过滤_非null照常映射() {
        BrandMapper brandMapper = mock(BrandMapper.class);
        BrandEntity brand = new BrandEntity();
        brand.setId(7L);
        brand.setName("皇家");
        when(brandMapper.selectBatchIds(any())).thenReturn(List.of(brand));

        CatalogLookup lookup = new CatalogLookup(mock(CategoryMapper.class), brandMapper);

        Map<Long, String> names = lookup.brandNames(new ArrayList<>(Arrays.asList(7L, null)));

        assertThat(names.get(7L)).isEqualTo("皇家");
        // 同一个 Map 上，存在的 key 与「没有品牌」的 key 都能安全取值 —— 调用方不必分两种情况写
        assertThat(names.get(null)).isNull();
    }

    @Test
    void null集合与空集合都安全() {
        CatalogLookup lookup = new CatalogLookup(mock(CategoryMapper.class), mock(BrandMapper.class));
        assertThat(lookup.brandNames(null)).isEmpty();
        assertThat(lookup.brandNames(List.of())).isEmpty();
    }

    @Test
    void 类目名与品牌名同一套契约() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        CategoryEntity category = new CategoryEntity();
        category.setId(500L);
        category.setName("宠物主粮");
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(category));

        CatalogLookup lookup = new CatalogLookup(categoryMapper, mock(BrandMapper.class));

        assertThat(lookup.categoryNames(List.of(500L)).get(500L)).isEqualTo("宠物主粮");
        // 类目 id 在库里是 NOT NULL，但「查不到」与「传 null」在调用方看来必须同形，
        // 否则同一种写法在类目上安全、在品牌上炸 —— 那才是最容易被复制传播的坑
        assertThatCode(() -> lookup.categoryNames(new ArrayList<>(List.of())).get(null))
                .doesNotThrowAnyException();
    }
}
