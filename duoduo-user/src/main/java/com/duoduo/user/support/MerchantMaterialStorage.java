package com.duoduo.user.support;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * {@code {dir}/{merchantId}/{storedName}} —— 一家店一个目录，将来做
 * 「租户数据导出 / 注销即焚」（P3）时按目录整删即可，不必全盘扫描文件名。
 *
 * <h2>路径安全</h2>
 * 读接口只接受服务端自己生成的文件名（UUID + 白名单扩展），正则一层就把
 * 路径穿越（{@code ../}）拒掉 —— 与公开通道 UploadController 的 SERVABLE 同款口径。
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

    private final Path root;

    public MerchantMaterialStorage(@Value("${duoduo.materials.dir:/data/merchant-materials}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
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
            Path dir = root.resolve(String.valueOf(merchantId));
            Files.createDirectories(dir);
            Files.write(dir.resolve(storedName), content);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL, "材料保存失败", ex);
        }
        return new Stored(storedName, originalName,
                "/api/merchant-materials/" + merchantId + "/" + storedName, contentType);
    }

    /**
     * 读取一份材料。**调用方必须先做授权判断**（店主本人 / 运营），本方法只管文件系统。
     *
     * @throws BizException 文件名形状非法（路径穿越企图）或文件不存在
     */
    public ReadResult read(long merchantId, String storedName) {
        if (storedName == null || !SERVABLE.matcher(storedName).matches()) {
            throw BizException.notFound("材料");
        }
        Path file = root.resolve(String.valueOf(merchantId)).resolve(storedName).normalize();
        // 双保险：normalize 后仍必须在租户目录内（防御未来有人改动 storedName 规则）
        if (!file.startsWith(root.resolve(String.valueOf(merchantId)))) {
            throw BizException.notFound("材料");
        }
        if (!Files.isRegularFile(file)) {
            throw BizException.notFound("材料");
        }
        try {
            return new ReadResult(file, CONTENT_TYPES.get(
                    storedName.substring(storedName.lastIndexOf('.') + 1).toLowerCase()));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "材料读取失败", ex);
        }
    }

    /** 存储结果：storedName 落库用，readPath 是给前端的鉴权读取路径 */
    public record Stored(String storedName, String originalName, String readPath, String contentType) {
    }

    public record ReadResult(Path file, String contentType) {
    }
}
