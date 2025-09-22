package cn.wubo.method.trace.log.impl.monitor;

import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MethodTraceInfo {

    private ServiceCallInfo before;
    private ServiceCallInfo after;

    private List<MethodTraceInfo> children = new ArrayList<>();


    public static MethodTraceInfo create(ServiceCallInfo before) {
        MethodTraceInfo methodTraceInfo = new MethodTraceInfo();
        methodTraceInfo.setBefore(before);
        return methodTraceInfo;
    }

    public void end(ServiceCallInfo after) {
        this.setAfter(after);
    }

    public void addChild(MethodTraceInfo child) {
        children.add(child);
    }
}
