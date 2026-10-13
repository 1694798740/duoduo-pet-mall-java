package com.duoduo.common.web.storage;

import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统实现 —— **默认值与回退路径**（S9）。
 *
 * <h2>它的职责不是"过渡"，而是"兜底"</h2>
 * 对象存储接上以后，这个实现**不会消失**：它是"新存储层出问题时能一行配置退回来"的那条路，
 * 也是开发/CI 环境（没有桶、没有密钥）能跑通全部测试的前提。
 * 因此它必须与改造前的行为**逐字节等价** —— 包括落盘位置、目录结构、错误提示。
 *
 * <h2>为什么要有 {@code logicalPrefix}</h2>
 * 逻辑 key 是 {@code uploads/<uuid>.jpg}（与 COS 桶内 key 一致，见 {@link ObjectStorage}），
 * 但本地盘的**物理位置**是 {@code /data/uploads/<uuid>.jpg} —— 也就是
 * 「逻辑前缀」在本地这条路上要**被剥掉**，而不是真的多建一层 {@code uploads/} 目录。
 * 剥掉而不是多建一层，是为了让**已有文件原地可用**：切到对象存储时，
 * 存量文件按原名上传即为正确 key，不需要搬目录、不需要改数据库里的 URL。
 *
 * <h2>路径安全</h2>
 * 与改造前同款口径：{@code normalize()} 之后必须仍在 root 之内，
 * 否则抛错。调用方（两个控制器）的正则白名单是第一道，这里是第二道。
 */
public class LocalFsObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFsObjectStorage.class);

    private final Path root;
    private final String logicalPrefix;

    /**
     * @param dir           物理根目录（如 {@code /data/uploads}）
     * @param logicalPrefix 逻辑前缀，含结尾斜杠（如 {@code uploads/}）；空串表示不剥
     */
    public LocalFsObjectStorage(String dir, String logicalPrefix) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.logicalPrefix = logicalPrefix == null ? "" : logicalPrefix;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (content == null) {
            throw BizException.validation("要写入的内容为空");
        }
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            // 先写临时文件再原子替换：直接覆盖写时进程被杀会留下**体积正确但内容截断**的半个文件，
            // 而下游（备份的 sha256 比对、前端出图）都只会看到一个"存在"的文件 —— 属于静默损坏。
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BizException(com.duoduo.common.enums.ErrorCode.INTERNAL,
                    "文件保存失败", e);
        }
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new BizException(com.duoduo.common.enums.ErrorCode.INTERNAL, "文件读取失败", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            // 不存在也算成功：清理脚本会重复调用，用异常表达"已经没了"会让它无法重跑
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除失败（已忽略）path={}", target, e);
        }
    }

    @Override
    public String backend() {
        return "local";
    }

    /** 逻辑 key → 物理路径。剥掉逻辑前缀，并做一次"仍在 root 内"的双保险。 */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw BizException.validation("非法的存储路径");
        }
        String rel = key;
        if (!logicalPrefix.isEmpty()) {
            if (!rel.startsWith(logicalPrefix)) {
                // 走错后端（比如把 materials 的 key 丢给 uploads 的实现）应当**立刻报错**，
                // 而不是在 uploads 根目录下建一个叫 materials 的目录 —— 那会把两类文件混在一起。
                throw BizException.validation("非法的存储路径前缀");
            }
            rel = rel.substring(logicalPrefix.length());
        }
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw BizException.validation("非法的存储路径");
        }
        return target;
    }
}
