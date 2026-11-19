package com.macro.mall.portal.sdc;

import com.macro.mall.mapper.UmsMemberReceiveAddressMapper;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberReceiveAddress;
import com.macro.mall.portal.service.UmsMemberService;
import com.macro.mall.portal.service.impl.UmsMemberReceiveAddressServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁定 E5-03 收货地址邮编的“现状”，不实现任何新规则。
 *
 * 现状：服务把 postCode 原样透传给 Mapper，不做校验、不去空白、不判空；
 * add 与 update 两个写入入口都如此。update 在 defaultStatus=1 时先清旧默认
 * 地址，再执行真正的更新——这一步顺序是未来校验要特别小心的风险点。
 *
 * 这里只断言“合法六位数字原样通过”，不断言 5 位/7 位/全角“现在也能写入”，
 * 因为那些现状会被新规则改变，不能当永久断言。
 */
class SDCE503Test {

    private UmsMemberReceiveAddressServiceImpl service;
    private UmsMemberReceiveAddressMapper mapper;

    /** insert 收到过的每一份地址对象（按调用顺序）。 */
    private final List<UmsMemberReceiveAddress> insertedRows = new ArrayList<>();
    /** updateByExampleSelective 收到过的每一份 row 参数（按调用顺序）。 */
    private final List<UmsMemberReceiveAddress> updatedRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new UmsMemberReceiveAddressServiceImpl();
        UmsMemberService memberService = mock(UmsMemberService.class);
        mapper = mock(UmsMemberReceiveAddressMapper.class);
        ReflectionTestUtils.setField(service, "memberService", memberService);
        ReflectionTestUtils.setField(service, "addressMapper", mapper);

        UmsMember current = new UmsMember();
        current.setId(101L);
        when(memberService.getCurrentMember()).thenReturn(current);

        when(mapper.insert(any())).thenAnswer(inv -> {
            insertedRows.add(inv.getArgument(0));
            return 1;
        });
        when(mapper.updateByExampleSelective(any(), any())).thenAnswer(inv -> {
            updatedRows.add(inv.getArgument(0));
            return 1;
        });
    }

    @Test
    void addPassesPostCodeThroughUnchanged() {
        UmsMemberReceiveAddress request = new UmsMemberReceiveAddress();
        request.setPostCode("001234");

        assertEquals(1, service.add(request));

        assertEquals(1, insertedRows.size());
        assertEquals("001234", insertedRows.get(0).getPostCode());
        assertEquals(101L, insertedRows.get(0).getMemberId());
    }

    @Test
    void updateKeepsPostCodeWhenNotBecomingDefault() {
        UmsMemberReceiveAddress request = new UmsMemberReceiveAddress();
        request.setPostCode("001234");
        request.setDefaultStatus(0);

        assertEquals(1, service.update(11L, request));

        assertEquals(1, updatedRows.size());
        assertEquals("001234", updatedRows.get(0).getPostCode());
    }

    @Test
    void updateToDefaultClearsOldDefaultBeforeWritingPostCode() {
        UmsMemberReceiveAddress request = new UmsMemberReceiveAddress();
        request.setPostCode("001234");
        request.setDefaultStatus(1);

        assertEquals(1, service.update(11L, request));

        assertEquals(2, updatedRows.size());
        // 第一次 updateByExampleSelective 只清旧默认地址，不携带邮编。
        assertEquals(Integer.valueOf(0), updatedRows.get(0).getDefaultStatus());
        assertNull(updatedRows.get(0).getPostCode());
        // 第二次才是真正的地址更新，邮编原样进入。
        assertEquals("001234", updatedRows.get(1).getPostCode());
    }

    @Test
    void postCodeIsAStringAcrossModelAndSchema() throws Exception {
        assertEquals(String.class,
                UmsMemberReceiveAddress.class.getDeclaredField("postCode").getType());
        String sql = readRepoFile("document/sql/mall.sql");
        assertTrue(sql.contains("`post_code` varchar(100)"));
        String xml = readRepoFile(
                "mall-mbg/src/main/resources/com/macro/mall/mapper/UmsMemberReceiveAddressMapper.xml");
        assertTrue(xml.contains("jdbcType=\"VARCHAR\" property=\"postCode\""));
    }

    /** 从当前工作目录向上查找仓库根目录下的相对路径文件并读成字符串。 */
    private static String readRepoFile(String relative) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到仓库文件: " + relative);
    }
}
