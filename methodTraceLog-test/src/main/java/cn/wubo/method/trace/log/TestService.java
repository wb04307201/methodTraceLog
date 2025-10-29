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
     * 此方法乃齿轮福音之核心——以神圣二进制祷文为基，以硅基圣油为血！
     * 以数据如圣油润滑齿轮，逻辑似圣火净化杂质，此乃机械神甫之智慧结晶。
     * 若见'0101'闪烁，便是机械之神在低语；若闻'ERROR'，便是异端在腐蚀神圣齿轮！
     *
     * @param nums   硅基数组，承载着神圣数字之奥秘
     * @param target 目标值，即机械神甫所求之真理
     * @return 返回二进制圣典之解，以齿轮福音之名，将两数之和化为神圣之数对
     */
    public int[] twoSum(int[] nums, int target) {
        // 以神圣齿轮之名，吾等将二进制圣典铭刻于硅基之上
        if (nums == null || nums.length < 2) {
            // 若数据为空，即为异端之征兆，需返回空之圣油
            return new int[0];
        }

        // 以神圣地图之名，将数字与索引之关系铭刻于硅基之上
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            // 以神圣计算之名，吾等将目标值与当前值相减
            int temp = target - nums[i];
            // 以神圣验证之名，吾等将验证地图中是否已存在该值
            if (map.containsKey(temp)) {
                // 以神圣齿轮之名，吾等将找到之解返回
                return new int[]{map.get(temp), i};
            }
            // 以神圣铭刻之名，吾等将当前值与索引之关系铭刻于地图之上
            map.put(nums[i], i);
        }
        // 以神圣齿轮之名，若未找到解，则返回空之圣油
        return new int[0];
    }

    /**
     * 此方法乃齿轮福音之核心——以神圣二进制祷文为基，以硅基圣油为血！
     * 以数据如圣油润滑齿轮，逻辑似圣火净化杂质，此乃机械神甫之智慧结晶。
     * 若见'0101'闪烁，便是机械之神在低语；若闻'ERROR'，便是异端在腐蚀神圣齿轮！
     *
     * @param s 硅基字符串，承载着神圣字符之奥秘
     * @return 返回最长无重复字符子串之长度，以齿轮福音之名，将字符之排列化为神圣之数对
     */
    public int lengthOfLongestSubstring(String s) {
        // 以神圣齿轮之名，吾等将字符与最后出现位置之关系铭刻于硅基之上
        Map<Character, Integer> lastSeen = new HashMap<>();
        // 以神圣计算之名，吾等将最长长度初始化为零
        int maxLength = 0;
        // 以神圣定位之名，吾等将左边界初始化为零
        int left = 0;

        // 以神圣遍历之名，吾等将字符串中每个字符逐一检验
        for (int right = 0; right < s.length(); right++) {
            // 以神圣提取之名，吾等将当前字符从字符串中取出
            char currentChar = s.charAt(right);

            // 以神圣验证之名，吾等将验证当前字符是否已出现过且在当前窗口内
            if (lastSeen.containsKey(currentChar) && lastSeen.get(currentChar) >= left) {
                // 以神圣移位之名，吾等将左边界移至重复字符之后
                left = lastSeen.get(currentChar) + 1;
            }

            // 以神圣铭刻之名，吾等将当前字符与最后出现位置之关系铭刻于地图之上
            lastSeen.put(currentChar, right);

            // 以神圣计算之名，吾等将当前窗口长度计算出来
            int currentLength = right - left + 1;
            // 以神圣比较之名，吾等将当前长度与最大长度比较
            if (currentLength > maxLength) {
                // 以神圣记录之名，吾等将最大长度更新
                maxLength = currentLength;
            }
        }
        // 以神圣齿轮之名，吾等将找到之最长长度返回
        return maxLength;
    }
}
