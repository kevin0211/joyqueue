package com.jd.journalq.other;

import com.jd.journalq.monitor.RestResponse;
import com.jd.journalq.exception.ServiceException;
import com.jd.journalq.service.BrokerRestUrlMappingService;
import com.jd.journalq.util.HttpUtil;
import com.jd.journalq.util.JSONParser;
import com.jd.journalq.util.NullUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("httpRestService")
public class HttpRestServiceImpl implements HttpRestService {
    private final static Logger logger= LoggerFactory.getLogger(HttpRestServiceImpl.class);

    @Autowired
    private BrokerRestUrlMappingService urlMappingService;
    @Override
    public <T> RestResponse<T> get(String pathKey, Class dataClass, boolean isList, String... args) {
        String urlTemplate= urlMappingService.urlTemplate(pathKey);
        String url;
        if(!NullUtil.isEmpty(args)){
           //args= UrlEncoderUtil.encodeParam(args);
           url= String.format(urlTemplate,args);
       }else{
           url=urlTemplate;
        }
        try {
            logger.info("http request:"+url);
            String responseString = HttpUtil.get(url);
           return JSONParser.parse(responseString, RestResponse.class, dataClass, isList);
        }catch (ServiceException e){
            logger.info("proxy monitor exception",e);
            throw e;
        }catch (Exception e){
            throw new ServiceException(ServiceException.IO_ERROR, e.getMessage());
        }
    }

    @Override
    public <T> RestResponse<T> put(String pathKey, Class dataClass, boolean isList, String content, String... args) {
        String urlTemplate= urlMappingService.urlTemplate(pathKey);
        String url;
        if(!NullUtil.isEmpty(args)){
            //args= UrlEncoderUtil.encodeParam(args);
            url= String.format(urlTemplate,args);
        }else{
            url=urlTemplate;
        }
        try {
            logger.info("http request:"+url);
            String responseString = HttpUtil.put(url,content);
            return JSONParser.parse(responseString, RestResponse.class, dataClass, isList);
        }catch (ServiceException e){
            logger.info("proxy monitor exception",e);
            throw e;
        }catch (Exception e){
            throw new ServiceException(ServiceException.IO_ERROR, e.getMessage());
        }
    }
}