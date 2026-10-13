package com.duoduo.user.support;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.web.storage.LocalFsObjectStorage;
import com.duoduo.common.web.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 入驻资质材料的**私有**存储（E12）。
 *
 * <h2>为什么不复用公开的 /uploads/**（A15）</h2>
 * 公开通道的设计前提是「文件名是不可枚举的 UUID，内容无归属语义」——
 * 这个前提对**商品图片**成立，对**资质材料**完全不成立：营业执照 + 法人身份证 +
 * 银行账户是足以开出一家假公司的一整套信息，"URL 难猜"挡不住链接从浏览器
 * 历史 / 分享 / 日志里泄露。私有通道的原则：**每一次读取都过一遍授权判断**，
 * 存储位置不挂任何公开 nginx 路径（compose 里独立卷，与 fulfilment 的公开盘分开）。
 *
 * <h2>按租户分目录</h2>
 * 逻辑 key 是 {@code materials/{merchantId}/{storedName}} —— 一家店一个目录，
 * 将来做「租户数据导出 / 注销即焚」（P3）时按目录整删即可，不必全盘扫描文件名。
 *
 * <h2>路径安全</h2>
 * 读接口只接受服务端自己生成的文件名（UUID + 白名单扩展），正则一层就把
 * 路径穿越（{@code ../}）拒掉 —— 与公开通道 UploadController 的 SERVABLE 同款口径。
 * 存储层（{@link LocalFsObjectStorage}）另有「normalize 后必须仍在根目录内」的第二道。
 *
 * <h2>介质（S9）</h2>
 * 落盘位置改由 {@link ObjectStorage} 决定：默认本地盘（行为与改造前逐字节一致），
 * 配 {@code duoduo.storage.type=cos} 后落到**私有桶**。
 * ★ 本类拿到的是 {@code privateObjectStorage} 这个 Bean ——
 * <b>私有语义靠"用哪个桶"保证</b>，所以这里绝不允许退回公开桶。
 */
@Component
public class MerchantMaterialStorage {

    /** 允许的扩展名 → Content-Type。资质材料在此放宽到 PDF（承诺书 / 许可证常见扫描件形态） */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "pdf", "application/pdf");

    /** 读接口只接受服务端生成的文件名（UUID + 白名单扩展），路径穿越在这里死掉 */
    private static final Pattern SERVABLE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp|pdf)$");

    /** 单文件上限：与网关 multipart 总上限（12MB）对齐并留余量 */
    private static final long MAX_BYTES = 6L * 1024 * 1024;

    private final ObjectStorage storage;

    @Autowired
    public MerchantMaterialStorage(@Qualifier("privateObjectStorage") ObjectStorage storage) {
        this.storage = storage;
    }

    /**
     * 兼容构造：直接指定本地根目录。
     *
     * <p>保留它有两个实际用途：① 单测不必起 Spring 容器；② 任何"没有自动装配"的裸调用场景仍可用。
     * 它内部走本地实现，**与 {@code duoduo.storage.type=local} 完全等价**。
     */
    public MerchantMaterialStorage(String dir) {
        this(new LocalFsObjectStorage(dir, ObjectStorage.PREFIX_PRIVATE));
    }

    /** 该扩展名是否可接收（供控制器把校验错误转成可读提示） */
    public static boolean isAllowedExtension(String filename) {
        return contentTypeOf(filename) != null;
    }

    public static String contentTypeOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return CONTENT_TYPES.get(filename.substring(dot + 1).toLowerCase());
    }

    /**
     * 保存一份材料，返回（服务端可见的）相对读取路径。
     *
     * @return {@code /api/merchant-materials/{merchantId}/{storedName}}
     */
    public Stored store(long merchantId, String originalName, byte[] content) {
        if (content == null || content.length == 0) {
            throw BizException.businessRule("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw BizException.businessRule("单个材料文件不能超过 6MB");
        }
        String contentType = contentTypeOf(originalName);
        if (contentType == null) {
            throw BizException.businessRule("仅支持 jpg / png / webp / pdf 格式");
        }
        String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
        String storedName = UUID.randomUUID() + "." + ext;
        try {
            storage.put(ObjectStorage.PREFIX_PRIVATE + merchantId + "/" + storedName, content, contentType);
        } catch (BizException e) {
            throw e;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "材料保存失败", ex);
        }
        return new Stored(storedName, originalName,
                "/api/merchant-materials/" + merchantId + "/" + storedName, contentType);
    }

    /**
     * 读取一份材料。**调用方必须先做授权判断**（店主本人 / 运营），本方法只管存储。
     *
     * @throws BizException 文件名形状非法（路径穿越企图）或材料不存在
     */
    public ReadResult read(long merchantId, String storedName) {
        if (storedName == null || !SERVABLE.matcher(storedName).matches()) {
            throw BizException.notFound("材料");
        }
        // ★ 租户隔离靠 key 里的 merchantId 段实现：A 店的 storedName 拼到 B 店的 key 上，
        //   取到的就是"不存在"，不可能顺着文件名落到 A 店目录 —— 与改造前同一口径。
        byte[] content;
        try {
            content = storage.get(ObjectStorage.PREFIX_PRIVATE + merchantId + "/" + storedName);
        } catch (BizException e) {
            throw e;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "材料读取失败", ex);
        }
        if (content == null) {
            throw BizException.notFound("材料");
        }
        return new ReadResult(content,
                CONTENT_TYPES.get(storedName.substring(storedName.lastIndexOf('.') + 1).toLowerCase()));
    }

    /** 存储结果：storedName 落库用，readPath 是给前端的鉴权读取路径 */
    public record Stored(String storedName, String originalName, String readPath, String contentType) {
    }

    /**
     * 读取结果：**直接给字节**而不是文件路径。
     *
     * <p>S9 之前这里是 {@code Path file} —— 那等于把"本地盘"暴露在契约里：
     * 换成对象存储后调用方拿不到 Path，接口就得跟着改；改成字节之后，
     * 存储介质怎么变、调用方都不用动。材料上限 6MB，整体读进内存是可接受的
     * （与改造前 {@code Files.readAllBytes} 同量级）。
     */
    public record ReadResult(byte[] content, String contentType) {
    }
}
