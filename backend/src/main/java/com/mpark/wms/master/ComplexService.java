package com.mpark.wms.master;

import com.mpark.wms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ComplexService {

    private final ComplexRepository repo;

    @Transactional(readOnly = true)
    public List<Complex> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public Complex get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Complex create(ComplexRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        if (isBlank(r.code())) throw ApiException.badRequest("코드를 입력하세요.");
        Complex c = new Complex();
        c.setCode(r.code().trim());
        c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        return repo.save(c);
    }

    public Complex update(String id, ComplexRequest r) {
        Complex c = repo.findById(id).orElseThrow(() -> ApiException.notFound("단지를 찾을 수 없습니다."));
        if (!isBlank(r.code())) c.setCode(r.code().trim());
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        return repo.save(c);
    }

    public void remove(String id) {
        repo.deleteById(id);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
