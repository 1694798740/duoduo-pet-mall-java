package com.duoduo.product.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端商品管理单测（第五十八轮 · P1-4，也是商品域第一批单测）。
 *
 * <h2>为什么先给「编辑」写测试</h2>
 * 编辑与上下架共用同一条归属判定，但两者是<b>两个入口</b>：
 * 只在一处写判定的话，另一处就是「看起来有权限校验、实际形同虚设」——
 * 这类缺陷只在越权请求时出现，普通冒烟全绿。这里把「越权 403 / 不存在 404」
 * 分别断言到两个入口上，将来谁抽走了共享判定，测试立刻转红。
 *
 * <h2>为什么钉住字段三态</h2>
 * {@code null} = 不改、{@code ""} = 清空、有值 = 替换。混淆前两者有两种后果：
 * 把「不改」当「清空」→ 用户保存一次主图就没了；把「清空」当「不改」→ 用户永远删不掉主图。
 * 两者都不会报错，只能靠断言守住。
 */
class SellerProductServiceTest {

    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final SkuMapper skuMapper = mock(SkuMapper.class);
    private final SellerProductService service = new SellerProductService(spuMapper, skuMapper);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private static LoginUser seller(long id) {
        return new LoginUser(id, "13800000000", "SELLER", false);
    }

    private static SpuEntity spu(long id, long sellerId, String title, String image) {
        SpuEntity spu = new SpuEntity();
        spu.setId(id);
        spu.setSellerId(sellerId);
        spu.setTitle(title);
        spu.setMainImage(image);
        spu.setStatus("ON_SALE");
        spu.setSalesCount(0);
        spu.setMinPrice(1000L);
        spu.setMaxPrice(2000L);
        return spu;
    }

    @Test
    void 卖家可以改自己商品的标题与主图() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "旧标题", null);
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        SellerProductView view = service.update(9001L, "新标题", "https://img/1.png");

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.mainImage()).isEqualTo("https://img/1.png");
        verify(spuMapper).updateById(spu);
    }

    @Test
    void 编辑别人的商品_403而不是404() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));

        // 刻意区分「不存在」与「存在但不是你的」：统一 404 等于帮越权探测消除了信号
        assertThatThrownBy(() -> service.update(9001L, "新标题", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }

    @Test
    void 上下架别人的商品同样403_与编辑共用归属判定() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 202L, "别人的商品", null));

        assertThatThrownBy(() -> service.changeStatus(9001L, "OFF_SHELF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前账号");
    }

    @Test
    void 非卖家角色进不了商家端() {
        UserContext.set(new LoginUser(101L, "13800000000", "USER", false));

        assertThatThrownBy(() -> service.update(9001L, "新标题", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是卖家");
    }

    @Test
    void 商品不存在是404而不是403() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(9001L, "新标题", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 字段三态_不传不改_空串清空() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        // 只传标题：主图必须原样保留（传 null = 不改）
        SellerProductView onlyTitle = service.update(9001L, "新标题", null);
        assertThat(onlyTitle.mainImage()).isEqualTo("https://img/old.png");

        // 主图传空串 = 清空
        SellerProductView cleared = service.update(9001L, null, "  ");
        assertThat(cleared.mainImage()).isNull();

        // 标题不允许清空
        assertThatThrownBy(() -> service.update(9001L, "   ", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("标题不能为空");
    }

    @Test
    void 一个字段都不传直接400() {
        UserContext.set(seller(101L));
        when(spuMapper.selectById(9001L)).thenReturn(spu(9001L, 101L, "原标题", null));

        assertThatThrownBy(() -> service.update(9001L, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有需要修改的字段");
    }

    @Test
    void 值没变就不写库() {
        UserContext.set(seller(101L));
        SpuEntity spu = spu(9001L, 101L, "原标题", "https://img/old.png");
        when(spuMapper.selectById(9001L)).thenReturn(spu);

        service.update(9001L, "原标题", "https://img/old.png");

        // 无变化不发 UPDATE：省一次写，也避免 updated_at 被无意义地刷新
        verify(spuMapper, never()).updateById(any(SpuEntity.class));
    }
}
