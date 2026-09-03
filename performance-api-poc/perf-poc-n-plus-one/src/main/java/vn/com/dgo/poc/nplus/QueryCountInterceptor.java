package vn.com.dgo.poc.nplus;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Đếm số JDBC statement Hibernate phát ra trong một HTTP request.
 *
 * Cách hoạt động: chụp Statistics trước & sau handler, tính delta, set vào header X-Query-Count.
 * Nếu vượt ngưỡng → log WARN (production có thể fail request hoặc emit metric).
 *
 * Caveat: Hibernate Statistics global cho cả SessionFactory. Demo này giả định
 * traffic thấp / load test single-threaded để delta sạch. Production-grade cần
 * `HibernateStatistics` per-session hoặc dùng datasource-proxy / p6spy + ThreadLocal counter.
 */
@Component
public class QueryCountInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(QueryCountInterceptor.class);
    private static final String ATTR = "queryCountStart";

    private final Statistics stats;
    private final long warnThreshold;

    public QueryCountInterceptor(EntityManagerFactory emf,
                                 @Value("${query-count.warn-threshold:5}") long warnThreshold) {
        this.stats = emf.unwrap(SessionFactory.class).getStatistics();
        this.stats.setStatisticsEnabled(true);
        this.warnThreshold = warnThreshold;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR, currentCount());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           ModelAndView modelAndView) {
        Object before = request.getAttribute(ATTR);
        if (before == null) {
            return;
        }
        long delta = currentCount() - (long) before;
        response.setHeader("X-Query-Count", String.valueOf(delta));
        if (delta > warnThreshold) {
            log.warn("N+1 suspect — {} {} fired {} queries (threshold {})",
                    request.getMethod(), request.getRequestURI(), delta, warnThreshold);
        }
    }

    private long currentCount() {
        // Tổng "đơn vị work" trên DB cho 1 request:
        // - queryExecutionCount: JPQL/Criteria
        // - entityLoadCount + collectionLoadCount: cả lazy lẫn eager hydrate
        return stats.getQueryExecutionCount()
                + stats.getEntityLoadCount()
                + stats.getCollectionLoadCount();
    }
}
