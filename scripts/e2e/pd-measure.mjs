import { chromium } from 'playwright';

const UI = process.env.UI_BASE ?? 'http://localhost:5173';
const browser = await chromium.launch();

for (const width of [2560, 1440, 1024, 768]) {
  const page = await browser.newPage({ viewport: { width, height: 950 } });
  await page.goto(`${UI}/product/1001`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  const m = await page.evaluate(() => {
    const box = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return `${Math.round(r.width)}px`;
    };
    return {
      container: box('.container'),
      pdLayout: box('.pd-layout'),
      detailImgs: box('.pd-detail-imgs'),
      cols2: box('.pd-cols-2'),
      overflowX: document.documentElement.scrollWidth > window.innerWidth,
    };
  });
  console.log(`viewport=${width}  container=${m.container}  pd-layout=${m.pdLayout}  介绍图区=${m.detailImgs}  规格双栏=${m.cols2}  横向溢出=${m.overflowX}`);
  await page.screenshot({ path: `.workbuddy/shots/pd-${width}.png` });
  await page.close();
}
await browser.close();
console.log('截图已保存到 .workbuddy/shots/pd-*.png');
