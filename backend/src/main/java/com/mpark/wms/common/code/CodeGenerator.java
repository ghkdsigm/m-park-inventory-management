package com.mpark.wms.common.code;

import com.mpark.wms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동코드 생성 — Postgres 의 set_seq_code 트리거 대체.
 * 예: next("categories", "CTG") → "CTG-000001"
 * 호출하는 create 서비스의 트랜잭션에 합류해 락이 커밋까지 유지된다.
 */
@Service
@RequiredArgsConstructor
public class CodeGenerator {

    private final SeqCounterRepository repo;

    @Transactional
    public String next(String seqName, String prefix) {
        SeqCounter c = repo.findByNameForUpdate(seqName)
                .orElseThrow(() -> ApiException.badRequest("시퀀스를 찾을 수 없습니다: " + seqName));
        long v = c.getVal() + 1;
        c.setVal(v);
        return prefix + "-" + String.format("%06d", v);
    }
}
