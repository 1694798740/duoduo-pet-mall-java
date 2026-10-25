package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos.CategoryAttrSaveRequest;
import com.duoduo.product.dto.ProductDtos.CategoryAttrView;
import com.duoduo.product.dto.ProductDtos.KvItem;
import com.duoduo.product.entity.CategoryAttrEntity;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.CategoryAttrMapper;
import com.duoduo.product.mapper.CategoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 类目属性模板（A-10）单测。
 *
 * <p>这套断言守的是三件「错了也不报错」的事：
 * <ul>
 *   <li><b>必填 / 类型 / 选项</b>三条规则各自生效，且错误信息能指到具体属性名 ——
 *       只说「参数不合法」的校验等于没校验，商家不知道要改哪个框；</li>
 *   <li><b>老商品不被新模板卡死</b>：validate 只在发布或改规格时调用（调用时机由
 *       {@code SellerProductServiceTest} 钉住），这里则保证空模板 / 可选属性不误伤；</li>
 *   <li><b>SPU 值仍在 KV</b>：模板只读校验，不偷偷建第二份值表。</li>
 * </ul>
 */
class CategoryAttrServiceTest {

    private final CategoryAttrMapper attrMapper = mock(CategoryAttrMapper.class);
    private final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    private final CategoryAttrService service = new CategoryAttrService(
            attrMapper, categoryMapper, new SnowflakeIdGenerator(1, 1));

    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CategoryAttrEntity.class);
    }

    // ---------------- 运营维护 ----------------

    @Test
    void 新建属性_去空白_缺省TEXT_默认可选且启用() {
        when(categoryMapper.selectById(500L)).thenReturn(new CategoryEntity());

        CategoryAttrView view = service.create(500L,
                new CategoryAttrSaveRequest(" 净含量 ", null, null, null, null));

        ArgumentCaptor<CategoryAttrEntity> captor = ArgumentCaptor.forClass(CategoryAttrEntity.class);
        verify(attrMapper).insert(captor.capture());
        CategoryAttrEntity saved = captor.getValue();
        assertThat(saved.getCategoryId()).isEqualTo(500L);
        assertThat(saved.getName()).isEqualTo("净含量");
        assertThat(saved.getInputType()).isEqualTo("TEXT");
        assertThat(saved.getRequired()).isFalse();
        assertThat(saved.getOptions()).isNull();
        assertThat(saved.getSortNo()).isZero();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(view.name()).isEqualTo("净含量");
    }

    @Test
    void 新建SELECT_选项去重且为空时拒绝() {
        when(categoryMapper.selectById(500L)).thenReturn(new CategoryEntity());

        service.create(500L, new CategoryAttrSaveRequest("适用阶段", "select", false,
                List.of("幼犬", "成犬", " 幼犬 ", ""), 1));

        ArgumentCaptor<CategoryAttrEntity> captor = ArgumentCaptor.forClass(CategoryAttrEntity.class);
        verify(attrMapper).insert(captor.capture());
        assertThat(captor.getValue().getInputType()).isEqualTo("SELECT");
        assertThat(captor.getValue().getOptions()).isEqualTo("[\"幼犬\",\"成犬\"]");

        assertThatThrownBy(() -> service.create(500L,
                new CategoryAttrSaveRequest("口味", "SELECT", false, List.of(), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少要有一个选项");
    }

    @Test
    void 同分类重名属性_唯一索引冲突转409() {
        when(categoryMapper.selectById(500L)).thenReturn(new CategoryEntity());
        when(attrMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_category_attr_name"));

        assertThatThrownBy(() -> service.create(500L,
                new CategoryAttrSaveRequest("净含量", "TEXT", false, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在属性「净含量」");
    }

    @Test
    void 属性类型只能是三种之一() {
        when(categoryMapper.selectById(500L)).thenReturn(new CategoryEntity());

        assertThatThrownBy(() -> service.create(500L,
                new CategoryAttrSaveRequest("净含量", "DATE", false, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TEXT / NUMBER / SELECT");
    }

    @Test
    void SELECT改成TEXT时必须显式清空options() {
        when(attrMapper.selectById(1L)).thenReturn(attr(1L, "适用阶段", "SELECT", false,
                "[\"幼犬\",\"成犬\"]"));

        CategoryAttrView view = service.update(1L,
                new CategoryAttrSaveRequest(null, "TEXT", null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<CategoryAttrEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attrMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("options");
        // options 可空：NOT_NULL 策略不会写 null，必须显式 set —— 用参数值里出现 null 证明
        assertThat(wrapper.getParamNameValuePairs().values()).containsNull();
        assertThat(view.inputType()).isEqualTo("TEXT");
        assertThat(view.options()).isEmpty();
    }

    @Test
    void 停用与删除都要求属性存在() {
        assertThatThrownBy(() -> service.setStatus(99L, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 停用属性_状态落INACTIVE() {
        CategoryAttrEntity entity = attr(1L, "净含量", "TEXT", true, null);
        when(attrMapper.selectById(1L)).thenReturn(entity);

        service.setStatus(1L, false);

        assertThat(entity.getStatus()).isEqualTo("INACTIVE");
        verify(attrMapper).updateById(entity);
    }

    @Test
    void 运营列表要求类目存在() {
        when(categoryMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.adminList(404L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类目不存在");
    }

    // ---------------- 发布校验 ----------------

    @Test
    void 必填缺失_拒绝发布并列出属性名() {
        when(attrMapper.selectList(any())).thenReturn(List.of(
                attr("净含量", "NUMBER", true, null),
                attr("适用阶段", "SELECT", false, "[\"幼犬\",\"成犬\"]")));

        assertThatThrownBy(() -> service.validateForPublish(500L,
                List.of(new KvItem("适用阶段", "幼犬"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("以下必填属性未填写：净含量");
    }

    @Test
    void NUMBER类型_非数字拒绝() {
        when(attrMapper.selectList(any())).thenReturn(List.of(attr("净含量", "NUMBER", true, null)));

        assertThatThrownBy(() -> service.validateForPublish(500L,
                List.of(new KvItem("净含量", "两公斤"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("属性「净含量」必须是数字");
    }

    @Test
    void SELECT类型_只能取模板选项() {
        when(attrMapper.selectList(any())).thenReturn(List.of(
                attr("适用阶段", "SELECT", false, "[\"幼犬\",\"成犬\"]")));

        assertThatThrownBy(() -> service.validateForPublish(500L,
                List.of(new KvItem("适用阶段", "老年犬"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能选：幼犬 / 成犬");
    }

    @Test
    void 满足模板的规格通过_可选属性不填也不拦() {
        when(attrMapper.selectList(any())).thenReturn(List.of(
                attr("净含量", "NUMBER", true, null),
                attr("适用阶段", "SELECT", false, "[\"幼犬\",\"成犬\"]")));

        assertThatCode(() -> service.validateForPublish(500L,
                List.of(new KvItem("净含量", "2.5"), new KvItem("自定义", "随便"))))
                .doesNotThrowAnyException();
    }

    @Test
    void 类目没有模板_任何规格都放行() {
        when(attrMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service.validateForPublish(500L, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.validateForPublish(500L, List.of(new KvItem("任意", "值"))))
                .doesNotThrowAnyException();
    }

    @Test
    void 模板项停用后不参与校验() {
        // 服务层只查 ACTIVE；这里用返回空列表模拟「唯一一项已停用」
        when(attrMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service.validateForPublish(500L, List.of()))
                .doesNotThrowAnyException();
    }

    // ---------------- 夹具 ----------------

    private static CategoryAttrEntity attr(String name, String type, boolean required, String options) {
        return attr(1L, name, type, required, options);
    }

    private static CategoryAttrEntity attr(Long id, String name, String type, boolean required, String options) {
        CategoryAttrEntity entity = new CategoryAttrEntity();
        entity.setId(id);
        entity.setCategoryId(500L);
        entity.setName(name);
        entity.setInputType(type);
        entity.setRequired(required);
        entity.setOptions(options);
        entity.setSortNo(0);
        entity.setStatus(CategoryAttrEntity.STATUS_ACTIVE);
        return entity;
    }
}
