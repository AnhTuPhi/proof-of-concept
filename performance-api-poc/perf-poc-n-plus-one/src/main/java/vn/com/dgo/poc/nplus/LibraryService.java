package vn.com.dgo.poc.nplus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LibraryService {

    private final AuthorRepository authorRepo;
    private final BookRepository bookRepo;

    public LibraryService(AuthorRepository authorRepo, BookRepository bookRepo) {
        this.authorRepo = authorRepo;
        this.bookRepo = bookRepo;
    }

    /**
     * Naive — N+1: 1 query lấy authors, sau đó N query lấy books mỗi author.
     */
    @Transactional(readOnly = true)
    public List<AuthorDto> naive() {
        return authorRepo.findAll().stream()
                .map(a -> new AuthorDto(a.getId(), a.getName(),
                        a.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }

    /**
     * Manual batch — mô phỏng @BatchSize: 1 query authors + 1 query books (IN clause).
     * Logic này chính là cái @BatchSize tự động làm dưới mui.
     */
    @Transactional(readOnly = true)
    public List<AuthorDto> manualBatch() {
        List<Author> authors = authorRepo.findAll();
        List<Long> ids = authors.stream().map(Author::getId).toList();
        Map<Long, List<String>> titlesByAuthor = bookRepo.findByAuthorIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                        Book::getAuthorId,
                        Collectors.mapping(Book::getTitle, Collectors.toList())));
        return authors.stream()
                .map(a -> new AuthorDto(a.getId(), a.getName(),
                        titlesByAuthor.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    /**
     * JPQL LEFT JOIN FETCH — 1 query duy nhất.
     * Cảnh báo: cartesian product nếu nhiều @OneToMany cùng fetch.
     */
    @Transactional(readOnly = true)
    public List<AuthorDto> joinFetch() {
        return authorRepo.findAllWithJoinFetch().stream()
                .map(a -> new AuthorDto(a.getId(), a.getName(),
                        a.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }

    /**
     * EntityGraph — Hibernate sinh LEFT OUTER JOIN, kết quả tương tự joinFetch
     * nhưng tách sang annotation/metadata thay vì JPQL — dùng lại được nhiều nơi.
     */
    @Transactional(readOnly = true)
    public List<AuthorDto> entityGraph() {
        return authorRepo.findAllWithEntityGraph().stream()
                .map(a -> new AuthorDto(a.getId(), a.getName(),
                        a.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }

    public record AuthorDto(Long id, String name, List<String> bookTitles) {
    }
}
