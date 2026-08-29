package cn.wubo.method.trace.log.model;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * ServiceCallInfo.copyOf(...) 的浅拷贝语义测试。
 * <p>
 * copyOf 用于在 LogAspect 中 BEFORE → AFTER 时复用元数据。本测试覆盖：
 *  <ul>
 *      <li>字段完全相同（11 字段 + rawException）</li>
 *      <li>返回的是新实例（不是同引用）</li>
 *      <li>context 字段是浅拷贝（同一引用）</li>
 *      <li>修改原始对象的 context 不影响副本</li>
 *      <li>rawException 字段也复制</li>
 *  </ul>
 */
class ServiceCallInfoCopyOfTest {

    private ServiceCallInfo makeSample(String traceid) {
        return new ServiceCallInfo(
                traceid, null, traceid + "-s",
                "cn.wubo.Foo", "Foo", "bar",
                "bar()", "cn.wubo.Foo.bar()",
                new ArrayList<>(List.of(1, 2, 3)),
                LogActionEnum.BEFORE, 1234L);
    }

    @Test
    void copyOf_preservesAllFields() {
        ServiceCallInfo orig = makeSample("t-1");
        orig.setRawException(new RuntimeException("boom"));

        ServiceCallInfo copy = ServiceCallInfo.copyOf(orig);

        Assertions.assertEquals(orig.getTraceid(), copy.getTraceid());
        Assertions.assertEquals(orig.getPspanid(), copy.getPspanid());
        Assertions.assertEquals(orig.getSpanid(), copy.getSpanid());
        Assertions.assertEquals(orig.getClassName(), copy.getClassName());
        Assertions.assertEquals(orig.getClassSimpleName(), copy.getClassSimpleName());
        Assertions.assertEquals(orig.getMethodName(), copy.getMethodName());
        Assertions.assertEquals(orig.getMethodSignatureShortString(), copy.getMethodSignatureShortString());
        Assertions.assertEquals(orig.getMethodSignatureLongString(), copy.getMethodSignatureLongString());
        Assertions.assertSame(orig.getContext(), copy.getContext(), "context 是浅拷贝：同引用");
        Assertions.assertEquals(orig.getLogActionEnum(), copy.getLogActionEnum());
        Assertions.assertEquals(orig.getTimeMillis(), copy.getTimeMillis());
        Assertions.assertSame(orig.getRawException(), copy.getRawException(), "rawException 也必须复制");
    }

    @Test
    void copyOf_returnsNewInstance() {
        ServiceCallInfo orig = makeSample("t-2");
        ServiceCallInfo copy = ServiceCallInfo.copyOf(orig);
        Assertions.assertNotSame(orig, copy, "copyOf 必须返回新实例");
    }

    @Test
    void copyOf_doesNotShareMutableList() {
        // 共享 List 引用是 copyOf 的"浅拷贝"语义的明确体现。
        // 即 list.add(...) 会同时影响 orig 与 copy。
        // 本测试把这个约定锁住，防止有人偷偷改成深拷贝。
        ServiceCallInfo orig = makeSample("t-3");
        ServiceCallInfo copy = ServiceCallInfo.copyOf(orig);

        @SuppressWarnings("unchecked")
        List<Integer> origList = (List<Integer>) orig.getContext();
        @SuppressWarnings("unchecked")
        List<Integer> copyList = (List<Integer>) copy.getContext();
        Assertions.assertSame(origList, copyList);

        copyList.add(99);
        Assertions.assertTrue(origList.contains(99),
                "浅拷贝语义：list 是同一引用，从 copy 修改会反映到 orig");
    }

    @Test
    void copyOf_doesNotMutateOriginal() {
        // 反向验证：mutate orig 后，copy 字段除 context 共享部分外保持原值。
        ServiceCallInfo orig = makeSample("t-4");
        ServiceCallInfo copy = ServiceCallInfo.copyOf(orig);

        orig.setMethodName("mutated");
        orig.setLogActionEnum(LogActionEnum.AFTER_RETURN);
        orig.setTimeMillis(9999L);

        Assertions.assertEquals("bar", copy.getMethodName());
        Assertions.assertEquals(LogActionEnum.BEFORE, copy.getLogActionEnum());
        Assertions.assertEquals(1234L, copy.getTimeMillis());
    }
}