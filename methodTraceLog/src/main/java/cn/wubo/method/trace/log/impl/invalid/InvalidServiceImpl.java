package cn.wubo.method.trace.log.impl.invalid;

import cn.wubo.method.trace.log.ICallService;
import cn.wubo.method.trace.log.ServiceCallInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidServiceImpl implements ICallService {

    @Override
    public void consumer(ServiceCallInfo serviceCallInfo) {
        // TODO document why this method is empty
    }
}
