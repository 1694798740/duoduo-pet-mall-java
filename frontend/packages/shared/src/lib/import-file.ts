/**
 * 导入文件读取（2026-10-28 · 商品 xlsx 模板）。
 *
 * <h2>为什么 CSV 读文本、xlsx 读 Base64</h2>
 * CSV 是「行文本」，服务端按 RFC 4180 解析即可，前端读成字符串最直接；
 * xlsx 是 zip 二进制，读成文本会得到乱码，必须原样把字节传到服务端。
 * 请求体是 JSON，字节只能 Base64 —— 2MB 的文件膨胀成 2.7MB，仍在网关限制内。
 *
 * <h2>为什么不引入 SheetJS 在浏览器里解析</h2>
 * 导入的校验（表头、类目是否存在、分组一致性）全在服务端，浏览器解析只多一份
 * 可能与服务端不一致的实现。这里只负责「把字节安全送到服务端」。
 */

/** 上传给导入接口的载荷：{@code content} 是 CSV 文本或 xlsx 的 Base64 */
export interface ImportFilePayload {
  content: string;
  fileName: string;
}

/** 按扩展名判断是否 xlsx；MIME 在 Windows 上不可靠，只认后缀 */
export const isXlsxFile = (name: string): boolean => /\.xlsx$/i.test(name);

/** 读取用户选择的导入文件 */
export async function readImportFile(file: File): Promise<ImportFilePayload> {
  if (!isXlsxFile(file.name)) {
    return { fileName: file.name, content: await file.text() };
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  return { fileName: file.name, content: toBase64(bytes) };
}

/**
 * 字节 → Base64。
 *
 * <p>逐块调用 {@code String.fromCharCode}：一次性展开 2MB 数组会触发
 * 「Maximum call stack size exceeded」，分块（32KB）既不会爆栈也足够快。
 */
function toBase64(bytes: Uint8Array): string {
  const chunkSize = 0x8000;
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}
