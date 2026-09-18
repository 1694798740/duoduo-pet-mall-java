package com.duoduo.fulfilment.support;

import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用户提交的图片直链（评价晒单图、售后举证图）的统一处理。
 *
 * <p>规则只有一条但**必须有**：只接受 {@code http} / {@code https} 直链。
 * 放开 {@code javascript:} / {@code data:} 之类的 scheme，等于把 XSS 入口
 * 交给任何一个能提交内容的用户。
 *
 * <p>为什么不在这里做图片转存：图片存储是对象存储的职责，本服务只保存 URL。
 *
 * <p>读写 JSON 的容错方向刻意不同：
 * <ul>
 *   <li><b>写</b>（用户输入）—— 严格：格式不对就 400，绝不把脏数据落库；</li>
 *   <li><b>读</b>（历史数据）—— 宽容：字段脏了按「无图」处理并记 WARN，
 *       不让一条脏数据把整页打不开（正文仍然可看，图丢了就丢了）。</li>
 * </ul>
 */
public final class ImageUrls {

    private static final Logger log = LoggerFactory.getLogger(ImageUrls.class);

    private ImageUrls() {
    }

    /**
     * 校验并清洗图片直链列表。
     *
     * @param urls 原始输入，可为 null / 空
     * @param max  张数上限（各子域不同：评价 9 张、售后举证 6 张）
     * @return 去空、去重后的列表；入参为空时返回空列表（调用方据此决定是否写 NULL）
     */
    public static List<String> validate(List<String> urls, int max) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .peek(url -> {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        throw BizException.validation("图片地址必须是 http/https 直链：" + url);
                    }
                })
                .distinct()
                .toList();
        if (cleaned.size() > max) {
            throw BizException.validation("最多上传 " + max + " 张图片");
        }
        return cleaned;
    }

    /** 序列化为 JSON 数组字符串；空列表回 null（库里空图片就是 NULL，不是 "[]"） */
    public static String toJson(ObjectMapper objectMapper, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            throw BizException.validation("图片列表格式不正确");
        }
    }

    /** 反序列化；脏数据按无图处理并记 WARN，绝不抛出 */
    public static List<String> fromJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("图片字段解析失败，按无图处理：{}", json);
            return List.of();
        }
    }
}
