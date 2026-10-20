/**
 * 网页端「在地图上选点」弹窗（A5）。
 *
 * 为什么需要它：定位只能拿到一个点，实际收货地址常常是「小区几号门 / 隔壁楼」，
 * 让用户在地图上拖动地标是最直观的修正方式（外卖 App 的做法）。
 *
 * 实现要点
 *  · 高德 JSAPI 2.0 用**动态 script 注入**，只有第一次打开弹窗才加载（不拖慢首屏）；
 *  · Key 来自构建期注入的 `VITE_AMAP_JS_KEY`（仓库根 .env，gitignored），代码里没有字面量；
 *  · 地图与地标的坐标是 **GCJ-02**（高德体系），回填时按 gcj02 调后端逆地理，**不能再转**；
 *  · 加载失败（无 Key / 网络 / Key 域名白名单未配）不阻断：给出明确原因，让用户回到
 *    附近候选或手动填写 —— 定位功能整体仍然可用。
 */

import React, { useEffect, useRef, useState } from 'react';
import { geoApi } from '@duoduo/api-sdk/endpoints';
import { Alert, Modal } from '@duoduo/shared/components/ui';

/** 高德 JSAPI 的最小类型（只用到 Map / Marker 两个能力，不引第三方 d.ts） */
type AMapNS = any;

let amapPromise: Promise<AMapNS> | null = null;

/**
 * 单例加载：并发打开弹窗只注入一次 script。
 *
 * Key 与安全密钥**从服务端按登录态取**（`/api/geo/jsapi-config`），而不是打进前端产物：
 * 高德机制要求这两个值必须交给浏览器，但没必要让它们躺在 dist 里 ——
 * 放服务端后换 Key 只需改环境变量重启，且未登录拿不到。
 */
function loadAMap(): Promise<AMapNS> {
  if (typeof window !== 'undefined' && (window as unknown as { AMap?: AMapNS }).AMap) {
    return Promise.resolve((window as unknown as { AMap: AMapNS }).AMap);
  }
  if (amapPromise) return amapPromise;

  amapPromise = geoApi
    .jsapiConfig()
    .then((cfg) => {
      if (!cfg || !cfg.configured || !cfg.key) throw new Error('NO_KEY');
      if (cfg.securityCode) {
        (window as unknown as { _AMapSecurityConfig?: unknown })._AMapSecurityConfig = {
          securityJsCode: cfg.securityCode,
        };
      }
      return injectScript(cfg.key);
    })
    .catch((err) => {
      amapPromise = null;
      throw err;
    });
  return amapPromise;
}

/** 真正注入 JSAPI 脚本（拆出来便于上面统一处理"取配置失败"） */
function injectScript(key: string): Promise<AMapNS> {
  return new Promise<AMapNS>((resolve, reject) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.onload = () => {
      const amap = (window as unknown as { AMap?: AMapNS }).AMap;
      if (amap) resolve(amap);
      else reject(new Error('LOAD_FAILED'));
    };
    script.onerror = () => reject(new Error('LOAD_FAILED'));
    document.head.appendChild(script);
    // 不能无限等：用户点开弹窗是来选地址的，10 秒还没起来就让他回退到候选/手填
    window.setTimeout(() => reject(new Error('TIMEOUT')), 10_000);
  });
}

export interface MapPickerProps {
  open: boolean;
  /** 初始中心点（GCJ-02） */
  center: { lng: number; lat: number } | null;
  onClose: () => void;
  /** 确认选点（GCJ-02） */
  onPick: (lng: number, lat: number) => void;
}

export const MapPicker: React.FC<MapPickerProps> = ({ open, center, onClose, onPick }) => {
  const boxRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const markerRef = useRef<any>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const [picked, setPicked] = useState<{ lng: number; lat: number } | null>(center);

  useEffect(() => {
    if (!open || !center) return undefined;
    let cancelled = false;
    setStatus('loading');
    setErrorMsg('');
    setPicked(center);

    loadAMap()
      .then((AMap) => {
        if (cancelled || !boxRef.current) return;
        const map = new AMap.Map(boxRef.current, {
          zoom: 17,
          center: [center.lng, center.lat],
          resizeEnable: true,
        });
        const marker = new AMap.Marker({
          position: [center.lng, center.lat],
          draggable: true,
          cursor: 'move',
          map,
        });
        // 拖动地标 → 更新选中坐标
        marker.on('dragend', () => {
          const p = marker.getPosition();
          setPicked({ lng: p.getLng(), lat: p.getLat() });
        });
        // 点地图任意位置 → 地标跟着走（比只能拖更省事）
        map.on('click', (e: any) => {
          marker.setPosition(e.lnglat);
          setPicked({ lng: e.lnglat.getLng(), lat: e.lnglat.getLat() });
        });
        mapRef.current = map;
        markerRef.current = marker;
        setStatus('ready');
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setStatus('failed');
        setErrorMsg(
          err.message === 'NO_KEY'
            ? '未配置网页地图 Key（VITE_AMAP_JS_KEY），无法显示地图'
            : err.message === 'TIMEOUT'
              ? '地图加载超时（网络较慢或被拦截），可先用下方「附近位置」选择'
              : '地图加载失败（Key 未授权当前域名，或网络不可达），可先用下方「附近位置」选择',
        );
      });

    return () => {
      cancelled = true;
      try {
        mapRef.current?.destroy?.();
      } catch {
        /* 卸载失败不影响功能 */
      }
      mapRef.current = null;
      markerRef.current = null;
    };
  }, [open, center]);

  return (
    <Modal
      open={open}
      title="在地图上选点"
      onClose={onClose}
      footer={
        <>
          <button className="btn btn-ghost" type="button" onClick={onClose}>
            取消
          </button>
          <button
            className="btn btn-primary"
            type="button"
            disabled={status !== 'ready' || !picked}
            onClick={() => picked && onPick(picked.lng, picked.lat)}
          >
            使用这个位置
          </button>
        </>
      }
    >
      <div className="small" style={{ marginBottom: 8, color: 'var(--text-3)' }}>
        拖动红色地标，或直接点地图上的位置；确认后会自动回填省市区与详细地址。
      </div>
      {status === 'failed' ? (
        <Alert kind="warn">{errorMsg}</Alert>
      ) : null}
      {/* 地图容器必须有明确高度：高德按容器尺寸初始化，高度为 0 会渲染成空白 */}
      <div
        ref={boxRef}
        style={{ width: '100%', height: 360, borderRadius: 12, overflow: 'hidden', background: 'var(--surface-2)' }}
      />
      {status === 'loading' ? (
        <div className="small" style={{ marginTop: 8 }}>地图加载中…</div>
      ) : null}
      {picked && status === 'ready' ? (
        <div className="small" style={{ marginTop: 8, color: 'var(--text-3)' }}>
          当前选中：{picked.lng.toFixed(6)}, {picked.lat.toFixed(6)}（可再用下方「附近位置」微调）
        </div>
      ) : null}
    </Modal>
  );
};
