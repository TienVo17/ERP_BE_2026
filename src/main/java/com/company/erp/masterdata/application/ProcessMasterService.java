package com.company.erp.masterdata.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.*;
import com.company.erp.masterdata.infrastructure.ProcessMasterJdbcRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessMasterService {
    private final ProcessMasterJdbcRepository repository; private final AuditEventWriter audit;
    public ProcessMasterService(ProcessMasterJdbcRepository repository,AuditEventWriter audit){this.repository=repository;this.audit=audit;}
    public PageResponse<ProcessResponse> list(int page,int size,List<String> requested,String status,String name){AdminQuery.validatePage(page,size);String s=MasterDataSupport.status(status),n=MasterDataSupport.optionalText(name);List<String> sort=repository.sort(requested);return MasterDataSupport.page(repository.list(page,size,s,n,sort),page,size,repository.count(s,n),AdminQuery.filters("status",s,"name",n),sort);}
    public ProcessResponse get(UUID id){return repository.find(id).orElseThrow(()->new ResourceNotFoundException("process",id.toString()));}
    @Transactional public ProcessResponse create(ProcessCreateRequest q,ErpPrincipal a,String rid){ProcessCreateRequest n=new ProcessCreateRequest(MasterDataSupport.requiredText(q.name()),q.sequenceNo(),MasterDataSupport.requiredText(q.qrValue()));if(repository.keyExists(n.name(),n.qrValue(),null))duplicate();UUID id=repository.create(n,a.user().id());ProcessResponse after=get(id);write(a,"CREATE",id,rid,null,summary(after));return after;}
    @Transactional public ProcessResponse update(UUID id,long v,ProcessUpdateRequest q,ErpPrincipal a,String rid){try{ProcessResponse before=get(id);if(repository.used(id))MasterDataSupport.inUse("Process");ProcessUpdateRequest n=new ProcessUpdateRequest(MasterDataSupport.requiredText(q.name()),q.sequenceNo());if(repository.keyExists(n.name(),before.qrValue(),id))duplicate();if(repository.update(id,v,n,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.find(id).isPresent());ProcessResponse after=get(id);write(a,"UPDATE",id,rid,summary(before),summary(after));return after;}catch(DataIntegrityViolationException e){throw MasterDataSupport.mapUsageRace(e,"Process");}}
    @Transactional public ProcessResponse archive(UUID id,long v,ErpPrincipal a,String rid){try{ProcessResponse before=get(id);if(repository.used(id))MasterDataSupport.inUse("Process");if(repository.archive(id,v,a.user().id())!=1)MasterDataSupport.staleOrMissing(repository.find(id).isPresent());ProcessResponse after=get(id);write(a,"ARCHIVE",id,rid,summary(before),summary(after));return after;}catch(DataIntegrityViolationException e){throw MasterDataSupport.mapUsageRace(e,"Process");}}
    private static void duplicate(){throw new ApiException(ApiErrorCode.DUPLICATE_BUSINESS_KEY,"A resource with the same business key already exists.");}
    private void write(ErpPrincipal a,String action,UUID id,String rid,Map<String,Object> before,Map<String,Object> after){audit.write(a.user().id(),action,"PROCESS",id,rid,null,before,after);}
    private static Map<String,Object> summary(ProcessResponse v){return Map.of("id",v.id(),"version",v.version(),"status",v.status(),"name",v.name(),"sequenceNo",v.sequenceNo(),"qrValue",v.qrValue());}
}
