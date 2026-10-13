package com.duoduo.common.web.storage;

import com.duoduo.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地盘实现的单测（S9）。
 *
 * <h2>为什么这个"过渡实现"也要认真测</h2>
 * 它是**默认值与回退路径**：对象存储出问题时靠它兜底，CI/开发环境（没有桶）也全靠它。
 * 更关键的是——切换介质时**不能动存量文件**，所以它的"逻辑 key → 物理路径"映射
 * 必须与改造前**逐字节一致**。下面第一条用例就是钉这件事的。
 */
class LocalFsObjectStorageTest {

    @Test
    @DisplayName("★ 逻辑前缀要被剥掉，而不是多建一层目录（存量文件原地可用的前提）")
    void stripsLogicalPrefixInsteadOfNesting(@TempDir Path root) {
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "uploads/");

        storage.put("uploads/abc.jpg", "hello".getBytes(), "image/jpeg");

        // 物理落点是 <root>/abc.jpg —— 与改造前 UploadController 的行为一致。
        // 若这里变成 <root>/uploads/abc.jpg，就等于把存量文件全部"视为不存在"。
        assertThat(root.resolve("abc.jpg")).exists();
        assertThat(root.resolve("uploads")).doesNotExist();
        assertThat(storage.get("uploads/abc.jpg")).isEqualTo("hello".getBytes());
    }

    @Test
    @DisplayName("租户目录结构原样保留：materials/<merchantId>/<file>")
    void keepsTenantDirectoryLayout(@TempDir Path root) {
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "materials/");

        storage.put("materials/123/x.pdf", "pdf".getBytes(), "application/pdf");

        assertThat(root.resolve("123").resolve("x.pdf")).exists();
    }

    @Test
    @DisplayName("key 缺失返回 null（而不是抛异常）：读端点要据此回 404")
    void missingKeyReturnsNull(@TempDir Path root) {
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "uploads/");

        assertThat(storage.get("uploads/nope.jpg")).isNull();
        assertThat(storage.exists("uploads/nope.jpg")).isFalse();
    }

    @Test
    @DisplayName("★ 前缀不匹配立刻报错（防止两类文件被混进同一个根目录）")
    void rejectsWrongPrefix(@TempDir Path root) {
        LocalFsObjectStorage uploads = new LocalFsObjectStorage(root.toString(), "uploads/");

        // 把 materials 的 key 丢给 uploads 的实现，应当在 uploads 根下建出一个 materials/ 目录之前就被拦下
        assertThatThrownBy(() -> uploads.put("materials/123/x.pdf", "x".getBytes(), "application/pdf"))
                .isInstanceOf(BizException.class);
        assertThat(root.resolve("materials")).doesNotExist();
    }

    @Test
    @DisplayName("★ 路径穿越被拒绝（../../ 之类不得逃出 root）")
    void rejectsPathTraversal(@TempDir Path root) {
        Path outside = root.getParent().resolve("escaped.jpg");
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "uploads/");

        assertThatThrownBy(() -> storage.put("uploads/../../escaped.jpg", "x".getBytes(), "image/jpeg"))
                .isInstanceOf(BizException.class);
        assertThat(outside).doesNotExist();
    }

    @Test
    @DisplayName("delete 幂等：不存在也算成功（清理脚本要能重复跑）")
    void deleteIsIdempotent(@TempDir Path root) {
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "uploads/");

        storage.delete("uploads/never-existed.jpg");
        storage.put("uploads/a.jpg", "x".getBytes(), "image/jpeg");
        storage.delete("uploads/a.jpg");
        storage.delete("uploads/a.jpg");

        assertThat(storage.exists("uploads/a.jpg")).isFalse();
    }

    @Test
    @DisplayName("覆盖写之后内容是新的一份，且不残留 .tmp")
    void overwriteReplacesContentWithoutTmpLeftover(@TempDir Path root) throws Exception {
        LocalFsObjectStorage storage = new LocalFsObjectStorage(root.toString(), "uploads/");

        storage.put("uploads/a.jpg", "first".getBytes(), "image/jpeg");
        storage.put("uploads/a.jpg", "second-longer".getBytes(), "image/jpeg");

        assertThat(storage.get("uploads/a.jpg")).isEqualTo("second-longer".getBytes());
        try (var s = Files.list(root)) {
            assertThat(s.map(p -> p.getFileName().toString()))
                    .as("临时文件必须被移走，不能在目录里留下 .tmp")
                    .containsExactly("a.jpg");
        }
    }

    @Test
    @DisplayName("backend() 自报 local —— 自检脚本据此断言「到底走的哪条路」")
    void reportsBackendName() {
        assertThat(new LocalFsObjectStorage("./does-not-matter", "uploads/").backend()).isEqualTo("local");
    }
}
