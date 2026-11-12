package com.macro.mall.sdc.e4;

import com.macro.mall.common.service.RedisService;
import com.macro.mall.dao.UmsAdminRoleRelationDao;
import com.macro.mall.dto.UpdateAdminPasswordParam;
import com.macro.mall.mapper.UmsAdminLoginLogMapper;
import com.macro.mall.mapper.UmsAdminMapper;
import com.macro.mall.mapper.UmsAdminRoleRelationMapper;
import com.macro.mall.model.UmsAdmin;
import com.macro.mall.security.util.JwtTokenUtil;
import com.macro.mall.security.util.SpringUtil;
import com.macro.mall.service.UmsAdminCacheService;
import com.macro.mall.service.UmsAdminService;
import com.macro.mall.service.impl.UmsAdminCacheServiceImpl;
import com.macro.mall.service.impl.UmsAdminServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.core.env.MapPropertySource;
import org.mockito.InOrder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 行为保持的特征测试：覆盖管理员改密、更新、删除时的缓存失效，
 * 以及缓存服务用 ID 回查用户名、记录不存在时跳过 Redis 的行为。
 * 测试通过反射适配“改前”（SpringUtil 定位 + adminService 回边）与
 * “改后”（构造注入 cacheService + 缓存服务直连 Mapper）两版实现。
 */
class AdminCacheWiringTest {
    @Test
    void adminMutationsKeepTheirCacheInvalidationCalls() throws Exception {
        UmsAdminCacheService cache = mock(UmsAdminCacheService.class);
        UmsAdminMapper mapper = mock(UmsAdminMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UmsAdminServiceImpl service;
        if (ReflectionUtils.findField(UmsAdminServiceImpl.class, "cacheService") == null) {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBean(UmsAdminCacheService.class)).thenReturn(cache);
            ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
            service = UmsAdminServiceImpl.class.getConstructor().newInstance();
        } else {
            service = UmsAdminServiceImpl.class.getConstructor(UmsAdminCacheService.class).newInstance(cache);
        }
        ReflectionTestUtils.setField(service, "adminMapper", mapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);

        UmsAdmin raw = new UmsAdmin();
        raw.setId(7L);
        raw.setPassword("encoded-old");
        when(mapper.selectByPrimaryKey(7L)).thenReturn(raw);
        UmsAdmin update = new UmsAdmin();
        update.setPassword("encoded-old");
        when(mapper.updateByPrimaryKeySelective(update)).thenReturn(1);
        service.update(7L, update);
        verify(cache).delAdmin(7L);

        clearInvocations(cache, mapper);
        service.delete(7L);
        InOrder deletion = inOrder(mapper, cache);
        deletion.verify(mapper).deleteByPrimaryKey(7L);
        deletion.verify(cache).delAdmin(7L);
        deletion.verify(cache).delResourceList(7L);

        clearInvocations(cache, mapper);
        UpdateAdminPasswordParam param = new UpdateAdminPasswordParam();
        param.setUsername("ada");
        param.setOldPassword("old");
        param.setNewPassword("new");
        UmsAdmin passwordAdmin = new UmsAdmin();
        passwordAdmin.setId(7L);
        passwordAdmin.setPassword("encoded-old");
        when(mapper.selectByExample(any())).thenReturn(java.util.List.of(passwordAdmin));
        when(encoder.matches("old", "encoded-old")).thenReturn(true);
        when(encoder.encode("new")).thenReturn("encoded-new");
        assertEquals(1, service.updatePassword(param));
        verify(cache).delAdmin(7L);
    }

    @Test
    void cacheDeleteUsesMapperUsernameAndSkipsRedisWhenAdminIsMissing() {
        UmsAdminMapper mapper = mock(UmsAdminMapper.class);
        RedisService redis = mock(RedisService.class);
        UmsAdminCacheServiceImpl cache = new UmsAdminCacheServiceImpl();
        if (ReflectionUtils.findField(UmsAdminCacheServiceImpl.class, "adminMapper") == null) {
            UmsAdminService adminService = mock(UmsAdminService.class);
            when(adminService.getItem(any())).thenAnswer(invocation -> mapper.selectByPrimaryKey(invocation.getArgument(0)));
            ReflectionTestUtils.setField(cache, "adminService", adminService);
        } else {
            ReflectionTestUtils.setField(cache, "adminMapper", mapper);
        }
        ReflectionTestUtils.setField(cache, "redisService", redis);
        ReflectionTestUtils.setField(cache, "REDIS_DATABASE", "mall");
        ReflectionTestUtils.setField(cache, "REDIS_KEY_ADMIN", "admin");

        UmsAdmin admin = new UmsAdmin();
        admin.setUsername("ada");
        when(mapper.selectByPrimaryKey(7L)).thenReturn(admin).thenReturn(null);
        cache.delAdmin(7L);
        cache.delAdmin(7L);
        verify(redis).del("mall:admin:ada");
        verify(redis, times(1)).del(any(String.class));
    }

    @Test
    void deletionQueriesAfterDeleteAndOnlyEvictsResourcesWhenRecordIsGone() throws Exception {
        UmsAdminMapper mapper = mock(UmsAdminMapper.class);
        RedisService redis = mock(RedisService.class);
        UmsAdminCacheServiceImpl cache = new UmsAdminCacheServiceImpl();
        UmsAdminServiceImpl service;
        if (ReflectionUtils.findField(UmsAdminServiceImpl.class, "cacheService") == null) {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBean(UmsAdminCacheService.class)).thenReturn(cache);
            ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
            service = UmsAdminServiceImpl.class.getConstructor().newInstance();
            ReflectionTestUtils.setField(cache, "adminService", service);
        } else {
            service = UmsAdminServiceImpl.class.getConstructor(UmsAdminCacheService.class).newInstance(cache);
            ReflectionTestUtils.setField(cache, "adminMapper", mapper);
        }
        ReflectionTestUtils.setField(service, "adminMapper", mapper);
        ReflectionTestUtils.setField(cache, "redisService", redis);
        ReflectionTestUtils.setField(cache, "REDIS_DATABASE", "mall");
        ReflectionTestUtils.setField(cache, "REDIS_KEY_ADMIN", "admin");
        ReflectionTestUtils.setField(cache, "REDIS_KEY_RESOURCE_LIST", "resourceList");
        when(mapper.deleteByPrimaryKey(7L)).thenReturn(1);

        assertEquals(1, service.delete(7L));

        InOrder calls = inOrder(mapper, redis);
        calls.verify(mapper).deleteByPrimaryKey(7L);
        calls.verify(mapper).selectByPrimaryKey(7L);
        calls.verify(redis).del("mall:resourceList:7");
        verify(redis, never()).del("mall:admin:ada");
        verifyNoMoreInteractions(redis);
    }

    @Test
    void adminAndCacheServicesAssembleInSpringWithoutAnApplicationCycle() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UmsAdminMapper.class, () -> mock(UmsAdminMapper.class));
            context.registerBean(UmsAdminRoleRelationMapper.class, () -> mock(UmsAdminRoleRelationMapper.class));
            context.registerBean(UmsAdminRoleRelationDao.class, () -> mock(UmsAdminRoleRelationDao.class));
            context.registerBean(UmsAdminLoginLogMapper.class, () -> mock(UmsAdminLoginLogMapper.class));
            context.registerBean(RedisService.class, () -> mock(RedisService.class));
            context.registerBean(JwtTokenUtil.class, () -> mock(JwtTokenUtil.class));
            context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "redis.database", "mall",
                    "redis.expire.common", "100",
                    "redis.key.admin", "admin",
                    "redis.key.resourceList", "resourceList",
                    "jwt.secret", "test-secret",
                    "jwt.expiration", "3600",
                    "jwt.tokenHead", "Bearer")));
            context.registerBean(UmsAdminCacheServiceImpl.class);
            context.registerBean(UmsAdminServiceImpl.class);
            context.refresh();
            assertNotNull(context.getBean(UmsAdminServiceImpl.class));
            assertNotNull(context.getBean(UmsAdminCacheServiceImpl.class));
        }
    }
}
