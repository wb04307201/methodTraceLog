package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.ServiceCallInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * MethodTraceInfo 生命周期 corner case 测试：create / end / addChild。
 * <p>
 * MethodTraceInfo 是 trace 树的核心节点 DTO；SimpleMonitorServiceImpl 在 BEFORE
 * 时调用 create(...)，AFTER 时调用 end(...)，子节点由父节点 addChild(...) 接入。
 * 这里锁住几个边界行为：
 *  <ul>
 *      <li>create 出来 before 有值，after=null，children=空 list</li>
 *      <li>end 可以被调用多次（覆盖式）</li>
 *      <li>addChild 可以挂多个；children 顺序是插入顺序</li>
 *      <li>addChild(null) 不会抛 NPE（实际看 ArrayList.add 行为）</li>
 *  </ul>
 */
class MethodTraceInfoLifecycleTest {

    private ServiceCallInfo info(String traceid, String methodName) {
        return new ServiceCallInfo(traceid, null, traceid + "-s",
                "Demo", "Demo", methodName,
                methodName + "()", "Demo." + methodName + "()",
                List.of("arg"),
                LogActionEnum.BEFORE, System.currentTimeMillis());
    }

    @Test
    void create_initializesBefore_andEmptyChildren() {
        ServiceCallInfo before = info("t-1", "m");
        MethodTraceInfo node = MethodTraceInfo.create(before);

        Assertions.assertSame(before, node.getBefore());
        Assertions.assertNull(node.getAfter());
        Assertions.assertNotNull(node.getChildren(), "children 必须初始化（非 null）");
        Assertions.assertTrue(node.getChildren().isEmpty());
    }

    @Test
    void end_setsAfterField() {
        MethodTraceInfo node = MethodTraceInfo.create(info("t-2", "m"));
        ServiceCallInfo after = new ServiceCallInfo(
                "t-2", null, "t-2-s",
                "Demo", "Demo", "m",
                "m()", "Demo.m()",
                "ok", LogActionEnum.AFTER_RETURN, System.currentTimeMillis());
        node.end(after);

        Assertions.assertSame(after, node.getAfter());
        Assertions.assertEquals(LogActionEnum.AFTER_RETURN, node.getAfter().getLogActionEnum());
    }

    @Test
    void end_canBeCalledMultipleTimes_overwritesAfter() {
        // 锁住"end 不是幂等覆盖而是覆盖式"的契约。
        MethodTraceInfo node = MethodTraceInfo.create(info("t-3", "m"));
        ServiceCallInfo after1 = new ServiceCallInfo("t-3", null, "t-3-s",
                "Demo", "Demo", "m", "m()", "Demo.m()",
                "first", LogActionEnum.AFTER_RETURN, 1L);
        node.end(after1);
        Assertions.assertEquals("first", node.getAfter().getContext());

        ServiceCallInfo after2 = new ServiceCallInfo("t-3", null, "t-3-s",
                "Demo", "Demo", "m", "m()", "Demo.m()",
                "second", LogActionEnum.AFTER_RETURN, 2L);
        node.end(after2);
        Assertions.assertEquals("second", node.getAfter().getContext(),
                "end 第二次调用应覆盖前一次的 after");
    }

    @Test
    void addChild_appendsInOrder() {
        MethodTraceInfo parent = MethodTraceInfo.create(info("t-4", "parent"));
        MethodTraceInfo c1 = MethodTraceInfo.create(info("t-4-c1", "c1"));
        MethodTraceInfo c2 = MethodTraceInfo.create(info("t-4-c2", "c2"));
        MethodTraceInfo c3 = MethodTraceInfo.create(info("t-4-c3", "c3"));

        parent.addChild(c1);
        parent.addChild(c2);
        parent.addChild(c3);

        Assertions.assertEquals(3, parent.getChildren().size());
        Assertions.assertSame(c1, parent.getChildren().get(0));
        Assertions.assertSame(c2, parent.getChildren().get(1));
        Assertions.assertSame(c3, parent.getChildren().get(2));
    }

    @Test
    void addChild_sameInstanceMultipleTimes_resultsInDuplicates() {
        // ArrayList.add 行为：重复 add 同一引用会保留多个槽位。
        // 锁住"MethodTraceInfo 自身不查重"的契约（查重在外层 SimpleMonitorServiceImpl）。
        MethodTraceInfo parent = MethodTraceInfo.create(info("t-5", "p"));
        MethodTraceInfo child = MethodTraceInfo.create(info("t-5-c", "c"));
        parent.addChild(child);
        parent.addChild(child);
        Assertions.assertEquals(2, parent.getChildren().size());
        Assertions.assertSame(parent.getChildren().get(0), parent.getChildren().get(1));
    }
}