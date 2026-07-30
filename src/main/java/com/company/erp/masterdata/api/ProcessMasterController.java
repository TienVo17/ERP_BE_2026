package com.company.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.*;
import com.company.erp.masterdata.application.MasterDataSupport;
import com.company.erp.masterdata.application.ProcessMasterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/master-data/processes") @Validated
public class ProcessMasterController {
    private final ProcessMasterService service; public ProcessMasterController(ProcessMasterService service){this.service=service;}
    @GetMapping @PreAuthorize("hasAuthority('PROCESS:VIEW')") PageResponse<ProcessResponse> list(@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="25")@Min(1)@Max(100)int size,@RequestParam(required=false)List<String> sort,@RequestParam(required=false)String status,@RequestParam(required=false)String name){return service.list(page,size,sort,status,name);}
    @PostMapping @PreAuthorize("hasAuthority('PROCESS:CREATE')") ResponseEntity<ProcessResponse> create(@Valid@RequestBody ProcessCreateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return ResponseEntity.status(201).body(service.create(q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r)));}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('PROCESS:VIEW')") ProcessResponse get(@PathVariable UUID id){return service.get(id);}
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('PROCESS:UPDATE')") ProcessResponse update(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,@Valid@RequestBody ProcessUpdateRequest q,JwtAuthenticationToken a,HttpServletRequest r){return service.update(id,MasterDataSupport.version(m),q,MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
    @PostMapping("/{id}/archive") @PreAuthorize("hasAuthority('PROCESS:ARCHIVE')") ProcessResponse archive(@PathVariable UUID id,@RequestHeader(HttpHeaders.IF_MATCH)String m,JwtAuthenticationToken a,HttpServletRequest r){return service.archive(id,MasterDataSupport.version(m),MasterDataSupport.principal(a),MasterDataSupport.requestId(r));}
}
