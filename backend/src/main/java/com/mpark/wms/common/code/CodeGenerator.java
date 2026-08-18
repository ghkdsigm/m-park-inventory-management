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

    /**
     * 스코프별 순번 채번 — 카운터 행이 없으면 만든다(예: "products:{위치id}"는 위치마다 1부터).
     * 반환값은 증가된 순번(long). 코드 문자열 조립은 호출측이 담당한다.
     */
    @Transactional
    public long nextValue(String seqName) {
        SeqCounter c = repo.findByNameForUpdate(seqName).orElse(null);
        if (c == null) {
            c = new SeqCounter();
            c.setName(seqName);
            c.setVal(0);
            repo.saveAndFlush(c);
            c = repo.findByNameForUpdate(seqName).orElse(c); // 생성 후 락 재획득
        }
        long v = c.getVal() + 1;
        c.setVal(v);
        return v;
    }
}
