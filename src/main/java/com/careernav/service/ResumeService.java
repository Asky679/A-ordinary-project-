package com.careernav.service;
import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.careernav.entity.ResumeEntity;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.ResumeVersionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
@Service
public class ResumeService {
 private final ResumeRepository repository; private final ResumeVersionRepository versionRepository; private final JsonCodec jsonCodec;
 public ResumeService(ResumeRepository repository,ResumeVersionRepository versionRepository,JsonCodec jsonCodec){this.repository=repository;this.versionRepository=versionRepository;this.jsonCodec=jsonCodec;}
 @Transactional(readOnly=true) public List<ResumeView> list(){return repository.findAll(Sort.by(Sort.Direction.DESC,"updatedAt")).stream().map(this::toView).toList();}
 @Transactional(readOnly=true) public ResumeView get(Long id){return toView(getEntity(id));}
 @Transactional(readOnly=true) public ResumeEntity getEntity(Long id){return repository.findById(id).orElseThrow(()->new BusinessException("简历不存在："+id));}
 @Transactional public ResumeView create(ResumePayload payload){ResumeEntity e=new ResumeEntity();copy(payload,e);LocalDateTime now=LocalDateTime.now();e.setCreatedAt(now);e.setUpdatedAt(now);return toView(repository.save(e));}
 @Transactional public ResumeView update(Long id,ResumePayload payload){return update(id,payload,false);}
 @Transactional public ResumeView update(Long id,ResumePayload payload,boolean force){
  ResumeEntity e=getEntity(id);
  if(!force){List<String> changed=changedLockedFields(e,payload);
   if(!changed.isEmpty())throw new BusinessException("以下事实字段已锁定，不能修改："+String.join("、",changed)+"。请先解锁，或选择强制保存。");}
  copy(payload,e);e.setUpdatedAt(LocalDateTime.now());return toView(repository.save(e));}
 @Transactional public ResumeView applyWithoutLockCheck(Long id,ResumePayload payload){
  ResumeEntity e=getEntity(id);copy(payload,e);e.setUpdatedAt(LocalDateTime.now());return toView(repository.save(e));}
 private String n(String v){return v==null?"":v.trim();}
 private List<String> changedLockedFields(ResumeEntity e,ResumePayload p){
  List<String> locks=jsonCodec.readList(e.getFactLocksJson(),String.class);
  if(locks.isEmpty())return List.of();
  Map<String,String> now=new LinkedHashMap<>(),next=new LinkedHashMap<>();
  now.put("ownerName",n(e.getOwnerName()));next.put("ownerName",n(p.ownerName()));
  now.put("phone",n(e.getPhone()));next.put("phone",n(p.phone()));
  now.put("email",n(e.getEmail()));next.put("email",n(p.email()));
  now.put("school",n(e.getSchool()));next.put("school",n(p.school()));
  now.put("education",n(e.getEducation()));next.put("education",n(p.education()));
  now.put("major",n(e.getMajor()));next.put("major",n(p.major()));
  now.put("graduationYear",e.getGraduationYear()==null?"":String.valueOf(e.getGraduationYear()));
  next.put("graduationYear",p.graduationYear()==null?"":String.valueOf(p.graduationYear()));
  List<String> changed=new ArrayList<>();
  for(String field:locks){if(!Objects.equals(now.getOrDefault(field,""),next.getOrDefault(field,"")))changed.add(lockLabel(field));}
  return changed;}
 private String lockLabel(String field){return switch(field){
  case "ownerName"->"姓名";case "phone"->"手机号";case "email"->"邮箱";case "school"->"学校";
  case "education"->"学历";case "major"->"专业";case "graduationYear"->"毕业年份";default->field;};}
 @Transactional public void delete(Long id){if(!repository.existsById(id))throw new BusinessException("简历不存在："+id);versionRepository.deleteByResumeId(id);repository.deleteById(id);}
 public ResumeView toView(ResumeEntity e){return new ResumeView(e.getId(),e.getOwnerName(),e.getTargetRole(),e.getCity(),e.getPhone(),e.getEmail(),e.getEducation(),e.getSchool(),e.getMajor(),e.getGraduationYear(),jsonCodec.readList(e.getSkillsJson(),String.class),jsonCodec.readList(e.getProjectsJson(),ProjectItem.class),jsonCodec.readList(e.getExperiencesJson(),ExperienceItem.class),e.getSelfEvaluation(),e.getRawText(),e.getCreatedAt(),e.getUpdatedAt());}
 private void copy(ResumePayload p,ResumeEntity e){e.setOwnerName(p.ownerName().trim());e.setTargetRole(p.targetRole().trim());e.setCity(p.city());e.setPhone(p.phone());e.setEmail(p.email());e.setEducation(p.education());e.setSchool(p.school());e.setMajor(p.major());e.setGraduationYear(p.graduationYear());e.setSkillsJson(jsonCodec.write(p.skills()==null?List.of():p.skills()));e.setProjectsJson(jsonCodec.write(p.projects()==null?List.of():p.projects()));e.setExperiencesJson(jsonCodec.write(p.experiences()==null?List.of():p.experiences()));e.setSelfEvaluation(p.selfEvaluation());e.setRawText(p.rawText());}
}
