package com.duoduo.fulfilment.support;

import com.duoduo.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 图片直链校验（C3）：口径必须同时容纳「绝对直链」与「本站上传的相对路径」，
 * 并且对可执行 scheme 保持零容忍。
 */
class ImageUrlsTest {

    @Test
    void acceptsAbsoluteAndSiteUploadPaths() {
        List<String> ok = ImageUrls.validate(
                List.of("https://cdn.example.com/a.png", "/uploads/6f1e-2b.png"), 6);
        assertEquals(2, ok.size());
    }

    @Test
    void rejectsExecutableSchemes() {
        assertThrows(BizException.class, () -> ImageUrls.validate(List.of("javascript:alert(1)"), 6));
        assertThrows(BizException.class, () -> ImageUrls.validate(List.of("data:image/png;base64,AAA"), 6));
        // 任意站内路径也不行：只认 /uploads/ 这一个前缀
        assertThrows(BizException.class, () -> ImageUrls.validate(List.of("/orders/123"), 6));
    }

    @Test
    void enforcesMaxAndDeduplicates() {
        assertThrows(BizException.class, () -> ImageUrls.validate(
                List.of("/uploads/a.png", "/uploads/b.png", "/uploads/c.png"), 2));
        assertEquals(1, ImageUrls.validate(List.of("/uploads/a.png", "/uploads/a.png"), 6).size());
    }
}
