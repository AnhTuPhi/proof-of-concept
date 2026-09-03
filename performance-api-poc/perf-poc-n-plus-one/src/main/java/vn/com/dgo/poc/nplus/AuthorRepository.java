package vn.com.dgo.poc.nplus;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /** Lazy collection — gây N+1 nếu loop access books. */
    @Override
    List<Author> findAll();

    /** Một query với LEFT JOIN FETCH — 1 query cho cả author + book. */
    @Query("select distinct a from Author a left join fetch a.books")
    List<Author> findAllWithJoinFetch();

    /** EntityGraph — Hibernate dịch ra LEFT OUTER JOIN tương đương JOIN FETCH. */
    @EntityGraph(value = "Author.books")
    @Query("select a from Author a")
    List<Author> findAllWithEntityGraph();
}
