package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * @Classname: SaveShop2RedisTest
 * @author: wanyu
 * @Date: 2022/7/9 12:50
 */

@SpringBootTest
public class SaveShop2RedisTest {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Test
    public void testSaveShop_1(){
        shopService.saveShop2Redis(1L, 10L); // 为了方便测试只写了10s
    }

    @Test
    public void testSaveShop_2(){
        // 从DB里拿2号店铺信息
        Shop shop = shopService.getById(2L);
        // 这里是测试缓存工具类，为了方便测试只设置逻辑过期为10s
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 2L, shop, 10L, TimeUnit.SECONDS);
    }

    // 方便以后测试，我们写个for循环把所有缓存信息都添加进去，逻辑过期时间随便设置下，按照现在quaryById的逻辑都会重建缓存的
    @Test
    public void testSaveShop_all(){
        for (int i = 1; i < 15; i++) {
            Shop shop = shopService.getById((long) i);
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + (long) i, shop, 10L, TimeUnit.SECONDS);
        }
    }
}
