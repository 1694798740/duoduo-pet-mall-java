package com.duoduo.common.web.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储选型（S9）：按 {@code duoduo.storage.type} 决定走本地盘还是 COS。
 *
 * <h2>★ 默认值是 {@code local}，这是刻意的</h2>
 * 与 {@code SchedLeaderLock}、{@code DUODUO_API_METRICS_ENABLED} 等处同一个原则：
 * <b>新组件的默认值必须让系统行为和"没有这个组件"时一致</b>。
 * 因此引入本层之后，只要不动配置，图片链路的行为与改造前**逐字节相同** ——
 * 这样"接入对象存储"这件事不会与"这一轮验收"互相污染。
 *
 * <h2>配置残缺时回落，而不是启动失败</h2>
 * {@code type=cos} 但五个键没配全时，**回落 local + 打 WARN（点名缺了哪些键）**。
 * 不选"启动即失败"的理由：这两个 Bean 被上传链路强依赖，构造失败会让**整个服务起不来**，
 * 而我们真正想要的是"图片仍能正常上传（落在本地盘）"，同时把配置问题显式喊出来。
 * ★ 但**不许静默**：WARN 里必须列出缺失的键名 —— 否则表现就是
 * "配了桶、图却落在本地盘"，属于最难查的一类（两处都"看起来正常"）。
 *
 * <h2>为什么是两个 Bean</h2>
 * 公开图片与资质材料的**存储介质与权限模型完全不同**（前者公有读、后者私有），
 * 所以是两个独立的桶 / 两个独立的根目录，不能是一个 Bean 换参数。
 * 用 {@code @Qualifier} 区分，名字直接说明用途。
 */
@AutoConfiguration
public class ObjectStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageAutoConfiguration.class);

    /**
     * 逻辑前缀 —— 常量本体在 {@link ObjectStorage#PREFIX_PUBLIC} / {@link ObjectStorage#PREFIX_PRIVATE}，
     * 这里只是本类内部的引用别名（两个控制器与本地实现读的是同一份常量，不会各写一遍写歪）。
     */
    public static final String PREFIX_PUBLIC = ObjectStorage.PREFIX_PUBLIC;
    public static final String PREFIX_PRIVATE = ObjectStorage.PREFIX_PRIVATE;

    @Bean("publicObjectStorage")
    public ObjectStorage publicObjectStorage(
            @Value("${duoduo.storage.type:local}") String type,
            @Value("${duoduo.uploads.dir:./data/uploads}") String localDir,
            @Value("${duoduo.storage.cos.secret-id:}") String secretId,
            @Value("${duoduo.storage.cos.secret-key:}") String secretKey,
            @Value("${duoduo.storage.cos.region:}") String region,
            @Value("${duoduo.storage.cos.bucket-public:}") String bucket) {
        return build("public", PREFIX_PUBLIC, localDir, type, secretId, secretKey, region, bucket);
    }

    @Bean("privateObjectStorage")
    public ObjectStorage privateObjectStorage(
            @Value("${duoduo.storage.type:local}") String type,
            @Value("${duoduo.materials.dir:/data/merchant-materials}") String localDir,
            @Value("${duoduo.storage.cos.secret-id:}") String secretId,
            @Value("${duoduo.storage.cos.secret-key:}") String secretKey,
            @Value("${duoduo.storage.cos.region:}") String region,
            @Value("${duoduo.storage.cos.bucket-private:}") String bucket) {
        return build("private", PREFIX_PRIVATE, localDir, type, secretId, secretKey, region, bucket);
    }

    private ObjectStorage build(String role, String prefix, String localDir,
                                String type, String secretId, String secretKey,
                                String region, String bucket) {
        boolean wantCos = "cos".equalsIgnoreCase(type == null ? "" : type.trim());
        if (!wantCos) {
            log.info("对象存储后端 = local（{}-role，root={}）", role, localDir);
            return new LocalFsObjectStorage(localDir, prefix);
        }

        List<String> missing = new ArrayList<>();
        if (secretId.isBlank()) missing.add("COS_SECRET_ID");
        if (secretKey.isBlank()) missing.add("COS_SECRET_KEY");
        if (region.isBlank()) missing.add("COS_REGION");
        if (bucket.isBlank()) missing.add("COS_BUCKET_PUBLIC/PRIVATE");

        if (!missing.isEmpty()) {
            // ★ 点名缺失项 + 明说"已回落"，让"以为走了桶、其实在本地盘"一眼可见
            log.warn("duoduo.storage.type=cos 但配置不全，已回落本地盘（{}-role）。缺失：{}。"
                            + "此期间上传的图片会落在 {} —— 补齐配置并重启后需重新迁移。",
                    role, String.join(", ", missing), localDir);
            return new LocalFsObjectStorage(localDir, prefix);
        }
        return new CosObjectStorage(role, bucket, region, secretId, secretKey);
    }
}
