package com.mpark.wms.master;

import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository repo;
    private final CodeGenerator codeGenerator;

    @Transactional(readOnly = true)
    public List<Category> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public Category get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Category create(CategoryRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        Category c = new Category();
        c.setCode(codeGenerator.next("categories", "CTG"));   // 자동코드
        apply(c, r);
        return repo.save(c);
    }

    public Category update(String id, CategoryRequest r) {
        Category c = repo.findById(id).orElseThrow(() -> ApiException.notFound("카테고리를 찾을 수 없습니다."));
        apply(c, r);   // code 는 유지
        return repo.save(c);
    }

    public void remove(String id) {
        repo.deleteById(id);
    }

    private void apply(Category c, CategoryRequest r) {
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        c.setPathLabel(nz(r.pathLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
