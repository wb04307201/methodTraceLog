package cn.wubo.method.trace.log;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public interface ICallService {

    Boolean getEnable();

    void setEnable(Boolean enable);

    void consumer(ServiceCallInfo serviceCallInfo);

    String  getCallServiceName();

    String  getCallServiceDesc();

}
