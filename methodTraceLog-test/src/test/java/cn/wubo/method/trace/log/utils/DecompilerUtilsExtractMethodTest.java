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

    // === Fix Round 1: 字符串/注释/换行签名 ===

    @Test
    void string_literal_with_braces_does_not_break_extraction() {
        String src = "class A { void foo() { String s = \"{x}\"; return; } void bar() {} }";
        Optional<String> m = DecompilerUtils.extractMethod(src, "foo");
        Assertions.assertTrue(m.isPresent());
        Assertions.assertTrue(m.get().contains("foo("), "got: " + m.get());
        Assertions.assertFalse(m.get().contains("bar"), "must not swallow next method; got: " + m.get());
    }

    @Test
    void comment_with_brace_does_not_break_extraction() {
        String src = "class A { void foo() { /* ignore { brace */ return; } void bar() {} }";
        Optional<String> m = DecompilerUtils.extractMethod(src, "foo");
        Assertions.assertTrue(m.isPresent());
        Assertions.assertTrue(m.get().contains("foo("), "got: " + m.get());
        Assertions.assertFalse(m.get().contains("bar"), "must not swallow next method; got: " + m.get());
    }

    @Test
    void multi_line_signature_is_matched() {
        String src = "class A {\n  public <T extends Comparable<T>>\n    int foo(T x) { return 0; }\n  void bar() {}\n}";
        Optional<String> m = DecompilerUtils.extractMethod(src, "foo");
        Assertions.assertTrue(m.isPresent(), "should match multi-line generic signature; got empty");
        Assertions.assertTrue(m.get().contains("foo(T x)"), "got: " + m.get());
        Assertions.assertFalse(m.get().contains("bar"), "must not swallow next method; got: " + m.get());
    }

    @Test
    void char_literal_with_closing_brace_inside_does_not_break_extraction() {
        String src = "class A { void foo() { char c = '}'; return; } void bar() {} }";
        Optional<String> m = DecompilerUtils.extractMethod(src, "foo");
        Assertions.assertTrue(m.isPresent(), "got: " + m.orElse("<empty>"));
        Assertions.assertFalse(m.get().contains("bar"), "got: " + m.get());
    }

    @Test
    void escaped_quote_in_string_does_not_break_extraction() {
        String src = "class A { void foo() { String s = \"a \\\"b {c}\\\" d\"; return; } void bar() {} }";
        Optional<String> m = DecompilerUtils.extractMethod(src, "foo");
        Assertions.assertTrue(m.isPresent(), "got: " + m.orElse("<empty>"));
        Assertions.assertFalse(m.get().contains("bar"), "got: " + m.get());
    }

    // === Fix Round 2: 嵌套泛型签名（CFR 病态输出会长成 Map<String, List<Map<String, Integer>>） ===

    @Test
    void nested_generic_returnType_isMatched() {
        // 短写在一行 — 验证现有正则能命中 1 层 <> + 1 层内部 <...>
        String src = "class A {\n" +
                "  public java.util.Map<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Integer>>> aggregate() { return null; }\n" +
                "  void other() {}\n" +
                "}\n";
        Optional<String> m = DecompilerUtils.extractMethod(src, "aggregate");
        Assertions.assertTrue(m.isPresent(), "should match nested-generic return type; got empty. src=" + src);
        Assertions.assertTrue(m.get().contains("aggregate()"),
                "extracted should contain method name; got: " + m.get());
        Assertions.assertFalse(m.get().contains("other"),
                "extracted must not swallow next method; got: " + m.get());
    }

    @Test
    void deeplyNested_generic_returnType_acrossMultipleLines_isMatched() {
        // CFR 长泛型签名经常换行 —— 把类型拆成多行，验证正则仍能命中
        String src = "class A {\n" +
                "  public java.util.Map<java.lang.String,\n" +
                "          java.util.List<java.util.Map<java.lang.String, java.lang.Integer>>>\n" +
                "      aggregate() { return null; }\n" +
                "  void other() {}\n" +
                "}\n";
        Optional<String> m = DecompilerUtils.extractMethod(src, "aggregate");
        Assertions.assertTrue(m.isPresent(),
                "multi-line nested generic signature should still match; got empty. src=" + src);
        Assertions.assertTrue(m.get().contains("aggregate()"),
                "got: " + m.get());
        Assertions.assertFalse(m.get().contains("other"),
                "got: " + m.get());
    }

    @Test
    void nested_generic_signature_hasBalancedBraces() {
        // 抽出后大括号必须配平 —— 这是 extractMethod 的硬约束
        String src = "class A {\n" +
                "  public java.util.Map<java.lang.String, java.util.List<java.util.Map<java.lang.String, java.lang.Integer>>> aggregate() {\n" +
                "    if (true) { return null; }\n" +
                "    return new java.util.HashMap<>();\n" +
                "  }\n" +
                "  void other() {}\n" +
                "}\n";
        Optional<String> m = DecompilerUtils.extractMethod(src, "aggregate");
        Assertions.assertTrue(m.isPresent(), "should extract nested-generic method");
        String body = m.get();
        Assertions.assertEquals(
                body.chars().filter(c -> c == '{').count(),
                body.chars().filter(c -> c == '}').count(),
                "braces must be balanced; got: " + body);
    }
}
