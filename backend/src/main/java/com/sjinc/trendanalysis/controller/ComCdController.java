package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.dto.comcd.ComCdRequest;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.comcd.ComCdService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공통코드 관리 화면과 각 화면의 select(com-cd.js)가 호출하는 API. 사용중 코드 조회는 로그인만 요구하고, includeInactive=true와
 * 저장/삭제는 "com-cd-mng" 권한이 필요하다. getCodes만 {@code @PreAuthorize}를 쓰는 것은 쿼리 파라미터 값에 따라
 * 권한을 요구해야 해서 메서드 전체에 걸리는 {@code @RequiresProgram}으로는 표현할 수 없기 때문이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/com-cds")
public class ComCdController {

    private final ComCdService comCdService;

    /** includeInactive=true면 사용 중지된 코드까지(공통코드 관리 화면), 아니면 사용중인 코드만(select). */
    @GetMapping
    @PreAuthorize("!#includeInactive or @programAccess.check(authentication.name, 'com-cd-mng')")
    public List<ComCd> getCodes(@RequestParam String group,
                                @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return includeInactive
                ? comCdService.getAllCodesByGroup(group)
                : comCdService.getCodesByGroup(group);
    }

    @PostMapping
    @RequiresProgram("com-cd-mng")
    public ComCd saveCode(@RequestBody ComCdRequest request) {
        return comCdService.saveCode(request);
    }

    @DeleteMapping("/{id}")
    @RequiresProgram("com-cd-mng")
    public void deleteCode(@PathVariable Long id) {
        comCdService.deleteCode(id);
    }
}
