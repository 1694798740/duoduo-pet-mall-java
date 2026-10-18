/**
 * 首页。
 *
 * 数据来源：GET /api/home —— 一次返回首屏所需的全部聚合内容
 * （类目树 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选），刻意不做多次往返。
 *
 * 与后端契约的注意点：
 *  · 推荐是否「按宠物档案排序」由服务端决定；前端只负责把档案展示出来并给建档入口
 *  · brands 在 HomeView 里是 BrandView 对象数组（Node 版是裸字符串数组）
 */

import React, { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { catalogApi, productApi } from '@duoduo/api-sdk/endpoints';
import type { HomeView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, LoadingBlock, ListingGrid, ProductGrid, SupplyTag } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

export const HomePage: React.FC = () => {
  const { user, pet } = useAuth();
  const navigate = useNavigate();
  const [quick, setQuick] = useState('');

  const fetcher = useCallback(() => productApi.home(), []);
  const { data, loading, error, reload } = useAsync<HomeView>(fetcher, true, [user?.id]);

  // 类目树单独拉一次：聚合接口若裁剪字段，首页类目区不会跟着消失
  const categoriesFetcher = useCallback(() => catalogApi.categories(), []);
  const { data: categories } = useAsync(categoriesFetcher);

  if (loading && !data) return <LoadingBlock text="正在准备首页…" />;

  if (error && !data) {
    return (
      <Alert kind="error">
        首页加载失败：{error}
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8 }} onClick={reload}>
          重试
        </button>
      </Alert>
    );
  }

  const home = data;
  const tree = categories ?? home?.categories ?? [];
  const picks = (home?.speciesPicks ?? []).filter((pick) => pick.items.length > 0);

  return (
    <div>
      <section className="hero">
        <div className="hero-deco">🐾</div>

        {user ? (
          pet ? (
            <div className="hero-pet">
              🐱 当前档案：<strong>{pet.name}</strong>
              {pet.breed ? ` · ${pet.breed}` : ''}
              {pet.weightKg ? ` · ${pet.weightKg}kg` : ''}
              {pet.allergens.length ? ` · 过敏：${pet.allergens.join('/')}` : ''}
              <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                管理
              </Link>
            </div>
          ) : (
            <div className="hero-pet">
              🐾 还没有宠物档案，建档后推荐会更懂你家毛孩子
              <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                去建档
              </Link>
            </div>
          )
        ) : (
          <div className="hero-pet">
            🐾 登录后可建立宠物档案，并按年龄与过敏原筛选商品
            <Link to="/login" className="text-brand" style={{ marginLeft: 6 }}>
              去登录
            </Link>
          </div>
        )}

        <h1>自营保真 × 个人闲置担保交易</h1>
        <p>
          自营商品正品直发，个人闲置低价流转；二手订单走平台资金托管——
          货款先冻结在平台，确认收货后才结算给卖家，买得放心、卖得安心。
        </p>
        <div className="hero-actions">
          <Link className="btn btn-primary" to="/search">
            开始逛商品
          </Link>
          <Link className="btn btn-ghost" to="/listings">
            看二手闲置
          </Link>
        </div>

        <form
          className="row"
          style={{ marginTop: 18, maxWidth: 460 }}
          onSubmit={(e) => {
            e.preventDefault();
            navigate(
              quick.trim() ? `/search?keyword=${encodeURIComponent(quick.trim())}` : '/search',
            );
          }}
        >
          <input
            className="input"
            value={quick}
            onChange={(e) => setQuick(e.target.value)}
            placeholder="试试搜「幼猫粮」「豆腐猫砂」「牵引绳」"
          />
          <button className="btn btn-accent" type="submit">
            搜索
          </button>
        </form>
      </section>

      {tree.length ? (
        <section className="section" style={{ marginTop: 24 }}>
          <div className="section-head">
            <h2 className="section-title">🗂️ 按分类逛</h2>
            <Link className="small muted" to="/search">
              全部分类 →
            </Link>
          </div>
          <div className="grid grid-4">
            {tree.slice(0, 8).map((cat) => (
              <Link key={cat.id} className="card card-pad" to={`/search?categoryId=${cat.id}`}>
                <div className="row-between">
                  <strong>
                    {cat.name}
                  </strong>
                  <span className="small muted">{cat.children.length} 个子类</span>
                </div>
                {cat.children.length ? (
                  <div className="small muted-2" style={{ marginTop: 6 }}>
                    {cat.children
                      .slice(0, 4)
                      .map((c) => c.name)
                      .join(' · ')}
                  </div>
                ) : null}
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <section className="section" style={{ marginTop: 24 }}>
        <div className="section-head">
          <h2 className="section-title">
            ✨ 为你推荐
            {pet ? <span className="section-sub">已结合「{pet.name}」的档案</span> : null}
          </h2>
          <Link className="small muted" to="/search">
            更多 →
          </Link>
        </div>
        {home?.recommended?.length ? (
          <ProductGrid items={home.recommended} />
        ) : (
          <Alert kind="info">暂无推荐商品</Alert>
        )}
      </section>

      {home?.freshArrivals?.length ? (
        <section className="section">
          <div className="section-head">
            <h2 className="section-title">🆕 新品上架</h2>
            <span className="section-sub">自营备货 · 正品保障 · 统一发货</span>
          </div>
          <ProductGrid items={home.freshArrivals} />
        </section>
      ) : null}

      {home?.secondhand?.length ? (
        <section className="section">
          <div className="section-head">
            <h2 className="section-title">♻️ 闲置新鲜上架</h2>
            <span className="section-sub">
              平台担保交易 · 确认收货后货款才结算给卖家
              <SupplyTag supplyType="secondhand" />
            </span>
          </div>
          <ListingGrid items={home.secondhand} />
        </section>
      ) : null}

      {picks.map((pick) => (
        <section className="section" key={pick.species}>
          <div className="section-head">
            <h2 className="section-title">🐾 {pick.label}</h2>
            <Link className="small muted" to={`/search?species=${pick.species}`}>
              更多 →
            </Link>
          </div>
          <ProductGrid items={pick.items} />
        </section>
      ))}

      {home?.brands?.length ? (
        <section className="section">
          <div className="section-head">
            <h2 className="section-title">🏷️ 热门品牌</h2>
          </div>
          <div className="row row-wrap">
            {home.brands.map((brand) => (
              <Link
                key={brand.id}
                className="tag"
                to={`/search?brandId=${brand.id}`}
                title={brand.story ?? undefined}
              >
                {brand.name}
                {brand.country ? <span className="muted"> · {brand.country}</span> : null}
              </Link>
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
};
