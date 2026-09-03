package vn.com.dgo.poc.nplus;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService service;
    private final AuthorRepository authorRepo;
    private final Statistics stats;

    public LibraryController(LibraryService service,
                             AuthorRepository authorRepo,
                             EntityManagerFactory emf) {
        this.service = service;
        this.authorRepo = authorRepo;
        this.stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    @GetMapping("/naive")
    public List<LibraryService.AuthorDto> naive() {
        return service.naive();
    }

    @GetMapping("/batch")
    public List<LibraryService.AuthorDto> batch() {
        return service.manualBatch();
    }

    @GetMapping("/join-fetch")
    public List<LibraryService.AuthorDto> joinFetch() {
        return service.joinFetch();
    }

    @GetMapping("/entity-graph")
    public List<LibraryService.AuthorDto> entityGraph() {
        return service.entityGraph();
    }

    @PostMapping("/seed")
    @Transactional
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "50") int authors,
            @RequestParam(defaultValue = "5") int booksPerAuthor) {
        authorRepo.deleteAllInBatch();
        List<Author> batch = new ArrayList<>(authors);
        for (int i = 1; i <= authors; i++) {
            Author a = new Author("Author " + i);
            for (int j = 1; j <= booksPerAuthor; j++) {
                a.getBooks().add(new Book("Book " + i + "-" + j));
            }
            batch.add(a);
        }
        authorRepo.saveAll(batch);
        return Map.of("authors", authors, "totalBooks", authors * booksPerAuthor);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "queryExecutionCount", stats.getQueryExecutionCount(),
                "entityLoadCount", stats.getEntityLoadCount(),
                "collectionLoadCount", stats.getCollectionLoadCount(),
                "connectCount", stats.getConnectCount(),
                "prepareStatementCount", stats.getPrepareStatementCount());
    }

    @PostMapping("/stats/reset")
    public Map<String, Object> resetStats() {
        stats.clear();
        return Map.of("cleared", true);
    }
}
