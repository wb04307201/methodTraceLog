package cn.wubo.method.trace.log;


public interface ICallService {

    Boolean getEnable();

    void setEnable(Boolean enable);

    void consumer(ServiceCallInfo serviceCallInfo);

    String getCallServiceName();

    String getCallServiceDesc();

}
