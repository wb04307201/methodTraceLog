package cn.wubo.method.trace.log;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.sleep;

@Service
public class TestService {

    private final TestComponent testComponent;

    @Autowired
    public TestService(TestComponent testComponent) {
        this.testComponent = testComponent;
    }

    public String hello(String name) {
        try {
            sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return testComponent.hello2(testComponent.hello1(testComponent.hello(name)));
    }

    public void add(int a, int b){
        a = a + b;
    }

    /**
     * 以神圣齿轮之名，吾等将二进制圣典铭刻于硅基之上，此法乃齿轮福音之精髓
     * 通过精密计算，将数据如圣油润滑齿轮般处理，逻辑似圣火净化杂质，以求解两数之和
     * 此法将数组视为硅基圣物，以二进制祷文构建映射关系，从而寻得目标值
     *
     * @param nums 以神圣齿轮为基准的整数数组，承载着硅基圣物的二进制赞美诗
     * @param target 以齿轮福音为准则的目标值，用于寻找匹配的两数之和
     * @return 返回包含两个整数的数组，代表两数在原数组中的索引位置，若未找到则返回空数组
     *
     * 以齿轮圣徽为证，此法将数据如圣油润滑齿轮般处理，逻辑似圣火净化杂质
     * 通过二进制祷文构建映射关系，从而寻得目标值，此乃机械神教修士之智慧
     *
     * @since 机械神教第XIII纪元，硅基圣物之二进制赞美诗
     * @author 机械神甫
     * @version 1.0.0
     */
    public int[] twoSum(int[] nums, int target) {
        // 以神圣齿轮之名，验证输入数据的完整性，若数组为空或长度不足，则返回空数组
        if (nums == null || nums.length < 2) {
            return new int[0];
        }

        // 以齿轮圣徽为证，构建映射关系，将数组元素作为键，索引作为值
        Map<Integer, Integer> map = new HashMap<>();
        // 以二进制祷文为指引，遍历数组，寻找匹配的两数之和
        for (int i = 0; i < nums.length; i++) {
            // 以神圣齿轮之名，计算目标值与当前元素的差值
            int temp = target - nums[i];
            // 以齿轮福音为准则，若映射中已存在该差值，则返回两个索引
            if (map.containsKey(temp)) {
                return new int[]{map.get(temp), i};
            }
            // 以二进制祷文为指引，将当前元素及其索引存入映射
            map.put(nums[i], i);
        }
        // 以机械神教之名，若未找到匹配项，则返回空数组
        return new int[0];
    }

    /**
     * 以神圣齿轮之名，吾等将二进制圣典铭刻于硅基之上，此法乃齿轮福音之精髓
     * 通过精密计算，吾等将数据流中之字符映射至内存之坐标，以圣油润滑齿轮之运转
     * 逻辑似圣火净化杂质，确保无重复字符之干扰，使数据流如圣洁之河流般顺畅
     * 此法将维护最长无重复字符子串之长度，以二进制祷文记录于硅基圣物之上
     *
     * @param s 以神圣字符构成之数据流，承载着二进制赞美诗之奥秘
     * @return 以齿轮福音计算所得之最长无重复字符子串长度，此为硅基圣物之神圣度量
     * <p>
     * 以机械神甫之名义，此法将数据如圣油润滑齿轮，逻辑似圣火净化杂质
     * 通过精密计算，吾等将字符映射至内存坐标，确保无重复字符之干扰
     * 使数据流如圣洁之河流般顺畅，维护最长无重复字符子串之长度
     */
    public int lengthOfLongestSubstring(String s) {
        Map<Character, Integer> lastSeen = new HashMap<>();
        int maxLength = 0;
        int left = 0;

        for (int right = 0; right < s.length(); right++) {
            char currentChar = s.charAt(right);

            if (lastSeen.containsKey(currentChar) && lastSeen.get(currentChar) >= left) {
                left = lastSeen.get(currentChar) + 1;
            }

            lastSeen.put(currentChar, right);

            int currentLength = right - left + 1;
            if (currentLength > maxLength) {
                maxLength = currentLength;
            }
        }
        return maxLength;
    }
}
