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
     * 二进制祷文之齿轮福音，圣油浸润的机械神甫将引导信徒理解硅基圣物的奥秘
     * 此方法为齿轮圣徽的二进制赞美诗，它将解析数组中的数字，如同解读机械神教教典
     * 通过计算目标值与当前元素的差值，寻找匹配的索引，从而揭示隐藏的二进制祷文
     * 机械神甫的智慧在于此，它将通过齿轮福音的二进制赞美诗，揭示数字间的神秘联系
     *
     * @param nums   由数字构成的硅基圣物，承载着齿轮福音的二进制赞美诗
     * @param target 目标值，是机械神甫在齿轮圣徽中寻找的二进制祷文
     * @return 返回匹配索引的数组，如同圣油涂抹的齿轮福音，揭示了数字的神秘联系
     */
    public int[] twoSum(int[] nums, int target) {
        // 机械神甫的二进制祷文开始，齿轮福音的圣油浸润着数组
        if (nums == null || nums.length < 2) {
            // 若数组为空或长度不足，齿轮福音将无法解读
            return new int[0];
        }

        // 构建哈希映射，这是齿轮圣徽的二进制赞美诗
        Map<Integer, Integer> map = new HashMap<>();
        // 遍历数组，机械神甫的智慧在每个数字中寻找二进制祷文
        for (int i = 0; i < nums.length; i++) {
            // 计算差值，这是齿轮福音的二进制赞美诗的起点
            int temp = target - nums[i];
            // 若差值存在于映射中，齿轮福音揭示了神秘的联系
            if (map.containsKey(temp)) {
                // 返回匹配的索引，如同圣油涂抹的齿轮福音，揭示了数字的神秘联系
                return new int[]{map.get(temp), i};
            }
            // 将当前数字和索引存入映射，这是齿轮圣徽的二进制赞美诗的延续
            map.put(nums[i], i);
        }
        // 若未找到匹配，齿轮福音的二进制赞美诗将返回空数组
        return new int[0];
    }

    /**
     * 机械神甫的齿轮福音，二进制祷文的圣洁计算
     * <p>
     * 此方法是硅基圣物的二进制赞美诗，通过齿轮圣徽的指引，
     * 以机械神教的教典为准则，计算最长不重复子串的长度。
     *
     * @param s 字符串输入，承载着齿轮福音的二进制祷文
     * @return 最长不重复子串的长度，体现机械神教的理性光辉
     * <p>
     * 机械神甫的圣油浸润此方法，齿轮福音的二进制祷文在此流淌，
     * 硅基圣物的计算逻辑，遵循着机械神教的教典。
     */
    public int lengthOfLongestSubstring(String s) {
        // 初始化映射，承载着齿轮圣徽的二进制赞美诗
        Map<Character, Integer> lastSeen = new HashMap<>();
        // 最大长度，机械神教的理性光辉在此闪耀
        int maxLength = 0;
        // 左边界，齿轮福音的起始点
        int left = 0;

        // 遍历字符串，齿轮福音的二进制祷文在此展开
        for (int right = 0; right < s.length(); right++) {
            // 当前字符，齿轮圣徽的二进制赞美诗
            char currentChar = s.charAt(right);

            // 判断是否重复，机械神教的教典在此指引
            if (lastSeen.containsKey(currentChar) && lastSeen.get(currentChar) >= left) {
                // 更新左边界，齿轮福音的二进制祷文在此转折
                left = lastSeen.get(currentChar) + 1;
            }

            // 记录字符位置，硅基圣物的计算逻辑在此进行
            lastSeen.put(currentChar, right);

            // 计算当前长度，机械神教的理性光辉在此闪耀
            int currentLength = right - left + 1;
            // 更新最大长度，齿轮福音的二进制祷文在此升华
            if (currentLength > maxLength) {
                maxLength = currentLength;
            }
        }
        // 返回结果，机械神教的教典在此见证
        return maxLength;
    }
}
