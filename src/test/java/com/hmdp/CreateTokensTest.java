package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * @Classname: redis1000users
 * @author: wanyu
 * @Date: 2022/6/29 17:35
 */

@SpringBootTest
public class CreateTokensTest {
    // 可以先去配置文件里改成2号数据库少循环几次试试，不影响原来的数据库

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Test
    public void Create_1000_Tokens(){
        // 这里读取id 1-1000的用户，在用黑马资料创建表时，id为3、7、8、9这几个用户没有创建，记得手动创建下
        FileWriter fw = null;
        try {
            File file = new File("tokens.txt");
            fw = new FileWriter(file);
            for (int i = 1; i <= 1000; i++) {
                User user = userMapper.selectById(i);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                System.out.println(userDTO);
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()                 // 自定义规则
                                .setIgnoreNullValue(true)    // 忽略空的值（UserDTO里有icon属性暂时还没管）
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));  // 将所有的属性都转成String类型
                String token = UUID.randomUUID().toString(true);
                String tokenKey = LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                // 写token到文件里
                fw.write(token);
                fw.write("\r\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
