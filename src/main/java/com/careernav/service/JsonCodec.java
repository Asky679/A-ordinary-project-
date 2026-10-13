package com.careernav.service;
import com.careernav.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class JsonCodec {
 private final ObjectMapper mapper;
 public JsonCodec(ObjectMapper mapper){this.mapper=mapper;}
 public String write(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new BusinessException("数据序列化失败");}}
 public <T> T read(String value,Class<T> type){if(value==null||value.isBlank())return null;try{return mapper.readValue(value,type);}catch(JsonProcessingException e){throw new BusinessException("数据读取失败");}}
 public <T> List<T> readList(String value,Class<T> type){if(value==null||value.isBlank())return List.of();try{return mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,type));}catch(JsonProcessingException e){throw new BusinessException("列表数据读取失败");}}
}
