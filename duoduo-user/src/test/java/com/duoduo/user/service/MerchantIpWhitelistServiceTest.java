package com.duoduo.user.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistRequest;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistView;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.support.MerchantIpWhitelistPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家 IP 白名单服务单测（E-06）。
 *
 * <p>重点不在「能不能存」，而在<b>三条防线</b>：
 * ① 启用时不允许空名单（否则网关失败关闭会把整家店挡在门外）；
 * ② 当前 IP 不在新名单里时必须显式确认（防「配完就把自己锁在门外」）；
 * ③ 店员无权配置（白名单是店主级安全边界）。
 */
class MerchantIpWhitelistServiceTest {

    private static MerchantEntity store(long id) {
        MerchantEntity s = new MerchantEntity();
        s.setId(id);
        s.setOwnerUserId(9001L);
        s.setIpWhitelistEnabled(0);
        return s;
    }

    private static MerchantIpWhitelistService service(MerchantEntity found,
                                                      MerchantMapper mapper,
                                                      MerchantIpWhitelistPublisher publisher) {
        when(mapper.selectOne(any())).thenReturn(found);
        return new MerchantIpWhitelistService(mapper, publisher);
    }

    @Test
    void 启用但没有规则_拒绝保存() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        MerchantIpWhitelistService svc = service(store(5001L), mapper, publisher);

        assertThatThrownBy(() -> svc.save(9001L,
                new IpWhitelistRequest(true, List.of(), false), "10.0.0.5"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少要有一条规则");
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void 当前IP不在新名单且未确认_拒绝并提示会被锁在门外() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        MerchantIpWhitelistService svc = service(store(5001L), mapper, publisher);

        assertThatThrownBy(() -> svc.save(9001L,
                new IpWhitelistRequest(true, List.of("203.0.113.7"), false), "10.0.0.5"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("10.0.0.5")
                .hasMessageContaining("无法进入商家端");
        verify(publisher, never()).publish(any(), anyBoolean(), anyList());
    }

    @Test
    void 确认锁死后允许保存_并广播到网关() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        when(publisher.publish(any(), anyBoolean(), anyList())).thenReturn(true);
        MerchantIpWhitelistService svc = service(store(5001L), mapper, publisher);

        IpWhitelistView view = svc.save(9001L,
                new IpWhitelistRequest(true, List.of("203.0.113.7"), true), "10.0.0.5");

        assertThat(view.enabled()).isTrue();
        assertThat(view.rules()).containsExactly("203.0.113.7");
        assertThat(view.published()).isTrue();
        verify(mapper).update(isNull(), any());
        verify(publisher).publish(eq(5001L), eq(true), eq(List.of("203.0.113.7")));
    }

    @Test
    void 规则形状非法_400并指出是哪一条() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        MerchantIpWhitelistService svc = service(store(5001L), mapper, publisher);

        assertThatThrownBy(() -> svc.save(9001L,
                new IpWhitelistRequest(true, List.of("localhost"), true), "10.0.0.5"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void 关闭白名单时清空规则并删除网关副本() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        when(publisher.publish(any(), anyBoolean(), anyList())).thenReturn(true);
        MerchantEntity store = store(5001L);
        store.setIpWhitelistEnabled(1);
        store.setIpWhitelist("203.0.113.7");
        MerchantIpWhitelistService svc = service(store, mapper, publisher);

        IpWhitelistView view = svc.save(9001L, new IpWhitelistRequest(false, List.of(), null), "10.0.0.5");

        assertThat(view.enabled()).isFalse();
        assertThat(view.rules()).isEmpty();
        verify(publisher).publish(eq(5001L), eq(false), anyList());
    }

    @Test
    void 不是店主_403且不写库() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        MerchantIpWhitelistService svc = service(null, mapper, publisher);

        assertThatThrownBy(() -> svc.view(9002L, "10.0.0.5"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有店主");
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void Redis不可用时如实返回published为false() {
        MerchantMapper mapper = mock(MerchantMapper.class);
        MerchantIpWhitelistPublisher publisher = mock(MerchantIpWhitelistPublisher.class);
        when(publisher.publish(any(), anyBoolean(), anyList())).thenReturn(false);
        MerchantIpWhitelistService svc = service(store(5001L), mapper, publisher);

        IpWhitelistView view = svc.save(9001L,
                new IpWhitelistRequest(true, List.of("10.0.0.5"), false), "10.0.0.5");

        assertThat(view.published()).as("不能假装成功：页面要提示稍后生效").isFalse();
        assertThat(view.rules()).containsExactly("10.0.0.5");
    }
}
