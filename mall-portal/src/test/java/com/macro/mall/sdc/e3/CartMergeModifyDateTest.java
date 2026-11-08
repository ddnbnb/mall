package com.macro.mall.sdc.e3;

import com.macro.mall.mapper.OmsCartItemMapper;
import com.macro.mall.model.OmsCartItem;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.service.UmsMemberService;
import com.macro.mall.portal.service.impl.OmsCartItemServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Spec-first failing test: merging a cart item must refresh the modify date on the
 * <em>existing</em> (persisted) row.
 *
 * <p>Defect: {@code OmsCartItemServiceImpl.add} sets {@code modifyDate} on the incoming
 * {@code cartItem} (which is never persisted) in the merge branch, while
 * {@code updateByPrimaryKey} persists {@code existCartItem}. The persisted row therefore
 * keeps its stale modify date after a merge.</p>
 *
 * <p>The red light must come from a business assertion: {@code mergeRefreshesSavedModifyDate}
 * asserts that the persisted argument's {@code modifyDate} falls inside the call time window;
 * before the fix this assertion fails because the modify date stays at its old value.</p>
 */
class CartMergeModifyDateTest {

    private final OmsCartItemServiceImpl service = new OmsCartItemServiceImpl();

    @Test
    void mergeRefreshesSavedModifyDate() {
        stubCurrentMember(101L, "m101");
        OmsCartItemMapper mapper = inject("cartItemMapper", OmsCartItemMapper.class);

        OmsCartItem existing = new OmsCartItem();
        existing.setId(7L);
        existing.setQuantity(2);
        existing.setModifyDate(new Date(1000L)); // old value, far before "now"
        when(mapper.selectByExample(any())).thenReturn(List.of(existing));

        AtomicReference<OmsCartItem> saved = new AtomicReference<>();
        when(mapper.updateByPrimaryKey(any())).thenAnswer(inv -> {
            OmsCartItem argument = inv.getArgument(0);
            assertSame(existing, argument, "persisted row must be the existing item, not the incoming one");
            OmsCartItem snapshot = new OmsCartItem();
            snapshot.setQuantity(argument.getQuantity());
            snapshot.setModifyDate(argument.getModifyDate());
            saved.set(snapshot);
            return 1;
        });

        OmsCartItem incoming = new OmsCartItem();
        incoming.setProductId(8L);
        incoming.setQuantity(3);

        Date before = new Date();
        service.add(incoming);
        Date after = new Date();

        OmsCartItem persisted = saved.get();
        assertNotNull(persisted);
        assertEquals(5, persisted.getQuantity(), "quantity must accumulate: 2 + 3");
        assertNotNull(persisted.getModifyDate(), "modifyDate must be refreshed on the saved row");
        assertFalse(persisted.getModifyDate().before(before),
                "saved modifyDate=" + persisted.getModifyDate().getTime() + " must not be before call start");
        assertFalse(persisted.getModifyDate().after(after),
                "saved modifyDate=" + persisted.getModifyDate().getTime() + " must not be after call end");
        verify(mapper, never()).insert(any());
    }

    @Test
    void insertKeepsCreateDateAndMemberSource() {
        stubCurrentMember(101L, "m101");
        OmsCartItemMapper mapper = inject("cartItemMapper", OmsCartItemMapper.class);
        when(mapper.selectByExample(any())).thenReturn(List.of());

        AtomicReference<OmsCartItem> inserted = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(inv -> {
            OmsCartItem argument = inv.getArgument(0);
            OmsCartItem snapshot = new OmsCartItem();
            snapshot.setQuantity(argument.getQuantity());
            snapshot.setMemberId(argument.getMemberId());
            snapshot.setMemberNickname(argument.getMemberNickname());
            snapshot.setDeleteStatus(argument.getDeleteStatus());
            snapshot.setCreateDate(argument.getCreateDate());
            inserted.set(snapshot);
            return 1;
        });

        OmsCartItem incoming = new OmsCartItem();
        incoming.setProductId(8L);
        incoming.setQuantity(3);
        incoming.setMemberId(999L);
        incoming.setMemberNickname("other");
        incoming.setDeleteStatus(1);

        Date before = new Date();
        service.add(incoming);
        Date after = new Date();

        OmsCartItem persisted = inserted.get();
        assertNotNull(persisted);
        assertEquals(3, persisted.getQuantity());
        assertEquals(101L, persisted.getMemberId(), "memberId must come from the current member");
        assertEquals("m101", persisted.getMemberNickname(), "nickname must come from the current member");
        assertEquals(0, persisted.getDeleteStatus(), "deleteStatus must be reset to 0");
        assertNotNull(persisted.getCreateDate());
        assertFalse(persisted.getCreateDate().before(before));
        assertFalse(persisted.getCreateDate().after(after));
        verify(mapper, never()).updateByPrimaryKey(any());
    }

    private void stubCurrentMember(long id, String nickname) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setNickname(nickname);
        UmsMemberService members = inject("memberService", UmsMemberService.class);
        when(members.getCurrentMember()).thenReturn(member);
    }

    private <T> T inject(String field, Class<T> type) {
        T value = mock(type, withSettings().mockMaker("mock-maker-subclass"));
        ReflectionTestUtils.setField(service, field, value);
        return value;
    }
}
