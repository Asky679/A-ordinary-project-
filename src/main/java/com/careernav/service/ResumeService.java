package com.careernav.service;
import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.careernav.entity.ResumeEntity;
import com.careernav.repository.ResumeRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
@Service
public class ResumeService {
 private final ResumeRepository repository; private final JsonCodec jsonCodec;
 public ResumeService(ResumeRepository repository,JsonCodec jsonCodec){this.repository=repository;this.jsonCodec=jsonCodec;}
 @Transactional(readOnly=true) public List<ResumeView> list(){return repository.findAll(Sort.by(Sort.Direction.DESC,"updatedAt")).stream().map(this::toView).toList();}
 @Transactional(readOnly=true) public ResumeView get(Long id){return toView(getEntity(id));}
 @Transactional(readOnly=true) public ResumeEntity getEntity(Long id){return repository.findById(id).orElseThrow(()->new BusinessException("简历不存在："+id));}
 @Transactional public ResumeView create(ResumePayload payload){ResumeEntity e=new ResumeEntity();copy(payload,e);LocalDateTime now=LocalDateTime.now();e.setCreatedAt(now);e.setUpdatedAt(now);return toView(repository.save(e));}
 @Transactional public ResumeView update(Long id,ResumePayload payload){ResumeEntity e=getEntity(id);copy(payload,e);e.setUpdatedAt(LocalDateTime.now());return toView(repository.save(e));}
 @Transactional public void delete(Long id){if(!repository.existsById(id))throw new BusinessException("简历不存在："+id);repository.deleteById(id);}
 public ResumeView toView(ResumeEntity e){return new ResumeView(e.getId(),e.getOwnerName(),e.getTargetRole(),e.getCity(),e.getPhone(),e.getEmail(),e.getEducation(),e.getSchool(),e.getMajor(),e.getGraduationYear(),jsonCodec.readList(e.getSkillsJson(),String.class),jsonCodec.readList(e.getProjectsJson(),ProjectItem.class),jsonCodec.readList(e.getExperiencesJson(),ExperienceItem.class),e.getSelfEvaluation(),e.getRawText(),e.getCreatedAt(),e.getUpdatedAt());}
 private void copy(ResumePayload p,ResumeEntity e){e.setOwnerName(p.ownerName().trim());e.setTargetRole(p.targetRole().trim());e.setCity(p.city());e.setPhone(p.phone());e.setEmail(p.email());e.setEducation(p.education());e.setSchool(p.school());e.setMajor(p.major());e.setGraduationYear(p.graduationYear());e.setSkillsJson(jsonCodec.write(p.skills()==null?List.of():p.skills()));e.setProjectsJson(jsonCodec.write(p.projects()==null?List.of():p.projects()));e.setExperiencesJson(jsonCodec.write(p.experiences()==null?List.of():p.experiences()));e.setSelfEvaluation(p.selfEvaluation());e.setRawText(p.rawText());}
}
