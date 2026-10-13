package com.duoduo.common.web.storage;

/**
 * 对象存储抽象（S9）。
 *
 * <h2>为什么要有这一层</h2>
 * 在它出现之前，图片与资质材料两条链路都是**直接操作本地盘**：
 * {@code UploadController} 用 {@code Files.copy}/{@code FileSystemResource}，
 * {@code MerchantMaterialStorage} 是具体类、{@code read()} 直接返回 {@link java.nio.file.Path}。
 * 结果是「换存储介质」无法靠改配置完成 —— 必须先补一层接口。
 *
 * <h2>接口设计的两条硬约束（都是踩过的坑）</h2>
 * <ol>
 *   <li><b>签名里不许出现 {@code Path} / {@code File}</b>：那等于把"本地盘"焊进抽象，
 *       换成桶以后所有调用方还得改一遍，抽象就白抽了。这里只有 {@code String key} 与 {@code byte[]}。</li>
 *   <li><b>key 用"逻辑路径"，由实现决定怎么落到介质上</b>：公开图是 {@code uploads/<uuid>.<ext>}、
 *       资质材料是 {@code materials/<merchantId>/<storedName>}。
 *       这个形状**刻意与 COS 桶内的对象 key 一致** —— 于是把
 *       {@code UPLOADS_PUBLIC_BASE_URL} 指向桶域名以后，返回给前端的图片 URL
 *       在原实现（本地盘 + 应用自己出图）与新实现（对象存储直出）下**完全一样**，
 *       历史 URL 一条都不会失效。</li>
 * </ol>
 *
 * <h2>实现</h2>
 * <ul>
 *   <li>{@link LocalFsObjectStorage} —— 默认实现与回退。行为与"没有对象存储"时**逐字节等价**。</li>
 *   <li>{@link CosObjectStorage} —— 腾讯云 COS，零第三方依赖（JDK {@code HttpClient} + {@code HmacSHA1}）。</li>
 * </ul>
 * 由 {@link ObjectStorageAutoConfiguration} 按 {@code duoduo.storage.type} 选择；
 * <b>默认必须是 local</b>：新存储层一旦有问题，图片链路不能整体不可用。
 */
public interface ObjectStorage {

    /**
     * 公开图片的逻辑前缀 —— 与对外 URL 形态 {@code <base>/uploads/<uuid>.<ext>} **一对一绑定**。
     * 改动它等于改动图片 URL，历史链接会全部失效，所以它只允许出现在这里一处。
     */
    String PREFIX_PUBLIC = "uploads/";

    /**
     * 私有资质材料的逻辑前缀 —— 与 {@code /api/merchant-materials/{merchantId}/{storedName}}
     * 的鉴权读取路径对应。
     */
    String PREFIX_PRIVATE = "materials/";

    /**
     * 写入（覆盖）一个对象。
     *
     * @param key         逻辑路径，形如 {@code uploads/<uuid>.jpg}、{@code materials/123/<uuid>.pdf}
     * @param content     内容
     * @param contentType MIME，写入对象存储时会作为对象的 Content-Type 落库
     */
    void put(String key, byte[] content, String contentType);

    /**
     * 读取。**不存在返回 {@code null}**（而不是抛异常）——
     * 读图端点要据此返回 404，用异常表达"没有"会让正常流程变成异常流。
     */
    byte[] get(String key);

    boolean exists(String key);

    /** 删除。对象不存在时**不报错**（幂等）—— 清理脚本会重复调用。 */
    void delete(String key);

    /** 后端标识：{@code local} / {@code cos}。供日志与自检断言（"到底走的哪条路"）。 */
    String backend();
}
