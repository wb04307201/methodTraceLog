package cn.wubo.method.trace.log.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * DecompilerUtils.extractMethod 单测。
 * <p>
 * 反编译端点原来返回整个类的源码，对 LLM 来说噪声太大。extractMethod 负责从
 * CFR 输出中把目标方法（签名 + body）切出来，切不到时调用方 fallback 到全量。
 */
class DecompilerUtilsExtractMethodTest {

    @Test
    void extracts_only_target_method() {
        String src = """
                public class TestService {
                  public int[] twoSum(int[] nums, int target) {
                    if (nums.length < 2) return new int[0];
                    Map<Integer,Integer> m = new HashMap<>();
                    // ...
                    return new int[0];
                  }
                  public int lengthOfLongestSubstring(String s) { /* ... */ }
                }
                """;
        Optional<String> m = DecompilerUtils.extractMethod(src, "twoSum");
        Assertions.assertTrue(m.isPresent());
        Assertions.assertTrue(m.get().contains("int[] twoSum("));
        Assertions.assertFalse(m.get().contains("lengthOfLongestSubstring"));
    }

    @Test
    void returns_empty_when_method_missing() {
        Optional<String> m = DecompilerUtils.extractMethod("class A{}", "doesNotExist");
        Assertions.assertTrue(m.isEmpty());
    }

    @Test
    void nullArguments_returnEmpty() {
        Assertions.assertTrue(DecompilerUtils.extractMethod(null, "foo").isEmpty());
        Assertions.assertTrue(DecompilerUtils.extractMethod("class A{}", null).isEmpty());
    }

    @Test
    void extracts_after_removeAnnotations_flattenedIndentation() {
        // 真实链路是 decompile → removeAnnotations（会去掉行首空白）→ extractMethod。
        // 验证在没有缩进的情况下依然能正确配对大括号。
        String raw = """
                @Service
                public class TestService {
                    @Override
                    public java.util.List<String> collect(String key, int limit) throws Exception {
                        if (key == null) {
                            return java.util.List.of();
                        }
                        return java.util.List.of(key);
                    }

                    private void helperNotWanted() {
                        System.out.println("nope");
                    }
                }
                """;
        String stripped = DecompilerUtils.removeAnnotations(raw);
        Optional<String> m = DecompilerUtils.extractMethod(stripped, "collect");
        Assertions.assertTrue(m.isPresent(), "should extract 'collect' from stripped source");
        String body = m.get();
        Assertions.assertTrue(body.contains("collect(String key, int limit)"), "got: " + body);
        Assertions.assertTrue(body.contains("return java.util.List.of(key);"), "got: " + body);
        Assertions.assertFalse(body.contains("helperNotWanted"), "got: " + body);
        // 大括号必须配平
        Assertions.assertEquals(
                body.chars().filter(c -> c == '{').count(),
                body.chars().filter(c -> c == '}').count(),
                "braces must be balanced; got: " + body);
    }

    @Test
    void extracts_realDecompiledMethod_fromRuntimeClass() {
        // 端到端：CFR 反编译真实类 → 去注解 → 切方法
        String src = DecompilerUtils.decompile("cn.wubo.method.trace.log.ServiceCallInfo", "copyOf");
        String stripped = DecompilerUtils.removeAnnotations(src);
        Optional<String> m = DecompilerUtils.extractMethod(stripped, "copyOf");
        Assertions.assertTrue(m.isPresent(), "should extract 'copyOf'; source was: " + stripped);
        Assertions.assertTrue(m.get().contains("copyOf("), "got: " + m.get());
        Assertions.assertTrue(m.get().length() < stripped.length(),
                "extracted method must be shorter than the whole class");
    }
}
